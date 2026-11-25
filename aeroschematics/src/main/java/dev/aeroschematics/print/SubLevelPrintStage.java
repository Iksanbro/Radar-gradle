package dev.aeroschematics.print;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.simibubi.create.content.schematics.cannon.MaterialChecklist;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import dev.aeroschematics.AeroSchematics;
import dev.aeroschematics.compat.DriveByWireCompat;
import dev.aeroschematics.compat.Mods;
import dev.aeroschematics.compat.PhysicsLock;
import dev.aeroschematics.config.AeroConfig;
import dev.aeroschematics.mixin.SchematicannonAccessor;
import dev.aeroschematics.place.PlacementSession;
import dev.aeroschematics.place.SchematicPrinterUtil;
import dev.aeroschematics.place.SubLevelBlueprint;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Extra printing stage of a schematicannon: once Create has printed the ordinary blocks of a schematic, the physics
 * structures saved in it are printed one block at a time, consuming items and gunpowder exactly like ordinary blocks.
 * <ol>
 *     <li><b>Seeding</b> - for every structure one solid block is placed and a new physics structure is created around
 *     it, posed like the schematic preview and locked in place (Simulated's persistent physics lock). Allocating all of
 *     them first means links between structures (bearings, welds, wires) can be re-targeted from the start.</li>
 *     <li><b>Blocks</b> - all other blocks, in an order that keeps every structure connected (Sable would otherwise
 *     split it into pieces). All blocks of a structure fly for the same time so they also land in that order.</li>
 *     <li><b>Entities</b>, then <b>wires</b> (Drive-By-Wire, optionally charged one wire item each).</li>
 *     <li><b>Finishing</b> - the locks are released (configurable) and the schematicannon finishes as usual.</li>
 * </ol>
 * The stage survives restarts: its progress is stored in the schematicannon and the structures are re-attached by id.
 */
public final class SubLevelPrintStage {
    public enum Phase {
        IDLE,
        SEEDING,
        BLOCKS,
        ENTITIES,
        WIRES,
        FINISHING
    }

    private static final int MISSING_STRUCTURE_GRACE_TICKS = 100;

    private Phase phase = Phase.IDLE;
    private int entryIndex;
    private int cursor;
    private int entityIndex;
    private int wireIndex;
    private int missingTicks;
    private final Map<UUID, UUID> builtIds = new LinkedHashMap<>();

    private SubLevelBlueprint blueprint = SubLevelBlueprint.empty();
    @Nullable
    private PlacementSession session;
    @Nullable
    private List<PlacementSession.PendingWire> wires;
    private final Map<UUID, ItemRequirement[]> requirementCache = new HashMap<>();

    public Phase phase() {
        return this.phase;
    }

    public boolean isActive() {
        return this.phase != Phase.IDLE;
    }

    public boolean hasPendingWork() {
        return !this.blueprint.isEmpty();
    }

    public SubLevelBlueprint blueprint() {
        return this.blueprint;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Lifecycle hooks (called from the schematicannon mixin)
    // ------------------------------------------------------------------------------------------------------------------

    /** The schematicannon (re)loaded its schematic. */
    public void onPrinterLoaded(SchematicannonBlockEntity cannon) {
        this.session = null;
        this.wires = null;
        this.requirementCache.clear();
        this.blueprint = SubLevelBlueprint.from(cannon.printer);
        if (this.blueprint.isEmpty()) {
            this.clearProgress();
            return;
        }
        ItemStack schematic = cannon.inventory.getStackInSlot(0);
        Mirror mirror = schematic.get(AllDataComponents.SCHEMATIC_MIRROR);
        if (mirror != null && mirror != Mirror.NONE) {
            // Sable cannot mirror physics structures (same restriction as its creative placement).
            cannon.state = SchematicannonBlockEntity.State.STOPPED;
            cannon.statusMsg = "aeroschematicsMirror";
            cannon.printer.resetSchematic();
            this.blueprint = SubLevelBlueprint.empty();
            this.clearProgress();
            cannon.sendUpdate = true;
            return;
        }
        // Progress loaded from NBT only applies to the same schematic.
        for (UUID schematicId : List.copyOf(this.builtIds.keySet())) {
            if (this.blueprint.find(schematicId) == null) {
                this.clearProgress();
                break;
            }
        }
        cannon.updateChecklist();
    }

    /** Create finished the ordinary blocks; take over if there are physics structures to print. */
    public void begin(SchematicannonBlockEntity cannon) {
        if (this.phase == Phase.IDLE) {
            this.phase = Phase.SEEDING;
            this.entryIndex = 0;
            this.cursor = 0;
            this.entityIndex = 0;
            this.wireIndex = 0;
        }
        cannon.statusMsg = "aeroschematicsPrinting";
        cannon.sendUpdate = true;
    }

    /** The schematicannon was reset (stopped, schematic removed, or finished). */
    public void reset(SchematicannonBlockEntity cannon) {
        if (!this.builtIds.isEmpty() && cannon.getLevel() instanceof ServerLevel level) {
            // Unfinished structures are released: nobody can unlock them in survival otherwise.
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            for (UUID id : this.builtIds.values()) {
                SubLevel subLevel = container == null ? null : container.getSubLevel(id);
                if (subLevel != null) {
                    PhysicsLock.unlock(level, subLevel);
                }
            }
        }
        this.clearProgress();
        this.blueprint = SubLevelBlueprint.empty();
        this.session = null;
        this.wires = null;
        this.requirementCache.clear();
    }

    private void clearProgress() {
        this.phase = Phase.IDLE;
        this.entryIndex = 0;
        this.cursor = 0;
        this.entityIndex = 0;
        this.wireIndex = 0;
        this.missingTicks = 0;
        this.builtIds.clear();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Replaces Create's {@code tickPrinter} while this stage is active. Mirrors its structure: cooldown, gunpowder,
     * creative crate, item checks, one shot per call.
     */
    public void tick(SchematicannonBlockEntity cannon) {
        SchematicannonAccessor access = (SchematicannonAccessor) cannon;
        access.aeroschematics$setBlockSkipped(false);
        if (!(cannon.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (access.aeroschematics$getPrinterCooldown() > 0) {
            access.aeroschematics$setPrinterCooldown(access.aeroschematics$getPrinterCooldown() - 1);
            return;
        }
        if (cannon.remainingFuel <= 0 && !cannon.hasCreativeCrate) {
            access.aeroschematics$refillFuelIfPossible();
            if (cannon.remainingFuel <= 0) {
                cannon.state = SchematicannonBlockEntity.State.PAUSED;
                cannon.statusMsg = "noGunpowder";
                cannon.sendUpdate = true;
                return;
            }
        }
        if (cannon.hasCreativeCrate) {
            cannon.remainingFuel = 0;
            if (cannon.missingItem != null) {
                cannon.missingItem = null;
                cannon.state = SchematicannonBlockEntity.State.RUNNING;
            }
        }

        PlacementSession session = this.ensureSession(cannon, level);
        if (session == null) {
            return;
        }
        if (cannon.positionNotLoaded) {
            cannon.positionNotLoaded = false;
            cannon.state = SchematicannonBlockEntity.State.RUNNING;
        }

        try {
            switch (this.phase) {
                case SEEDING -> this.tickSeeding(cannon, session, level);
                case BLOCKS -> this.tickBlocks(cannon, session, level);
                case ENTITIES -> this.tickEntities(cannon, session, level);
                case WIRES -> this.tickWires(cannon, session);
                case FINISHING -> this.tickFinishing(cannon, session, level);
                case IDLE -> {
                }
            }
        } catch (RuntimeException e) {
            AeroSchematics.LOGGER.error("Schematicannon at {} failed while printing a physics structure", cannon.getBlockPos(), e);
            cannon.state = SchematicannonBlockEntity.State.PAUSED;
            cannon.statusMsg = "schematicErrored";
            cannon.sendUpdate = true;
        }
    }

    @Nullable
    private PlacementSession ensureSession(SchematicannonBlockEntity cannon, ServerLevel level) {
        if (this.session != null) {
            return this.session;
        }
        BlockPos anchor = cannon.printer.getAnchor();
        if (anchor == null) {
            return null;
        }
        PlacementSession created = new PlacementSession(level, anchor, this.blueprint);
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        boolean waiting = false;
        for (Map.Entry<UUID, UUID> built : List.copyOf(this.builtIds.entrySet())) {
            SubLevelBlueprint.Entry entry = this.blueprint.find(built.getKey());
            if (entry == null) {
                this.builtIds.remove(built.getKey());
                continue;
            }
            SubLevel subLevel = container.getSubLevel(built.getValue());
            if (subLevel instanceof ServerSubLevel serverSubLevel && !subLevel.isRemoved()) {
                created.adopt(entry, serverSubLevel);
                PhysicsLock.ensureLocked(level, serverSubLevel);
                continue;
            }
            Vector3d corner = created.worldCorner(entry);
            if (!level.isLoaded(BlockPos.containing(corner.x, corner.y, corner.z)) || this.missingTicks++ < MISSING_STRUCTURE_GRACE_TICKS) {
                // Not loaded yet (Sable loads structures shortly after their chunks): wait.
                waiting = true;
                continue;
            }
            // The structure is gone (destroyed by players): print it again from scratch.
            AeroSchematics.LOGGER.info("Physics structure {} printed by the schematicannon at {} disappeared, printing it again", built.getValue(), cannon.getBlockPos());
            this.builtIds.remove(built.getKey());
            this.phase = Phase.SEEDING;
            this.entryIndex = 0;
            this.cursor = 0;
        }
        if (waiting) {
            cannon.positionNotLoaded = true;
            cannon.state = SchematicannonBlockEntity.State.PAUSED;
            cannon.statusMsg = "targetNotLoaded";
            cannon.sendUpdate = true;
            return null;
        }
        this.missingTicks = 0;
        this.session = created;
        return created;
    }

    private void tickSeeding(SchematicannonBlockEntity cannon, PlacementSession session, ServerLevel level) {
        List<SubLevelBlueprint.Entry> entries = this.blueprint.entries();
        if (this.entryIndex >= entries.size()) {
            this.phase = Phase.BLOCKS;
            this.entryIndex = 0;
            this.cursor = 0;
            this.skip(cannon);
            return;
        }
        SubLevelBlueprint.Entry entry = entries.get(this.entryIndex);
        if (session.built(entry) != null) {
            this.entryIndex++;
            this.skip(cannon);
            return;
        }
        Vector3d corner = session.worldCorner(entry);
        if (!level.isLoaded(BlockPos.containing(corner.x, corner.y, corner.z))) {
            cannon.positionNotLoaded = true;
            cannon.state = SchematicannonBlockEntity.State.PAUSED;
            cannon.statusMsg = "targetNotLoaded";
            cannon.sendUpdate = true;
            return;
        }
        BlockPos seed = entry.seed();
        ItemRequirement requirement = this.requirement(session, entry, 0);
        if (requirement.isInvalid()) {
            AeroSchematics.LOGGER.warn("Cannot print physics structure {}: its first block {} cannot be obtained in survival", entry.schematicUuid, entry.stateAt(seed));
            this.entryIndex++;
            this.skip(cannon);
            return;
        }
        if (!this.consume(cannon, requirement)) {
            return;
        }
        ServerSubLevel subLevel = session.allocate(entry);
        session.placeNow(entry, seed, icon(requirement));
        session.settlePose(entry);
        PhysicsLock.ensureLocked(level, subLevel);
        this.builtIds.put(entry.schematicUuid, subLevel.getUniqueId());
        this.fired(cannon, true);
        this.entryIndex++;
    }

    private void tickBlocks(SchematicannonBlockEntity cannon, PlacementSession session, ServerLevel level) {
        List<SubLevelBlueprint.Entry> entries = this.blueprint.entries();
        if (this.entryIndex >= entries.size()) {
            this.phase = Phase.ENTITIES;
            this.entryIndex = 0;
            this.entityIndex = 0;
            this.skip(cannon);
            return;
        }
        SubLevelBlueprint.Entry entry = entries.get(this.entryIndex);
        if (session.built(entry) == null && this.builtIds.containsKey(entry.schematicUuid)) {
            // The structure disappeared while printing: let ensureSession() find out what happened.
            this.session = null;
            return;
        }
        if (session.built(entry) == null || this.cursor >= entry.order.size()) {
            // Not printable (its seed could not be printed) or done: next structure.
            this.entryIndex++;
            this.cursor = 0;
            this.skip(cannon);
            return;
        }
        BlockPos local = entry.order.get(this.cursor);
        BlockState state = entry.stateAt(local);
        BlockPos target = session.target(entry, local);
        if (this.cursor == 0 || state.isAir() || isImplicitHalf(state) || !session.isInsidePlot(entry, target) || !level.getBlockState(target).isAir()) {
            this.cursor++;
            this.skip(cannon);
            return;
        }
        if (!level.isLoaded(target)) {
            cannon.positionNotLoaded = true;
            cannon.state = SchematicannonBlockEntity.State.PAUSED;
            cannon.statusMsg = "targetNotLoaded";
            cannon.sendUpdate = true;
            return;
        }
        ItemRequirement requirement = this.requirement(session, entry, this.cursor);
        if (requirement.isInvalid()) {
            this.cursor++;
            this.skip(cannon);
            return;
        }
        if (!this.consume(cannon, requirement)) {
            return;
        }
        CompoundTag data = session.prepareBlockData(entry, local, state);
        LaunchedItem.ForBlockState launched = new LaunchedItem.ForBlockState(cannon.getBlockPos(), target, icon(requirement), state, data);
        // Same flight time for the whole structure, so blocks land in the order they were shot (connectivity).
        int ticks = this.flightTicks(cannon, session, entry);
        launched.totalTicks = ticks;
        launched.ticksRemaining = ticks;
        cannon.flyingBlocks.add(launched);
        this.fired(cannon, true);
        this.cursor++;
    }

    private void tickEntities(SchematicannonBlockEntity cannon, PlacementSession session, ServerLevel level) {
        if (!cannon.flyingBlocks.isEmpty()) {
            this.waitForLanding(cannon);
            return;
        }
        List<EntityRef> entities = this.entities();
        if (this.entityIndex >= entities.size()) {
            this.phase = Phase.WIRES;
            this.wireIndex = 0;
            this.skip(cannon);
            return;
        }
        EntityRef ref = entities.get(this.entityIndex);
        if (session.built(ref.entry()) == null) {
            this.entityIndex++;
            this.skip(cannon);
            return;
        }
        Entity copy = session.prepareEntity(ref.entry(), ref.entity());
        ItemRequirement requirement = copy == null ? ItemRequirement.INVALID : ItemRequirement.of(copy);
        if (requirement.isInvalid()) {
            // Same rule as Create: entities without an item form (e.g. assembled contraptions) are not printed.
            this.entityIndex++;
            this.skip(cannon);
            return;
        }
        if (!this.consume(cannon, requirement)) {
            return;
        }
        level.addFreshEntity(copy);
        this.fired(cannon, true);
        this.entityIndex++;
    }

    private void tickWires(SchematicannonBlockEntity cannon, PlacementSession session) {
        if (!cannon.flyingBlocks.isEmpty()) {
            this.waitForLanding(cannon);
            return;
        }
        if (this.wires == null) {
            this.wires = session.pendingWires();
        }
        if (this.wireIndex >= this.wires.size()) {
            if (!this.wires.isEmpty()) {
                session.syncWires();
            }
            this.phase = Phase.FINISHING;
            this.skip(cannon);
            return;
        }
        PlacementSession.PendingWire wire = this.wires.get(this.wireIndex);
        if (session.wireExists(wire)) {
            this.wireIndex++;
            this.skip(cannon);
            return;
        }
        if (wiresCostItems(cannon) && !this.consume(cannon, new ItemRequirement(ItemRequirement.ItemUseType.CONSUME, DriveByWireCompat.wireStack()))) {
            return;
        }
        session.connect(wire);
        cannon.statusMsg = "aeroschematicsWiring";
        cannon.sendUpdate = true;
        this.wireIndex++;
        this.skip(cannon);
    }

    private void tickFinishing(SchematicannonBlockEntity cannon, PlacementSession session, ServerLevel level) {
        if (!cannon.flyingBlocks.isEmpty()) {
            this.waitForLanding(cannon);
            return;
        }
        boolean unlock = AeroConfig.UNLOCK_AFTER_PRINTING.get();
        for (SubLevelBlueprint.Entry entry : this.blueprint.entries()) {
            ServerSubLevel subLevel = session.built(entry);
            if (subLevel != null && unlock) {
                PhysicsLock.unlock(level, subLevel);
            }
        }
        // Forget the structures before Create resets the printer: they are finished and must not be unlocked/reset.
        this.clearProgress();
        this.blueprint = SubLevelBlueprint.empty();
        this.session = null;
        this.wires = null;
        cannon.finishedPrinting();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------------

    private record EntityRef(SubLevelBlueprint.Entry entry, Entity entity) {
    }

    private List<EntityRef> entities() {
        List<EntityRef> list = new ArrayList<>();
        for (SubLevelBlueprint.Entry entry : this.blueprint.entries()) {
            for (Entity entity : entry.level.getEntityList()) {
                list.add(new EntityRef(entry, entity));
            }
        }
        return list;
    }

    private static boolean isImplicitHalf(BlockState state) {
        return SchematicPrinterUtil.isImplicitHalf(state);
    }

    private static boolean wiresCostItems(SchematicannonBlockEntity cannon) {
        if (cannon.hasCreativeCrate || !Mods.DRIVE_BY_WIRE.isLoaded()) {
            return false;
        }
        return switch (AeroConfig.WIRE_CHARGING.get()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> DriveByWireCompat.wiresCostItems();
        };
    }

    private ItemRequirement requirement(PlacementSession session, SubLevelBlueprint.Entry entry, int index) {
        ItemRequirement[] cache = this.requirementCache.computeIfAbsent(entry.schematicUuid, k -> new ItemRequirement[entry.order.size()]);
        ItemRequirement cached = cache[index];
        if (cached == null) {
            BlockPos local = entry.order.get(index);
            BlockState state = entry.stateAt(local);
            CompoundTag data = session.prepareBlockData(entry, local, state);
            cached = session.requirement(entry, local, state, data);
            cache[index] = cached;
        }
        return cached;
    }

    private static ItemStack icon(ItemRequirement requirement) {
        List<ItemRequirement.StackRequirement> items = requirement.getRequiredItems();
        return requirement.isEmpty() || items.isEmpty() ? ItemStack.EMPTY : items.get(0).stack;
    }

    /** Takes the required items from attached inventories, or pauses the cannon on the missing one. */
    private boolean consume(SchematicannonBlockEntity cannon, ItemRequirement requirement) {
        SchematicannonAccessor access = (SchematicannonAccessor) cannon;
        if (!requirement.isEmpty()) {
            List<ItemRequirement.StackRequirement> required = requirement.getRequiredItems();
            for (ItemRequirement.StackRequirement stack : required) {
                if (!access.aeroschematics$grabItems(stack, true)) {
                    // Physics structures never skip missing blocks: a hole could split the structure into pieces.
                    cannon.missingItem = stack.stack;
                    cannon.state = SchematicannonBlockEntity.State.PAUSED;
                    cannon.statusMsg = "missingBlock";
                    cannon.sendUpdate = true;
                    return false;
                }
            }
            for (ItemRequirement.StackRequirement stack : required) {
                access.aeroschematics$grabItems(stack, false);
            }
        }
        cannon.missingItem = null;
        cannon.state = SchematicannonBlockEntity.State.RUNNING;
        return true;
    }

    private void fired(SchematicannonBlockEntity cannon, boolean countsAsBlock) {
        SchematicannonAccessor access = (SchematicannonAccessor) cannon;
        if (countsAsBlock) {
            cannon.blocksPlaced++;
        }
        cannon.playFiringSound();
        cannon.statusMsg = "aeroschematicsPrinting";
        access.aeroschematics$setPrinterCooldown(cannon.config().schematicannonDelay.get());
        cannon.remainingFuel--;
        cannon.sendUpdate = true;
    }

    private void skip(SchematicannonBlockEntity cannon) {
        ((SchematicannonAccessor) cannon).aeroschematics$setBlockSkipped(true);
    }

    private void waitForLanding(SchematicannonBlockEntity cannon) {
        if (!"aeroschematicsWaiting".equals(cannon.statusMsg)) {
            cannon.statusMsg = "aeroschematicsWaiting";
            cannon.sendUpdate = true;
        }
    }

    private int flightTicks(SchematicannonBlockEntity cannon, PlacementSession session, SubLevelBlueprint.Entry entry) {
        Vector3d corner = session.worldCorner(entry);
        BlockPos pos = cannon.getBlockPos();
        double distanceSq = corner.distanceSquared(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return (int) Math.max(10, Math.sqrt(Math.sqrt(distanceSq)) * 4);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Checklist
    // ------------------------------------------------------------------------------------------------------------------

    /** Adds what is still needed for the physics structures to the schematicannon's material list. */
    public int addRequirements(SchematicannonBlockEntity cannon, MaterialChecklist checklist) {
        if (this.blueprint.isEmpty() || !(cannon.getLevel() instanceof ServerLevel level)) {
            return 0;
        }
        PlacementSession requirementSession = this.session != null ? this.session : new PlacementSession(level, cannon.printer.getAnchor(), this.blueprint);
        int remaining = 0;
        List<SubLevelBlueprint.Entry> entries = this.blueprint.entries();
        for (SubLevelBlueprint.Entry entry : entries) {
            boolean seeded = this.builtIds.containsKey(entry.schematicUuid);
            int start;
            if (this.phase.ordinal() > Phase.BLOCKS.ordinal() || (this.phase == Phase.BLOCKS && entry.index < this.entryIndex)) {
                continue;
            } else if (this.phase == Phase.BLOCKS && entry.index == this.entryIndex) {
                start = Math.max(1, this.cursor);
            } else {
                start = seeded ? 1 : 0;
            }
            for (int i = start; i < entry.order.size(); i++) {
                BlockState state = entry.stateAt(entry.order.get(i));
                if (state.isAir() || isImplicitHalf(state)) {
                    continue;
                }
                ItemRequirement requirement = this.requirement(requirementSession, entry, i);
                if (!requirement.isEmpty() && !requirement.isInvalid()) {
                    checklist.require(requirement);
                    remaining++;
                }
            }
        }
        if (this.phase.ordinal() < Phase.WIRES.ordinal() && wiresCostItems(cannon)) {
            int wireCount = 0;
            for (SubLevelBlueprint.Entry entry : entries) {
                if (entry.meta != null) {
                    wireCount += entry.meta.wires().size();
                }
            }
            for (int i = 0; i < wireCount; i++) {
                checklist.require(new ItemRequirement(ItemRequirement.ItemUseType.CONSUME, DriveByWireCompat.wireStack()));
            }
        }
        return remaining;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Phase", this.phase.name());
        tag.putInt("Entry", this.entryIndex);
        tag.putInt("Cursor", this.cursor);
        tag.putInt("Entity", this.entityIndex);
        tag.putInt("Wire", this.wireIndex);
        ListTag built = new ListTag();
        this.builtIds.forEach((schematic, real) -> {
            CompoundTag pair = new CompoundTag();
            pair.putUUID("Schematic", schematic);
            pair.putUUID("SubLevel", real);
            built.add(pair);
        });
        tag.put("Built", built);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.clearProgress();
        try {
            this.phase = Phase.valueOf(tag.getString("Phase"));
        } catch (IllegalArgumentException e) {
            this.phase = Phase.IDLE;
        }
        this.entryIndex = tag.getInt("Entry");
        this.cursor = tag.getInt("Cursor");
        this.entityIndex = tag.getInt("Entity");
        this.wireIndex = tag.getInt("Wire");
        for (Tag entry : tag.getList("Built", Tag.TAG_COMPOUND)) {
            CompoundTag pair = (CompoundTag) entry;
            if (pair.hasUUID("Schematic") && pair.hasUUID("SubLevel")) {
                this.builtIds.put(pair.getUUID("Schematic"), pair.getUUID("SubLevel"));
            }
        }
        this.session = null;
        this.wires = null;
    }
}
