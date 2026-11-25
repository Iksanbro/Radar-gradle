package dev.aeroschematics.place;

import com.simibubi.create.content.schematics.SchematicPrinter;
import dev.aeroschematics.capture.SubLevelCaptureMeta;
import dev.aeroschematics.mixinterface.AeroPrinterExt;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.create.schematics.SchematicLevelExtension;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.create.schematics.SchematicPrinterExtension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The physics structures contained in a loaded schematic, in printing order.
 * <p>
 * Sable already loads every {@code sub_levels} entry of a schematic into its own {@link SchematicLevel} (template-local
 * coordinates) and applies the schematic's rotation to its position/orientation. This class adds the metadata written by
 * this mod and computes a block order that keeps the structure connected at every step of a survival print: Sable splits
 * a physics structure into several as soon as its blocks are no longer connected, so blocks are emitted in breadth-first
 * order over the same 18-neighbourhood Sable uses, starting from a solid seed block (see {@link ConnectivityOrder}).
 */
public final class SubLevelBlueprint {
    public static final class Entry {
        public final int index;
        public final UUID schematicUuid;
        /** World position of the template origin relative to the schematic anchor (already rotated by Sable). */
        public final Vector3d corner;
        public final Quaterniond orientation;
        public final SchematicLevel level;
        @Nullable
        public final SubLevelCaptureMeta meta;
        /** Template-local positions of all non-air blocks in printing order; the first one is the seed. */
        public final List<BlockPos> order;

        Entry(int index, UUID schematicUuid, Vector3d corner, Quaterniond orientation, SchematicLevel level, @Nullable SubLevelCaptureMeta meta) {
            this.index = index;
            this.schematicUuid = schematicUuid;
            this.corner = new Vector3d(corner);
            this.orientation = new Quaterniond(orientation);
            this.level = level;
            this.meta = meta;
            this.order = computeOrder(level);
        }

        public BlockState stateAt(BlockPos local) {
            return this.level.getBlockState(local);
        }

        public BlockPos seed() {
            return this.order.get(0);
        }
    }

    private final List<Entry> entries;

    private SubLevelBlueprint(List<Entry> entries) {
        this.entries = entries;
    }

    public static SubLevelBlueprint empty() {
        return new SubLevelBlueprint(List.of());
    }

    /** Reads the physics structures of a loaded printer (empty if the schematic has none). */
    public static SubLevelBlueprint from(SchematicPrinter printer) {
        if (!printer.isLoaded() || printer.isErrored()) {
            return empty();
        }
        SchematicLevel reader = ((SchematicPrinterExtension) printer).sable$getSchematicLevel();
        if (reader == null) {
            return empty();
        }
        List<SchematicLevelExtension.SchematicSubLevel> subLevels = ((SchematicLevelExtension) reader).sable$getSubLevels();
        Map<UUID, SubLevelCaptureMeta> metas = ((AeroPrinterExt) printer).aeroschematics$getSubLevelMeta();
        List<Entry> entries = new ArrayList<>();
        for (SchematicLevelExtension.SchematicSubLevel subLevel : subLevels) {
            Entry entry = new Entry(entries.size(), subLevel.uuid(), subLevel.position(), subLevel.orientation(), subLevel.level(), metas.get(subLevel.uuid()));
            if (!entry.order.isEmpty()) {
                entries.add(entry);
            }
        }
        return new SubLevelBlueprint(Collections.unmodifiableList(entries));
    }

    public static boolean hasSubLevels(SchematicPrinter printer) {
        if (!printer.isLoaded() || printer.isErrored()) {
            return false;
        }
        SchematicLevel reader = ((SchematicPrinterExtension) printer).sable$getSchematicLevel();
        return reader != null && !((SchematicLevelExtension) reader).sable$getSubLevels().isEmpty();
    }

    public List<Entry> entries() {
        return this.entries;
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    @Nullable
    public Entry find(UUID schematicUuid) {
        for (Entry entry : this.entries) {
            if (entry.schematicUuid.equals(schematicUuid)) {
                return entry;
            }
        }
        return null;
    }

    public int totalBlocks() {
        int total = 0;
        for (Entry entry : this.entries) {
            total += entry.order.size();
        }
        return total;
    }

    /** Non-air positions of a sub-level template in connectivity-preserving order. */
    static List<BlockPos> computeOrder(SchematicLevel level) {
        List<BlockPos> all = new ArrayList<>();
        for (BlockPos pos : level.getAllPositions()) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.STRUCTURE_VOID)) {
                all.add(pos.immutable());
            }
        }
        // Deterministic base order: the one Create's printer uses (Y, then Z, then X).
        all.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
        return ConnectivityOrder.order(all, pos -> SchematicPrinterUtil.isDeferred(level.getBlockState(pos)), pos -> SchematicPrinterUtil.isSolidSeed(level, pos));
    }

}
