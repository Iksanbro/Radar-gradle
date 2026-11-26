package com.radar.bathhouse.stove;

import net.minecraft.util.StringRepresentable;

/**
 * The heart of a stove. Wood fireboxes need an ash pit underneath for a good draft and a chimney on top
 * to keep the smoke out of the room; the electric heating element needs neither.
 */
public enum FireboxType implements StringRepresentable {
    //          id                    fuel   power mass transfer maxCore drying
    STEEL("steel_firebox", true, 30, 8, 0.14, 500, 0.0),
    CAST_IRON("cast_iron_firebox", true, 34, 6, 0.17, 550, 0.01),
    BRICK("brick_firebox", true, 36, 20, 0.11, 500, 0.0),
    ELECTRIC("heating_element", false, 28, 6, 0.14, 420, 0.0);

    private final String id;
    private final boolean fuel;
    private final double power;
    private final double mass;
    private final double transfer;
    private final double maxCore;
    private final double drying;

    FireboxType(String id, boolean fuel, double power, double mass, double transfer, double maxCore, double drying) {
        this.id = id;
        this.fuel = fuel;
        this.power = power;
        this.mass = mass;
        this.transfer = transfer;
        this.maxCore = maxCore;
        this.drying = drying;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "block.bathhouse." + id;
    }

    /** Burns wood and coal (otherwise: runs on redstone). */
    public boolean burnsFuel() {
        return fuel;
    }

    /** Full output, kW. */
    public double power() {
        return power;
    }

    /** Heat capacity, kJ/°C: heavy fireboxes heat up slowly and stay hot for long. */
    public double mass() {
        return mass;
    }

    /** Heat transfer to the room, kW/°C. */
    public double transfer() {
        return transfer;
    }

    /** The damper throttles the fire above this core temperature. */
    public double maxCore() {
        return maxCore;
    }

    /** Extra air exchange while burning: the firebox draws moist air out of the room. */
    public double drying() {
        return drying;
    }
}
