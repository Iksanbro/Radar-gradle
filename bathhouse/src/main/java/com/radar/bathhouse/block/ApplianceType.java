package com.radar.bathhouse.block;

import com.mojang.serialization.Codec;
import com.radar.bathhouse.climate.SourceInfo;
import net.minecraft.util.StringRepresentable;

/** Single-block, redstone-powered bath appliances. Stoves are multiblocks (see the stove package). */
public enum ApplianceType implements StringRepresentable {
    //               id                  power mass transfer maxCore steam light kind
    STEAM_GENERATOR("steam_generator", 14, 4, 0.30, 110, 10, 3, SourceInfo.Kind.STEAM_GENERATOR),
    /** Heated marble lounger: gentle floor heating for mist halls; resting on it doubles the session speed. */
    WARM_LOUNGER("warm_lounger", 5, 30, 0.15, 60, 0, 0, SourceInfo.Kind.WARM_LOUNGER);

    public static final Codec<ApplianceType> CODEC = StringRepresentable.fromEnum(ApplianceType::values);

    private final String id;
    private final double power;
    private final double mass;
    private final double transfer;
    private final double maxCore;
    private final double steamRate;
    private final int lightLevel;
    private final SourceInfo.Kind kind;

    ApplianceType(String id, double power, double mass, double transfer, double maxCore, double steamRate, int lightLevel,
                  SourceInfo.Kind kind) {
        this.id = id;
        this.power = power;
        this.mass = mass;
        this.transfer = transfer;
        this.maxCore = maxCore;
        this.steamRate = steamRate;
        this.lightLevel = lightLevel;
        this.kind = kind;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "block.bathhouse." + id;
    }

    public double power() {
        return power;
    }

    public double mass() {
        return mass;
    }

    public double transfer() {
        return transfer;
    }

    public double maxCore() {
        return maxCore;
    }

    /** Grams of vapour per second at full power. */
    public double steamRate() {
        return steamRate;
    }

    public int lightLevel() {
        return lightLevel;
    }

    public SourceInfo.Kind kind() {
        return kind;
    }
}
