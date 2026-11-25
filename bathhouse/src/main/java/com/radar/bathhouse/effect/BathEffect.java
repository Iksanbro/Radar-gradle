package com.radar.bathhouse.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** Beneficial bath buff; attribute modifiers are added at registration. */
public class BathEffect extends MobEffect {
    public BathEffect(int color) {
        super(MobEffectCategory.BENEFICIAL, color);
    }
}
