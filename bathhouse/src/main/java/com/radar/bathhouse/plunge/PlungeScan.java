package com.radar.bathhouse.plunge;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/** The inside of a plunge basin found by {@link PlungeScanner}. */
public final class PlungeScan {
    /** Heat capacity per block of water / sawdust, kJ/°C. */
    public static final double WATER_CAPACITY = 4.0;
    public static final double SAWDUST_CAPACITY = 1.5;
    /** Heat lost through the open surface per block, kW/°C. */
    public static final double WATER_SURFACE_LOSS = 0.02;
    public static final double SAWDUST_SURFACE_LOSS = 0.008;

    private final boolean valid;
    @Nullable
    private final Component problem;
    private final LongSet interior;
    private final int water;
    private final int sawdust;
    private final int benches;
    private final int surface;
    private final EnumMap<PlungeMaterial, Integer> faces;
    private final Map<BlockPos, Integer> stoves;
    private final Set<BlockPos> chillers;
    @Nullable
    private final AABB bounds;

    PlungeScan(boolean valid, @Nullable Component problem, LongSet interior, int water, int sawdust, int benches, int surface,
               EnumMap<PlungeMaterial, Integer> faces, Map<BlockPos, Integer> stoves, Set<BlockPos> chillers, @Nullable AABB bounds) {
        this.valid = valid;
        this.problem = problem;
        this.interior = interior;
        this.water = water;
        this.sawdust = sawdust;
        this.benches = benches;
        this.surface = surface;
        this.faces = faces;
        this.stoves = stoves;
        this.chillers = chillers;
        this.bounds = bounds;
    }

    static PlungeScan invalid(Component problem) {
        return new PlungeScan(false, problem, LongSet.of(), 0, 0, 0, 0, new EnumMap<>(PlungeMaterial.class), Map.of(), Set.of(), null);
    }

    public boolean valid() {
        return valid;
    }

    @Nullable
    public Component problem() {
        return problem;
    }

    public boolean contains(BlockPos pos) {
        return interior.contains(pos.asLong());
    }

    public int volume() {
        return interior.size();
    }

    public int water() {
        return water;
    }

    public int sawdust() {
        return sawdust;
    }

    public int benches() {
        return benches;
    }

    /** Stove position → number of its faces touching the basin. */
    public Map<BlockPos, Integer> stoves() {
        return stoves;
    }

    public Set<BlockPos> chillers() {
        return chillers;
    }

    @Nullable
    public AABB bounds() {
        return bounds;
    }

    public int wallFaces() {
        int total = 0;
        for (int count : faces.values()) {
            total += count;
        }
        return total;
    }

    /** Share of wooden and composite walls, 0..1. */
    public double insulatedFraction() {
        int total = wallFaces();
        if (total == 0) {
            return 0.0;
        }
        int insulated = 0;
        for (Map.Entry<PlungeMaterial, Integer> e : faces.entrySet()) {
            if (e.getKey().insulated()) {
                insulated += e.getValue();
            }
        }
        return (double) insulated / total;
    }

    public double conductance() {
        double ua = 0.0;
        for (Map.Entry<PlungeMaterial, Integer> e : faces.entrySet()) {
            ua += e.getKey().conductance() * e.getValue();
        }
        return ua + surface * (sawdust > 0 ? SAWDUST_SURFACE_LOSS : WATER_SURFACE_LOSS);
    }

    public double capacity() {
        return Math.max(1.0, water * WATER_CAPACITY + sawdust * SAWDUST_CAPACITY);
    }
}
