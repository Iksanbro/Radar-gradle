package com.radar.bathhouse.climate;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/** Small psychrometrics helper: ambient temperature and water vapour saturation. */
public final class Climate {
    /** Relative humidity of outside air. */
    public static final double AMBIENT_HUMIDITY = 0.5;
    /** Reference room volume used to normalise smoke output. */
    public static final double REFERENCE_VOLUME = 27.0;

    private Climate() {
    }

    /** Outside air temperature in °C, derived from the biome. Plains ≈ 14 °C, snowy ≈ -8 °C, desert ≈ 40 °C. */
    public static double ambientTemperature(Level level, BlockPos pos) {
        float base = level.getBiome(pos).value().getBaseTemperature();
        return Mth.clamp(-8.0 + 28.0 * base, -25.0, 40.0);
    }

    /** Water vapour density at saturation, g/m³ (Magnus formula). */
    public static double saturation(double temperature) {
        double t = Mth.clamp(temperature, -40.0, 150.0);
        double pressure = 6.112 * Math.exp(17.62 * t / (243.12 + t));
        return 216.7 * pressure / (273.15 + t);
    }

    /** Relative humidity, percent. */
    public static double relativeHumidity(double temperature, double vapour) {
        return Mth.clamp(100.0 * vapour / saturation(temperature), 0.0, 100.0);
    }

    public static double ambientVapour(double ambientTemperature) {
        return AMBIENT_HUMIDITY * saturation(ambientTemperature);
    }
}
