package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

public final class ModDamageTypes {
    /** Defined in data/bathhouse/damage_type/overheat.json; tagged as fire so Fire Resistance protects. */
    public static final ResourceKey<DamageType> OVERHEAT = ResourceKey.create(Registries.DAMAGE_TYPE, Bathhouse.id("overheat"));

    private ModDamageTypes() {
    }
}
