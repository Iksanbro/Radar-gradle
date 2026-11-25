package com.radar.bathhouse.climate;

import com.radar.bathhouse.registry.ModTags;
import net.minecraft.world.level.block.state.BlockState;

/** What the room's surfaces are made of. Affects heat loss, thermal mass and which bath types are possible. */
public enum WallMaterial {
    //          U (kW/°C per face), heat capacity per face (kJ/°C)
    WOOD("wood", 0.006, 0.3),
    STONE("stone", 0.008, 0.5),
    GLASS("glass", 0.025, 0.2),
    INSULATION("insulation", 0.003, 0.2),
    OTHER("other", 0.010, 0.4);

    private final String id;
    private final double conductance;
    private final double capacity;

    WallMaterial(String id, double conductance, double capacity) {
        this.id = id;
        this.conductance = conductance;
        this.capacity = capacity;
    }

    public static WallMaterial of(BlockState state) {
        if (state.is(ModTags.BATH_WOOD)) {
            return WOOD;
        }
        if (state.is(ModTags.BATH_STONE)) {
            return STONE;
        }
        if (state.is(ModTags.BATH_GLASS)) {
            return GLASS;
        }
        if (state.is(ModTags.BATH_INSULATION)) {
            return INSULATION;
        }
        return OTHER;
    }

    public String translationKey() {
        return "bathhouse.material." + id;
    }

    public double conductance() {
        return conductance;
    }

    public double capacity() {
        return capacity;
    }
}
