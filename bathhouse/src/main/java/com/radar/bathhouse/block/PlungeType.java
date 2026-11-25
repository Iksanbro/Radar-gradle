package com.radar.bathhouse.block;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Plunge tubs: deep wooden barrels with a bench inside.
 * <ul>
 *     <li>COLD — 4–12 °C water for the contrast dip after the steam room (10–30 s).</li>
 *     <li>HOT — wood-fired hot water tub, 38–42 °C; works outdoors in winter.</li>
 *     <li>SAWDUST — wood-fired tub of hot cedar and linden shavings, 45–50 °C; herbs make it last longer.</li>
 * </ul>
 */
public enum PlungeType implements StringRepresentable {
    //       id               medium           heated min max  power throttle loss  capacity
    COLD("cold_plunge", Medium.WATER, false, 4, 12, 0.0, 0.0, 0.02, 30.0),
    HOT("hot_plunge", Medium.WATER, true, 38, 42, 8.0, 42.0, 0.10, 30.0),
    SAWDUST("sawdust_plunge", Medium.SAWDUST, true, 45, 50, 8.0, 50.0, 0.06, 20.0);

    public static final Codec<PlungeType> CODEC = StringRepresentable.fromEnum(PlungeType::values);
    /** Content level that reaches the chest of someone sitting on the inner bench. */
    public static final int CHEST_LEVEL = 2;
    public static final int MAX_LEVEL = 3;

    private final String id;
    private final Medium medium;
    private final boolean heated;
    private final int minTemperature;
    private final int maxTemperature;
    private final double power;
    private final double throttle;
    private final double loss;
    private final double capacity;

    PlungeType(String id, Medium medium, boolean heated, int minTemperature, int maxTemperature, double power,
               double throttle, double loss, double capacity) {
        this.id = id;
        this.medium = medium;
        this.heated = heated;
        this.minTemperature = minTemperature;
        this.maxTemperature = maxTemperature;
        this.power = power;
        this.throttle = throttle;
        this.loss = loss;
        this.capacity = capacity;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "block.bathhouse." + id;
    }

    public Medium medium() {
        return medium;
    }

    /** Has a built-in wood-fired stove. */
    public boolean heated() {
        return heated;
    }

    public int minTemperature() {
        return minTemperature;
    }

    public int maxTemperature() {
        return maxTemperature;
    }

    /** Stove output, kW. */
    public double power() {
        return power;
    }

    /** The stove's damper closes at this temperature. */
    public double throttle() {
        return throttle;
    }

    /** Heat loss to the outside, kW/°C. */
    public double loss() {
        return loss;
    }

    /** Heat capacity when full, kJ/°C. */
    public double capacity() {
        return capacity;
    }

    public boolean inRange(double temperature) {
        return temperature >= minTemperature && temperature <= maxTemperature;
    }

    public enum Medium {
        WATER, SAWDUST
    }
}
