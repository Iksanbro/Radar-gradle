package com.radar.bathhouse.climate;

import net.minecraft.core.BlockPos;

/**
 * Anything that heats, steams or smokes a steam room: a multiblock stove (its firebox is the core),
 * the electric steam generator or the warm lounger. The room's gauge talks to it once a second.
 */
public interface HeatSource {
    /** Tells the source which room it serves and how warm that room is. */
    void link(BlockPos gauge, double roomTemperature, long gameTime);

    /** Heat (kJ), vapour (g) and smoke produced since the last call; drying is a current rate. */
    Output drainOutput(BlockPos gauge);

    /** What the source is made of, for classifying the bath and judging its quality. */
    SourceInfo info();

    record Output(double heat, double vapour, double smoke, double drying) {
        public static final Output NONE = new Output(0, 0, 0, 0);
    }
}
