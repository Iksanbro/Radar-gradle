package com.radar.bathhouse.plunge;

import com.radar.bathhouse.registry.ModEffects;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

/**
 * What a plunge basin is, decided from its contents and parts:
 * <ul>
 *     <li>COLD — water, no stove: 4–12 °C contrast dip after the steam room;</li>
 *     <li>HOT — water and a stove (built-in or outside the wall): 38–42 °C, needs an inner bench;</li>
 *     <li>SAWDUST — hot shavings and a stove: 45–50 °C.</li>
 * </ul>
 */
public enum PlungeKind {
    COLD("cold", 4, 12, 4, 8, 0, ModEffects.COLD_PARTS),
    HOT("hot", 38, 42, 39, 41, 42, ModEffects.HOT_PARTS),
    SAWDUST("sawdust", 45, 50, 46, 49, 50, ModEffects.HOT_PARTS);

    private final String id;
    private final int minTemperature;
    private final int maxTemperature;
    private final int idealMin;
    private final int idealMax;
    private final int throttle;
    private final List<Holder<MobEffect>> effects;

    PlungeKind(String id, int minTemperature, int maxTemperature, int idealMin, int idealMax, int throttle,
               List<Holder<MobEffect>> effects) {
        this.id = id;
        this.minTemperature = minTemperature;
        this.maxTemperature = maxTemperature;
        this.idealMin = idealMin;
        this.idealMax = idealMax;
        this.throttle = throttle;
        this.effects = effects;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "bathhouse.plunge.kind." + id;
    }

    public int minTemperature() {
        return minTemperature;
    }

    public int maxTemperature() {
        return maxTemperature;
    }

    public boolean inRange(double temperature) {
        return temperature >= minTemperature && temperature <= maxTemperature;
    }

    public boolean ideal(double temperature) {
        return temperature >= idealMin && temperature <= idealMax;
    }

    public int idealMin() {
        return idealMin;
    }

    public int idealMax() {
        return idealMax;
    }

    public boolean heated() {
        return this != COLD;
    }

    /** The stove's damper closes at this temperature. */
    public int throttle() {
        return throttle;
    }

    public List<Holder<MobEffect>> effects() {
        return effects;
    }
}
