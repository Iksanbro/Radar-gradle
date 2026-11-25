package com.radar.bathhouse.climate;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Snapshot of an enclosed room found by {@link RoomScanner}: its air volume, what its surfaces are made of
 * and which appliances and smoke vents border it.
 */
public final class RoomScan {
    /** Extra heat loss through an open smoke vent, kW/°C. */
    private static final double OPEN_VENT_CONDUCTANCE = 0.05;
    private static final double CLOSED_VENT_CONDUCTANCE = 0.006;
    /** Air exchange through an open vent, fraction of vapour per second. */
    private static final double OPEN_VENT_AIR_EXCHANGE = 0.01;
    /** Smoke removal through an open vent, fraction per second. */
    private static final double OPEN_VENT_SMOKE_EXCHANGE = 0.08;
    /** A flue opening in the ceiling draws smoke (and a little air and heat) out of the room. */
    private static final double FLUE_CONDUCTANCE = 0.01;
    private static final double FLUE_AIR_EXCHANGE = 0.005;
    private static final double FLUE_SMOKE_EXCHANGE = 0.08;
    /** Heat capacity of the air and furnishings per block of volume, kJ/°C. */
    private static final double AIR_CAPACITY = 0.2;

    private final boolean enclosed;
    private final LongSet interior;
    private final long[] interiorArray;
    private final EnumMap<WallMaterial, Integer> faces;
    private final Map<BlockPos, Set<Direction>> appliances;
    private final Set<BlockPos> openVents;
    private final Set<BlockPos> closedVents;
    private final Set<BlockPos> draftFlues;
    private final boolean duplicateGauge;
    @Nullable
    private final AABB bounds;

    RoomScan(boolean enclosed, LongSet interior, EnumMap<WallMaterial, Integer> faces,
             Map<BlockPos, Set<Direction>> appliances, Set<BlockPos> openVents, Set<BlockPos> closedVents,
             Set<BlockPos> draftFlues, boolean duplicateGauge, @Nullable AABB bounds) {
        this.enclosed = enclosed;
        this.interior = interior;
        this.interiorArray = interior.toLongArray();
        this.faces = faces;
        this.appliances = appliances;
        this.openVents = openVents;
        this.closedVents = closedVents;
        this.draftFlues = draftFlues;
        this.duplicateGauge = duplicateGauge;
        this.bounds = bounds;
    }

    /** False when the flood fill escaped (open door, hole, too large) or ran into unloaded chunks. */
    public boolean enclosed() {
        return enclosed;
    }

    public int volume() {
        return interior.size();
    }

    public boolean contains(BlockPos pos) {
        return interior.contains(pos.asLong());
    }

    public long[] interiorArray() {
        return interiorArray;
    }

    @Nullable
    public AABB bounds() {
        return bounds;
    }

    /** Appliance position → faces of that appliance that touch the room. */
    public Map<BlockPos, Set<Direction>> appliances() {
        return appliances;
    }

    public Set<BlockPos> openVents() {
        return openVents;
    }

    public Set<BlockPos> closedVents() {
        return closedVents;
    }

    /** Chimney openings in the room's ceiling or walls that are not stacked on a stove. */
    public Set<BlockPos> draftFlues() {
        return draftFlues;
    }

    public boolean duplicateGauge() {
        return duplicateGauge;
    }

    public int faces(WallMaterial material) {
        return faces.getOrDefault(material, 0);
    }

    public int wallFaces() {
        int total = 0;
        for (int count : faces.values()) {
            total += count;
        }
        return total;
    }

    /** Share of the room's wall, floor and ceiling faces made of the given material, 0..1. */
    public double fraction(WallMaterial material) {
        int total = wallFaces();
        return total == 0 ? 0.0 : (double) faces(material) / total;
    }

    /** Heat loss to the outside, kW/°C. */
    public double conductance() {
        double ua = 0.0;
        for (Map.Entry<WallMaterial, Integer> e : faces.entrySet()) {
            ua += e.getKey().conductance() * e.getValue();
        }
        return ua + openVents.size() * OPEN_VENT_CONDUCTANCE + closedVents.size() * CLOSED_VENT_CONDUCTANCE
                + draftFlues.size() * FLUE_CONDUCTANCE;
    }

    /** Heat capacity of air, furnishings and walls, kJ/°C. */
    public double heatCapacity() {
        double c = AIR_CAPACITY * Math.max(1, volume());
        for (Map.Entry<WallMaterial, Integer> e : faces.entrySet()) {
            c += e.getKey().capacity() * e.getValue();
        }
        return Math.max(1.0, c);
    }

    public double vapourVentilation() {
        return openVents.size() * OPEN_VENT_AIR_EXCHANGE + draftFlues.size() * FLUE_AIR_EXCHANGE;
    }

    public double smokeVentilation() {
        return openVents.size() * OPEN_VENT_SMOKE_EXCHANGE + draftFlues.size() * FLUE_SMOKE_EXCHANGE;
    }
}
