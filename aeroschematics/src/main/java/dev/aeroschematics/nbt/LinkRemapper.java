package dev.aeroschematics.nbt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Re-targets links stored inside NBT ("this block is linked to that block", "this bearing drives that sub-level") from
 * the original physics structure(s) to the freshly printed one(s).
 * <p>
 * Sable stores every sub-level's blocks in a private "plot": a huge, unique region of the level (far away from the
 * playable area). Any block position that lies inside the XZ square of a captured plot therefore unambiguously refers to
 * a block of that captured structure, whatever mod wrote it and however it was encoded. Because a printed structure keeps
 * every block at the same offset from its plot's center, a reference is fixed by a pure translation
 * ({@code newCenter - oldCenter}). References relative to the plot center (used e.g. by Flight Control welds) stay valid
 * as they are.
 * <p>
 * Recognised encodings:
 * <ul>
 *     <li>{@code BlockPos.asLong()} long tags,</li>
 *     <li>{@code [I; x, y, z]} int arrays ({@code NbtUtils.writeBlockPos}, {@code BlockPos.CODEC}),</li>
 *     <li>lists of three numbers ({@code Vec3}/{@code Vector3d} codecs, rope points...),</li>
 *     <li>sibling keys {@code <prefix>X/<prefix>Y/<prefix>Z} (any case, int/long/float/double),</li>
 *     <li>UUIDs of the captured sub-levels as {@code [I; a, b, c, d]}, strings, or {@code <key>Most/<key>Least} longs.</li>
 * </ul>
 */
public final class LinkRemapper {
    /** A captured plot region and how to move references out of it. */
    public record PlotShift(int minX, int minZ, int maxXExclusive, int maxZExclusive, int dx, int dy, int dz) {
        public boolean contains(double x, double z) {
            return x >= this.minX && x < this.maxXExclusive && z >= this.minZ && z < this.maxZExclusive;
        }
    }

    /** Everything needed to re-target references of one placement. */
    public static final class Table {
        private final List<PlotShift> shifts = new ArrayList<>();
        private final Map<UUID, UUID> uuids = new HashMap<>();
        private final int minY;
        private final int maxY;

        /**
         * @param minY lowest valid block Y of the level (positions decoded from longs outside the build height are
         *             ignored, which makes accidental matches of arbitrary longs practically impossible)
         * @param maxY highest valid block Y (exclusive)
         */
        public Table(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
        }

        public Table shift(PlotShift shift) {
            this.shifts.add(shift);
            return this;
        }

        public Table uuid(UUID from, UUID to) {
            if (from != null && to != null && !from.equals(to)) {
                this.uuids.put(from, to);
            }
            return this;
        }

        public boolean isEmpty() {
            return this.shifts.isEmpty() && this.uuids.isEmpty();
        }

        PlotShift find(double x, double z) {
            for (PlotShift shift : this.shifts) {
                if (shift.contains(x, z)) {
                    return shift;
                }
            }
            return null;
        }

        boolean validY(double y) {
            return y >= this.minY - 1 && y <= this.maxY + 1;
        }

        public UUID mapUuid(UUID uuid) {
            return this.uuids.get(uuid);
        }

        public BlockPos mapPos(BlockPos pos) {
            PlotShift shift = this.find(pos.getX(), pos.getZ());
            return shift == null || !this.validY(pos.getY()) ? pos : pos.offset(shift.dx(), shift.dy(), shift.dz());
        }

        /** True if the position lies inside one of the captured plots (i.e. would be translated). */
        public boolean isCaptured(BlockPos pos) {
            return this.find(pos.getX(), pos.getZ()) != null && this.validY(pos.getY());
        }
    }

    /** Statistics of one remap pass. */
    public static final class Result {
        public int positions;
        public int uuids;

        public int total() {
            return this.positions + this.uuids;
        }
    }

    private LinkRemapper() {
    }

    public static Result remap(CompoundTag root, Table table) {
        Result result = new Result();
        if (!table.isEmpty()) {
            remapCompound(root, table, result, 0);
        }
        return result;
    }

    private static void remapCompound(CompoundTag tag, Table table, Result result, int depth) {
        if (depth > 64) {
            return;
        }
        remapTriplets(tag, table, result);
        remapMostLeastUuids(tag, table, result);
        for (String key : List.copyOf(tag.getAllKeys())) {
            Tag value = tag.get(key);
            Tag replacement = remapValue(value, table, result, depth);
            if (replacement != null) {
                tag.put(key, replacement);
            }
        }
    }

    /** Returns a replacement for {@code value} or {@code null} when it was left as is (or edited in place). */
    private static Tag remapValue(Tag value, Table table, Result result, int depth) {
        if (value instanceof CompoundTag compound) {
            remapCompound(compound, table, result, depth + 1);
            return null;
        }
        if (value instanceof ListTag list) {
            Tag vector = remapNumericVector(list, table, result);
            if (vector != null) {
                return vector;
            }
            for (int i = 0; i < list.size(); i++) {
                Tag element = list.get(i);
                Tag replacement = remapValue(element, table, result, depth + 1);
                if (replacement != null) {
                    list.set(i, replacement);
                }
            }
            return null;
        }
        if (value instanceof LongTag longTag) {
            long packed = longTag.getAsLong();
            BlockPos pos = BlockPos.of(packed);
            if (table.isCaptured(pos)) {
                result.positions++;
                return LongTag.valueOf(table.mapPos(pos).asLong());
            }
            return null;
        }
        if (value instanceof IntArrayTag array) {
            int[] ints = array.getAsIntArray();
            if (ints.length == 3) {
                BlockPos pos = new BlockPos(ints[0], ints[1], ints[2]);
                if (table.isCaptured(pos)) {
                    BlockPos mapped = table.mapPos(pos);
                    result.positions++;
                    return new IntArrayTag(new int[]{mapped.getX(), mapped.getY(), mapped.getZ()});
                }
            } else if (ints.length == 4) {
                UUID mapped = table.mapUuid(NbtUtils.loadUUID(array));
                if (mapped != null) {
                    result.uuids++;
                    return NbtUtils.createUUID(mapped);
                }
            }
            return null;
        }
        if (value instanceof StringTag stringTag) {
            String s = stringTag.getAsString();
            if (s.length() == 36 && s.charAt(8) == '-') {
                try {
                    UUID mapped = table.mapUuid(UUID.fromString(s));
                    if (mapped != null) {
                        result.uuids++;
                        return StringTag.valueOf(mapped.toString());
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a UUID.
                }
            }
        }
        return null;
    }

    /** {@code [x, y, z]} lists of numbers, e.g. {@code Vec3}/{@code Vector3d} codecs or rope strand points. */
    private static Tag remapNumericVector(ListTag list, Table table, Result result) {
        if (list.size() != 3 || !(list.get(0) instanceof NumericTag)) {
            return null;
        }
        double x = ((NumericTag) list.get(0)).getAsDouble();
        double y = ((NumericTag) list.get(1)).getAsDouble();
        double z = ((NumericTag) list.get(2)).getAsDouble();
        PlotShift shift = table.find(x, z);
        if (shift == null || !table.validY(y)) {
            return null;
        }
        ListTag out = new ListTag();
        byte type = list.getElementType();
        out.add(number(type, x + shift.dx()));
        out.add(number(type, y + shift.dy()));
        out.add(number(type, z + shift.dz()));
        result.positions++;
        return out;
    }

    private static Tag number(byte type, double value) {
        return switch (type) {
            case Tag.TAG_INT -> IntTag.valueOf((int) Math.round(value));
            case Tag.TAG_LONG -> LongTag.valueOf(Math.round(value));
            case Tag.TAG_FLOAT -> FloatTag.valueOf((float) value);
            default -> DoubleTag.valueOf(value);
        };
    }

    /** {@code fooX, fooY, fooZ} (or {@code x, y, z}) siblings forming one position. */
    private static void remapTriplets(CompoundTag tag, Table table, Result result) {
        for (String key : List.copyOf(tag.getAllKeys())) {
            if (key.isEmpty()) {
                continue;
            }
            char last = key.charAt(key.length() - 1);
            if (last != 'X' && last != 'x') {
                continue;
            }
            String prefix = key.substring(0, key.length() - 1);
            String yKey = prefix + (last == 'X' ? 'Y' : 'y');
            String zKey = prefix + (last == 'X' ? 'Z' : 'z');
            if (!(tag.get(key) instanceof NumericTag nx) || !(tag.get(yKey) instanceof NumericTag ny) || !(tag.get(zKey) instanceof NumericTag nz)) {
                continue;
            }
            double x = nx.getAsDouble();
            double y = ny.getAsDouble();
            double z = nz.getAsDouble();
            PlotShift shift = table.find(x, z);
            if (shift == null || !table.validY(y)) {
                continue;
            }
            tag.put(key, number(tag.get(key).getId(), x + shift.dx()));
            tag.put(yKey, number(tag.get(yKey).getId(), y + shift.dy()));
            tag.put(zKey, number(tag.get(zKey).getId(), z + shift.dz()));
            result.positions++;
        }
    }

    /** {@code <key>Most}/{@code <key>Least} long pairs (legacy UUID encoding). */
    private static void remapMostLeastUuids(CompoundTag tag, Table table, Result result) {
        for (String key : List.copyOf(tag.getAllKeys())) {
            if (!key.endsWith("Most")) {
                continue;
            }
            String prefix = key.substring(0, key.length() - 4);
            String leastKey = prefix + "Least";
            if (tag.get(key) instanceof LongTag most && tag.get(leastKey) instanceof LongTag least) {
                UUID mapped = table.mapUuid(new UUID(most.getAsLong(), least.getAsLong()));
                if (mapped != null) {
                    tag.putLong(key, mapped.getMostSignificantBits());
                    tag.putLong(leastKey, mapped.getLeastSignificantBits());
                    result.uuids++;
                }
            }
        }
    }
}
