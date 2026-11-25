package dev.aeroschematics.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class LinkRemapperTest {
    // A Sable plot square (2048 x 2048 blocks) far away from the playable area, and where the copy goes.
    private static final int PLOT_MIN_X = 20_480_000;
    private static final int PLOT_MIN_Z = 20_480_000;
    private static final BlockPos OLD_CENTER = new BlockPos(PLOT_MIN_X + 1032, 128, PLOT_MIN_Z + 1032);
    private static final BlockPos NEW_CENTER = new BlockPos(PLOT_MIN_X + 2048 + 1032, 128, PLOT_MIN_Z + 1032);
    private static final UUID OLD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SCHEMATIC_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final UUID NEW_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static LinkRemapper.Table table() {
        BlockPos shift = NEW_CENTER.subtract(OLD_CENTER);
        return new LinkRemapper.Table(-64, 320)
                .shift(new LinkRemapper.PlotShift(PLOT_MIN_X, PLOT_MIN_Z, PLOT_MIN_X + 2048, PLOT_MIN_Z + 2048, shift.getX(), shift.getY(), shift.getZ()))
                .uuid(OLD_ID, NEW_ID)
                .uuid(SCHEMATIC_ID, NEW_ID);
    }

    private static BlockPos moved(BlockPos old) {
        return old.subtract(OLD_CENTER).offset(NEW_CENTER);
    }

    @Test
    void longPositionsInsideThePlotAreMoved() {
        BlockPos linked = OLD_CENTER.offset(3, -2, 7);
        CompoundTag tag = new CompoundTag();
        tag.putLong("DriveByWireHub", linked.asLong());        // Drive-By-Wire lectern hub
        tag.putLong("PartnerPosition", linked.east().asLong()); // Simulated merging glue
        tag.putLong("Timestamp", 1_764_000_000_000L);            // not a position
        tag.putLong("World", new BlockPos(100, 64, -200).asLong()); // a position outside the plot

        LinkRemapper.Result result = LinkRemapper.remap(tag, table());

        assertEquals(moved(linked), BlockPos.of(tag.getLong("DriveByWireHub")));
        assertEquals(moved(linked.east()), BlockPos.of(tag.getLong("PartnerPosition")));
        assertEquals(1_764_000_000_000L, tag.getLong("Timestamp"));
        assertEquals(new BlockPos(100, 64, -200), BlockPos.of(tag.getLong("World")));
        assertEquals(2, result.positions);
    }

    @Test
    void intArrayPositionsAndNestedListsAreMoved() {
        BlockPos plate = OLD_CENTER.offset(0, 5, 0);
        CompoundTag tag = new CompoundTag();
        tag.put("SwivelPlate", NbtUtils.writeBlockPos(plate)); // Simulated swivel bearing
        ListTag linked = new ListTag();
        CompoundTag sensor = new CompoundTag();
        sensor.put("pos", NbtUtils.writeBlockPos(plate.north()));
        linked.add(sensor);
        tag.put("linkedSensors", linked); // Flight Control distance coupler

        LinkRemapper.remap(tag, table());

        assertEquals(moved(plate), NbtUtils.readBlockPos(tag, "SwivelPlate").orElseThrow());
        assertEquals(moved(plate.north()), NbtUtils.readBlockPos(tag.getList("linkedSensors", Tag.TAG_COMPOUND).getCompound(0), "pos").orElseThrow());
    }

    @Test
    void siblingCoordinateKeysAreMovedTogether() {
        BlockPos target = OLD_CENTER.offset(-4, 1, 2);
        CompoundTag tag = new CompoundTag();
        tag.putInt("linkX", target.getX());
        tag.putInt("linkY", target.getY());
        tag.putInt("linkZ", target.getZ());
        tag.putDouble("x", target.getX() + 0.5);
        tag.putDouble("y", target.getY() + 0.5);
        tag.putDouble("z", target.getZ() + 0.5);
        tag.putInt("offsetX", 3); // relative offsets are not touched
        tag.putInt("offsetY", 0);
        tag.putInt("offsetZ", 1);

        LinkRemapper.remap(tag, table());

        BlockPos expected = moved(target);
        assertEquals(expected.getX(), tag.getInt("linkX"));
        assertEquals(expected.getY(), tag.getInt("linkY"));
        assertEquals(expected.getZ(), tag.getInt("linkZ"));
        assertEquals(expected.getX() + 0.5, tag.getDouble("x"), 1e-9);
        assertEquals(3, tag.getInt("offsetX"));
    }

    @Test
    void ropeStrandsAreMovedIncludingTheirSubLevelIds() {
        // Simulated ServerRopeStrand codec: points as [x, y, z] doubles, attachments with string subLevelID.
        CompoundTag strand = new CompoundTag();
        ListTag points = new ListTag();
        ListTag point = new ListTag();
        point.add(DoubleTag.valueOf(OLD_CENTER.getX() + 0.25));
        point.add(DoubleTag.valueOf(OLD_CENTER.getY() - 3.0));
        point.add(DoubleTag.valueOf(OLD_CENTER.getZ() + 0.75));
        points.add(point);
        strand.put("points", points);
        ListTag attachments = new ListTag();
        CompoundTag attachment = new CompoundTag();
        attachment.putString("subLevelID", OLD_ID.toString());
        attachment.put("blockAttachment", NbtUtils.writeBlockPos(OLD_CENTER));
        attachments.add(attachment);
        strand.put("attachments", attachments);

        LinkRemapper.remap(strand, table());

        ListTag movedPoint = strand.getList("points", Tag.TAG_LIST).getList(0);
        assertEquals(NEW_CENTER.getX() + 0.25, movedPoint.getDouble(0), 1e-9);
        assertEquals(NEW_CENTER.getZ() + 0.75, movedPoint.getDouble(2), 1e-9);
        CompoundTag movedAttachment = strand.getList("attachments", Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(NEW_ID.toString(), movedAttachment.getString("subLevelID"));
        assertEquals(NEW_CENTER, NbtUtils.readBlockPos(movedAttachment, "blockAttachment").orElseThrow());
    }

    @Test
    void subLevelIdsAreReTargetedInEveryEncoding() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SubLevelID", SCHEMATIC_ID);   // Simulated swivel bearing written under Sable's SAVE context
        tag.putUUID("computerBody", OLD_ID);       // Flight Control weld (context unaware)
        tag.putLong("ownerMost", OLD_ID.getMostSignificantBits());
        tag.putLong("ownerLeast", OLD_ID.getLeastSignificantBits());
        UUID unrelated = UUID.randomUUID();
        tag.putUUID("Owner", unrelated);

        LinkRemapper.Result result = LinkRemapper.remap(tag, table());

        assertEquals(NEW_ID, tag.getUUID("SubLevelID"));
        assertEquals(NEW_ID, tag.getUUID("computerBody"));
        assertEquals(NEW_ID, new UUID(tag.getLong("ownerMost"), tag.getLong("ownerLeast")));
        assertEquals(unrelated, tag.getUUID("Owner"));
        assertEquals(3, result.uuids);
    }

    @Test
    void positionsRelativeToThePlotCenterAreLeftAlone() {
        // Flight Control stores welded engines as localPos = pos - plot center: printing keeps that offset.
        CompoundTag tag = new CompoundTag();
        long local = new BlockPos(4, -1, 9).asLong();
        tag.putLong("localPos", local);
        tag.putIntArray("rel", new int[]{1, 2, 3});
        LinkRemapper.remap(tag, table());
        assertEquals(local, tag.getLong("localPos"));
        assertArrayEquals(new int[]{1, 2, 3}, tag.getIntArray("rel"));
    }
}
