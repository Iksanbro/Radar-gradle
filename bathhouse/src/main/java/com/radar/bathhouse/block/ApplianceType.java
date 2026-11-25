package com.radar.bathhouse.block;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Heating and steam appliances. Each one has a "core" (stones, firebox, boiler) with its own
 * temperature and heat capacity that exchanges heat with the room it borders.
 *
 * <p>Units are game-abstract but self-consistent: power in kW, heat capacity in kJ/°C,
 * transfer in kW/°C, vapour in grams of water.</p>
 */
public enum ApplianceType implements StringRepresentable {
    //               id                 source              power  mass  transfer  maxCore stones outer  drying  steam  smoke light
    INNER_HEATER("inner_heater", HeatSource.FUEL, 32, 10, 0.20, 500, true, false, 0.0, 0, 2.0, 13),
    OUTER_HEATER("outer_heater", HeatSource.FUEL, 30, 14, 0.18, 450, true, true, 0.0, 0, 2.0, 13),
    BRICK_STOVE("brick_stove", HeatSource.FUEL, 32, 20, 0.16, 450, true, false, 0.0, 0, 2.5, 11),
    IRON_STOVE("iron_stove", HeatSource.FUEL, 34, 5, 0.35, 600, false, false, 0.010, 0, 2.0, 13),
    ELECTRIC_HEATER("electric_heater", HeatSource.ELECTRIC, 28, 8, 0.20, 450, true, false, 0.0, 0, 0.0, 7),
    STEAM_GENERATOR("steam_generator", HeatSource.STEAM, 14, 4, 0.30, 110, false, false, 0.0, 10, 0.0, 3),
    /** Heated marble lounger: gentle floor heating for mist baths; standing on it doubles the session speed. */
    WARM_LOUNGER("warm_lounger", HeatSource.ELECTRIC, 5, 30, 0.15, 60, false, false, 0.0, 0, 0.0, 0);

    public static final Codec<ApplianceType> CODEC = StringRepresentable.fromEnum(ApplianceType::values);

    private final String id;
    private final HeatSource source;
    private final double power;
    private final double mass;
    private final double transfer;
    private final double maxCore;
    private final boolean stones;
    private final boolean outerFirebox;
    private final double drying;
    private final double steamRate;
    private final double smokeRate;
    private final int lightLevel;

    ApplianceType(String id, HeatSource source, double power, double mass, double transfer, double maxCore,
                  boolean stones, boolean outerFirebox, double drying, double steamRate, double smokeRate, int lightLevel) {
        this.id = id;
        this.source = source;
        this.power = power;
        this.mass = mass;
        this.transfer = transfer;
        this.maxCore = maxCore;
        this.stones = stones;
        this.outerFirebox = outerFirebox;
        this.drying = drying;
        this.steamRate = steamRate;
        this.smokeRate = smokeRate;
        this.lightLevel = lightLevel;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "block.bathhouse." + id;
    }

    public HeatSource source() {
        return source;
    }

    /** Burns solid fuel (and therefore makes smoke unless it has a chimney). */
    public boolean isFueled() {
        return source == HeatSource.FUEL;
    }

    /** Runs on redstone power. */
    public boolean isPowered() {
        return source == HeatSource.ELECTRIC || source == HeatSource.STEAM;
    }

    /** Full-power heat output, kW. */
    public double power() {
        return power;
    }

    /** Core heat capacity, kJ/°C. Heavier cores heat up slowly and stay hot for longer. */
    public double mass() {
        return mass;
    }

    /** Core-to-room heat transfer, kW/°C. */
    public double transfer() {
        return transfer;
    }

    /** Above this core temperature the damper closes and the appliance throttles. */
    public double maxCore() {
        return maxCore;
    }

    /** Has a basket of stones you can throw water on. */
    public boolean hasStones() {
        return stones;
    }

    /** Fed from outside the steam room: smoke stays outside if the firebox door faces away from the room. */
    public boolean outerFirebox() {
        return outerFirebox;
    }

    /** Extra air exchange (per second) while burning: the firebox draws moist air out of the room. */
    public double drying() {
        return drying;
    }

    /** Grams of vapour per second while running. */
    public double steamRate() {
        return steamRate;
    }

    /** Smoke added to a 27 m³ room per second (in percent) when the smoke has nowhere else to go. */
    public double smokeRate() {
        return smokeRate;
    }

    public int lightLevel() {
        return lightLevel;
    }

    public enum HeatSource {
        FUEL, ELECTRIC, STEAM
    }
}
