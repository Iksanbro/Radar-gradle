package dev.aeroschematics.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Extra information this mod stores next to every sub-level of a schematic (key {@value #KEY} inside each entry of
 * Sable's {@code sub_levels} list). Schematics written without this mod simply lack it and are still printable.
 *
 * @param schematicUuid id of the sub-level inside the schematic (the {@code uuid} Sable writes next to it)
 * @param sourceUuid    id of the real sub-level the schematic was taken from (references to it are re-targeted)
 * @param oldCenter     the plot center block of the source sub-level
 * @param origin        the template origin: min corner of the source plot bounds (template-local (0,0,0))
 * @param plotMinX      XZ square of the source plot, used to recognise references into it
 * @param wires         Drive-By-Wire connections whose source is in this sub-level and whose sink is in a captured one
 */
public record SubLevelCaptureMeta(
        int version,
        UUID schematicUuid,
        UUID sourceUuid,
        BlockPos oldCenter,
        BlockPos origin,
        int plotMinX,
        int plotMinZ,
        int plotMaxXExclusive,
        int plotMaxZExclusive,
        List<WireRecord> wires) {
    public static final String KEY = "aeroschematics";
    public static final int CURRENT_VERSION = 1;

    /**
     * One Drive-By-Wire connection. Both endpoints are stored relative to the plot center of the sub-level they belong
     * to, so they survive being printed into a different plot.
     *
     * @param sinkSubLevel schematic id of the sub-level the sink belongs to
     */
    public record WireRecord(BlockPos sourceRel, UUID sinkSubLevel, BlockPos sinkRel, Direction sinkDirection, String channel) {
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("Source", NbtUtils.writeBlockPos(this.sourceRel));
            tag.putUUID("SinkSubLevel", this.sinkSubLevel);
            tag.put("Sink", NbtUtils.writeBlockPos(this.sinkRel));
            tag.putInt("Direction", this.sinkDirection.get3DDataValue());
            tag.putString("Channel", this.channel);
            return tag;
        }

        @Nullable
        static WireRecord load(CompoundTag tag) {
            if (!tag.hasUUID("SinkSubLevel") || !tag.contains("Channel", Tag.TAG_STRING)) {
                return null;
            }
            BlockPos source = NbtUtils.readBlockPos(tag, "Source").orElse(null);
            BlockPos sink = NbtUtils.readBlockPos(tag, "Sink").orElse(null);
            if (source == null || sink == null) {
                return null;
            }
            return new WireRecord(source, tag.getUUID("SinkSubLevel"), sink, Direction.from3DDataValue(tag.getInt("Direction")), tag.getString("Channel"));
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", this.version);
        tag.putUUID("SchematicId", this.schematicUuid);
        tag.putUUID("SourceId", this.sourceUuid);
        tag.put("OldCenter", NbtUtils.writeBlockPos(this.oldCenter));
        tag.put("Origin", NbtUtils.writeBlockPos(this.origin));
        tag.putIntArray("PlotXZ", new int[]{this.plotMinX, this.plotMinZ, this.plotMaxXExclusive, this.plotMaxZExclusive});
        ListTag wireList = new ListTag();
        for (WireRecord wire : this.wires) {
            wireList.add(wire.save());
        }
        tag.put("Wires", wireList);
        return tag;
    }

    @Nullable
    public static SubLevelCaptureMeta load(CompoundTag tag) {
        if (!tag.hasUUID("SchematicId") || !tag.hasUUID("SourceId")) {
            return null;
        }
        BlockPos oldCenter = NbtUtils.readBlockPos(tag, "OldCenter").orElse(null);
        BlockPos origin = NbtUtils.readBlockPos(tag, "Origin").orElse(null);
        int[] plot = tag.getIntArray("PlotXZ");
        if (oldCenter == null || origin == null || plot.length != 4) {
            return null;
        }
        List<WireRecord> wires = new ArrayList<>();
        for (Tag entry : tag.getList("Wires", Tag.TAG_COMPOUND)) {
            WireRecord wire = WireRecord.load((CompoundTag) entry);
            if (wire != null) {
                wires.add(wire);
            }
        }
        return new SubLevelCaptureMeta(tag.getInt("Version"), tag.getUUID("SchematicId"), tag.getUUID("SourceId"), oldCenter, origin,
                plot[0], plot[1], plot[2], plot[3], List.copyOf(wires));
    }

    /** Where the template-local position {@code local} was in the source plot. */
    public BlockPos oldAbsolute(BlockPos local) {
        return this.origin.offset(local);
    }

    /** Where the template-local position {@code local} goes in a plot whose center is {@code newCenter}. */
    public BlockPos placeIn(BlockPos newCenter, BlockPos local) {
        return this.oldAbsolute(local).subtract(this.oldCenter).offset(newCenter);
    }
}
