package dev.aeroschematics.place;

import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.utility.BlockHelper;
import dev.aeroschematics.AeroSchematics;
import dev.aeroschematics.capture.SubLevelCaptureMeta;
import dev.aeroschematics.compat.DriveByWireCompat;
import dev.aeroschematics.compat.Mods;
import dev.aeroschematics.config.AeroConfig;
import dev.aeroschematics.nbt.AeroSanitizer;
import dev.aeroschematics.nbt.LinkRemapper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.createmod.catnip.nbt.NBTProcessors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Builds the physics structures of one schematic placement in a level. Used by both the instant creative placement and
 * the schematicannon. Responsibilities:
 * <ul>
 *     <li>allocate one new sub-level per structure and keep blocks at the same offset from the plot center as in the
 *     original, so that every stored position can be fixed with a translation;</li>
 *     <li>expose a Sable {@link SubLevelSchematicSerializationContext} of type PLACE, so mods that understand schematics
 *     (Simulated swivel bearings, springs, ...) re-target their links themselves;</li>
 *     <li>prepare block entity data: saved under that context, stored resources removed, remaining links re-targeted,
 *     then passed through Create's own NBT processors;</li>
 *     <li>align the new structures with the schematic preview, spawn their entities, restore their wires.</li>
 * </ul>
 */
public final class PlacementSession {
    private static final int MAX_WIRES = 4096;

    private final ServerLevel level;
    private final BlockPos anchor;
    private final SubLevelBlueprint blueprint;
    private final Map<UUID, UUID> newIds = new LinkedHashMap<>();
    private final Map<UUID, ServerSubLevel> built = new HashMap<>();
    private final SubLevelSchematicSerializationContext context = new SubLevelSchematicSerializationContext(SubLevelSchematicSerializationContext.Type.PLACE, null);
    private final Map<UUID, SubLevelCaptureMeta> validMeta = new HashMap<>();
    private LinkRemapper.Table table;

    public PlacementSession(ServerLevel level, BlockPos anchor, SubLevelBlueprint blueprint) {
        this.level = level;
        this.anchor = anchor.immutable();
        this.blueprint = blueprint;
        for (SubLevelBlueprint.Entry entry : blueprint.entries()) {
            this.newIds.put(entry.schematicUuid, UUID.randomUUID());
            if (entry.meta != null && this.isPlausible(entry.meta)) {
                this.validMeta.put(entry.schematicUuid, entry.meta);
            }
        }
        this.table = new LinkRemapper.Table(level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    public ServerLevel level() {
        return this.level;
    }

    public SubLevelBlueprint blueprint() {
        return this.blueprint;
    }

    /** Re-attaches structures that were already allocated (schematicannon resuming after a reload). */
    public void adopt(SubLevelBlueprint.Entry entry, ServerSubLevel subLevel) {
        this.newIds.put(entry.schematicUuid, subLevel.getUniqueId());
        this.register(entry, subLevel);
    }

    @Nullable
    public ServerSubLevel built(SubLevelBlueprint.Entry entry) {
        ServerSubLevel subLevel = this.built.get(entry.schematicUuid);
        return subLevel == null || subLevel.isRemoved() ? null : subLevel;
    }

    public Map<UUID, UUID> builtIds() {
        Map<UUID, UUID> ids = new LinkedHashMap<>();
        this.built.forEach((schematic, subLevel) -> ids.put(schematic, subLevel.getUniqueId()));
        return ids;
    }

    public void forget(SubLevelBlueprint.Entry entry) {
        this.built.remove(entry.schematicUuid);
        this.context.getMappings().remove(entry.schematicUuid);
        this.newIds.put(entry.schematicUuid, UUID.randomUUID());
        this.rebuildTable();
    }

    @Nullable
    private SubLevelCaptureMeta meta(SubLevelBlueprint.Entry entry) {
        return this.validMeta.get(entry.schematicUuid);
    }

    /** Metadata comes from a file a client uploaded: only use it if it describes a real plot square. */
    private boolean isPlausible(SubLevelCaptureMeta meta) {
        SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        if (container == null) {
            return false;
        }
        int expectedSize = 1 << (container.getLogPlotSize() + 4);
        return meta.plotMaxXExclusive() - meta.plotMinX() == expectedSize
                && meta.plotMaxZExclusive() - meta.plotMinZ() == expectedSize
                && container.inBounds(new BlockPos(meta.plotMinX(), 0, meta.plotMinZ()))
                && meta.oldCenter().getX() >= meta.plotMinX() && meta.oldCenter().getX() < meta.plotMaxXExclusive()
                && meta.oldCenter().getZ() >= meta.plotMinZ() && meta.oldCenter().getZ() < meta.plotMaxZExclusive();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Allocation and geometry
    // ------------------------------------------------------------------------------------------------------------------

    /** World position of the template origin of {@code entry}. */
    public Vector3d worldCorner(SubLevelBlueprint.Entry entry) {
        return new Vector3d(entry.corner).add(this.anchor.getX(), this.anchor.getY(), this.anchor.getZ());
    }

    /** Block position (inside the new plot) where the template-local position {@code local} is printed. */
    public BlockPos target(SubLevelBlueprint.Entry entry, BlockPos local) {
        ServerSubLevel subLevel = this.built.get(entry.schematicUuid);
        if (subLevel == null) {
            throw new IllegalStateException("Sub-level " + entry.schematicUuid + " has not been allocated");
        }
        BlockPos center = subLevel.getPlot().getCenterBlock();
        SubLevelCaptureMeta meta = this.meta(entry);
        return meta != null ? meta.placeIn(center, local) : center.offset(local);
    }

    public boolean isInsidePlot(SubLevelBlueprint.Entry entry, BlockPos target) {
        ServerSubLevel subLevel = this.built(entry);
        return subLevel != null && subLevel.getPlot().contains(new ChunkPos(target));
    }

    /** Allocates a new, empty sub-level for {@code entry} posed like the schematic preview. */
    public ServerSubLevel allocate(SubLevelBlueprint.Entry entry) {
        SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        Pose3d pose = new Pose3d();
        pose.orientation().set(entry.orientation);
        pose.position().set(this.worldCorner(entry));
        UUID id = this.newIds.computeIfAbsent(entry.schematicUuid, k -> UUID.randomUUID());
        ServerSubLevel subLevel;
        int[] plot = firstEmptyPlot(container);
        if (plot != null && container.getSubLevel(id) == null) {
            subLevel = (ServerSubLevel) container.allocateSubLevel(id, plot[0], plot[1], pose);
        } else {
            subLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);
        }
        this.register(entry, subLevel);
        this.ensureChunks(entry, subLevel);
        return subLevel;
    }

    private void register(SubLevelBlueprint.Entry entry, ServerSubLevel subLevel) {
        this.built.put(entry.schematicUuid, subLevel);
        this.context.getMappings().put(entry.schematicUuid,
                new SubLevelSchematicSerializationContext.SchematicMapping(null, null, subLevel.getUniqueId(), local -> this.target(entry, (BlockPos) local)));
        this.rebuildTable();
    }

    @Nullable
    private static int[] firstEmptyPlot(SubLevelContainer container) {
        BitSet occupancy = container.getOccupancy();
        int side = 1 << container.getLogSideLength();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                if (!occupancy.get(container.getIndex(x, z)) && container.getSubLevel(x, z) == null) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }

    private void ensureChunks(SubLevelBlueprint.Entry entry, ServerSubLevel subLevel) {
        LevelPlot plot = subLevel.getPlot();
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockPos local : entry.order) {
            BlockPos target = this.target(entry, local);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    chunks.add(new ChunkPos((target.getX() >> 4) + dx, (target.getZ() >> 4) + dz));
                }
            }
        }
        for (ChunkPos chunk : chunks) {
            if (plot.contains(chunk) && plot.getChunkHolder(plot.toLocal(chunk)) == null) {
                plot.newEmptyChunk(chunk);
            }
        }
    }

    /**
     * Moves the structure so that its template origin sits exactly where the schematic preview showed it, then informs
     * the physics engine. Same maths as Sable's own creative placement.
     */
    public void settlePose(SubLevelBlueprint.Entry entry) {
        ServerSubLevel subLevel = this.built(entry);
        if (subLevel == null) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(this.level);
        SubLevelPhysicsSystem physicsSystem = container.physicsSystem();
        PhysicsPipeline pipeline = physicsSystem.getPipeline();

        BlockPos plotCorner = this.target(entry, BlockPos.ZERO);
        Vector3d current = subLevel.logicalPose().transformPosition(new Vector3d(plotCorner.getX(), plotCorner.getY(), plotCorner.getZ()));
        subLevel.logicalPose().position().add(this.worldCorner(entry).sub(current));

        SubLevel containing = Sable.HELPER.getContaining(this.level, subLevel.logicalPose().position());
        if (containing != null && containing != subLevel) {
            SubLevelAssemblyHelper.kickFromContainingSubLevel(this.level, physicsSystem, pipeline, subLevel, containing);
            subLevel.logicalPose().orientation().premul(containing.logicalPose().orientation());
        }
        pipeline.teleport(subLevel, subLevel.logicalPose().position(), subLevel.logicalPose().orientation());
        pipeline.resetVelocity(subLevel);
        subLevel.updateLastPose();
    }

    private void rebuildTable() {
        LinkRemapper.Table table = new LinkRemapper.Table(this.level.getMinBuildHeight(), this.level.getMaxBuildHeight());
        for (SubLevelBlueprint.Entry entry : this.blueprint.entries()) {
            UUID newId = this.newIds.get(entry.schematicUuid);
            SubLevelCaptureMeta meta = this.meta(entry);
            table.uuid(entry.schematicUuid, newId);
            if (meta != null) {
                table.uuid(meta.sourceUuid(), newId);
            }
            ServerSubLevel subLevel = this.built.get(entry.schematicUuid);
            if (meta != null && subLevel != null) {
                BlockPos shift = subLevel.getPlot().getCenterBlock().subtract(meta.oldCenter());
                table.shift(new LinkRemapper.PlotShift(meta.plotMinX(), meta.plotMinZ(), meta.plotMaxXExclusive(), meta.plotMaxZExclusive(),
                        shift.getX(), shift.getY(), shift.getZ()));
            }
        }
        this.table = table;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Block entity data
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * The data a printed block gets: everything the block entity saves (settings included) minus stored resources,
     * with links re-targeted to the new structures. {@code null} if the block has no block entity.
     */
    @Nullable
    public CompoundTag prepareBlockData(SubLevelBlueprint.Entry entry, BlockPos local, BlockState state) {
        BlockEntity schematicBlockEntity = entry.level.getBlockEntity(local);
        if (schematicBlockEntity == null) {
            return null;
        }
        CompoundTag tag;
        SubLevelSchematicSerializationContext previous = SubLevelSchematicSerializationContext.getCurrentContext();
        SubLevelSchematicSerializationContext.setCurrentContext(this.context);
        try {
            tag = schematicBlockEntity.saveWithFullMetadata(this.level.registryAccess());
        } catch (RuntimeException e) {
            AeroSchematics.LOGGER.error("Could not save schematic block entity {} at {}, printing it without data", schematicBlockEntity.getType(), local, e);
            return null;
        } finally {
            SubLevelSchematicSerializationContext.setCurrentContext(previous);
        }
        AeroSanitizer.sanitize(schematicBlockEntity, tag);
        LinkRemapper.remap(tag, this.table);
        tag = NBTProcessors.process(state, schematicBlockEntity, tag, true);
        if (tag != null) {
            tag.remove("x");
            tag.remove("y");
            tag.remove("z");
        }
        return tag;
    }

    /** What printing this block costs, computed from exactly the data that will be placed (as Create does). */
    public ItemRequirement requirement(SubLevelBlueprint.Entry entry, BlockPos local, BlockState state, @Nullable CompoundTag data) {
        BlockEntity blockEntity = null;
        if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock entityBlock) {
            blockEntity = entityBlock.newBlockEntity(local, state);
            if (blockEntity != null && data != null) {
                try {
                    blockEntity.loadWithComponents(data, this.level.registryAccess());
                } catch (RuntimeException e) {
                    AeroSchematics.LOGGER.debug("Could not load data for requirement of {}", state, e);
                }
            }
        }
        return ItemRequirement.of(state, blockEntity);
    }

    /** Places one block right away (creative placement, or the seed block of a survival print). */
    public void placeNow(SubLevelBlueprint.Entry entry, BlockPos local, @Nullable ItemStack stack) {
        BlockState state = entry.stateAt(local);
        BlockPos target = this.target(entry, local);
        if (!this.isInsidePlot(entry, target)) {
            AeroSchematics.LOGGER.warn("Skipping block {} of a physics structure: outside of its plot", local);
            return;
        }
        CompoundTag data = this.prepareBlockData(entry, local, state);
        BlockHelper.placeSchematicBlock(this.level, state, target, stack, data);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------------------------------------------------------

    /** A copy of {@code entity} moved into the new plot, with stored items removed and links re-targeted. */
    @Nullable
    public Entity prepareEntity(SubLevelBlueprint.Entry entry, Entity entity) {
        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) {
            return null;
        }
        AeroSanitizer.sanitizeEntity(tag);
        LinkRemapper.remap(tag, this.table);
        BlockPos plotCorner = this.target(entry, BlockPos.ZERO);
        Vec3 position = entity.position().add(plotCorner.getX(), plotCorner.getY(), plotCorner.getZ());
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(position.x));
        pos.add(DoubleTag.valueOf(position.y));
        pos.add(DoubleTag.valueOf(position.z));
        tag.put("Pos", pos);
        tag.remove("UUID");
        Entity copy = EntityType.loadEntityRecursive(tag, this.level, created -> {
            created.setUUID(UUID.randomUUID());
            return created;
        });
        if (copy != null) {
            copy.setPos(position);
        }
        return copy;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Drive-By-Wire
    // ------------------------------------------------------------------------------------------------------------------

    public record PendingWire(BlockPos source, BlockPos sink, Direction direction, String channel) {
    }

    /** Connections stored in the schematic between structures of this placement, mapped to their new positions. */
    public List<PendingWire> pendingWires() {
        List<PendingWire> wires = new ArrayList<>();
        if (!Mods.DRIVE_BY_WIRE.isLoaded() || !AeroConfig.RESTORE_WIRES.get()) {
            return wires;
        }
        for (SubLevelBlueprint.Entry entry : this.blueprint.entries()) {
            SubLevelCaptureMeta meta = this.meta(entry);
            ServerSubLevel sourceSubLevel = this.built(entry);
            if (meta == null || sourceSubLevel == null) {
                continue;
            }
            for (SubLevelCaptureMeta.WireRecord wire : meta.wires()) {
                SubLevelBlueprint.Entry sinkEntry = this.blueprint.find(wire.sinkSubLevel());
                ServerSubLevel sinkSubLevel = sinkEntry == null ? null : this.built(sinkEntry);
                if (sinkSubLevel == null || this.meta(sinkEntry) == null) {
                    continue;
                }
                BlockPos source = sourceSubLevel.getPlot().getCenterBlock().offset(wire.sourceRel());
                BlockPos sink = sinkSubLevel.getPlot().getCenterBlock().offset(wire.sinkRel());
                // Never let a (possibly hand-edited) schematic wire anything outside the structures it just created.
                if (Sable.HELPER.getContaining(this.level, source) != sourceSubLevel || Sable.HELPER.getContaining(this.level, sink) != sinkSubLevel) {
                    continue;
                }
                wires.add(new PendingWire(source, sink, wire.sinkDirection(), wire.channel()));
                if (wires.size() >= MAX_WIRES) {
                    return wires;
                }
            }
        }
        return wires;
    }

    public boolean wireExists(PendingWire wire) {
        return DriveByWireCompat.hasConnection(this.level, wire.source(), wire.sink(), wire.direction(), wire.channel());
    }

    public boolean connect(PendingWire wire) {
        return DriveByWireCompat.connect(this.level, wire.source(), wire.sink(), wire.direction(), wire.channel());
    }

    public void syncWires() {
        if (Mods.DRIVE_BY_WIRE.isLoaded()) {
            DriveByWireCompat.syncToPlayers(this.level);
        }
    }
}
