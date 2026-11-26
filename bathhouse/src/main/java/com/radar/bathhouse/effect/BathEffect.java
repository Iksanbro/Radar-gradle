package com.radar.bathhouse.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** A bath buff (or, for failed steaming, a debuff); attribute modifiers are added at registration. */
public class BathEffect extends MobEffect {
    public BathEffect(int color) {
        this(MobEffectCategory.BENEFICIAL, color);
    }

    public BathEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    public static BathEffect penalty(int color) {
        return new BathEffect(MobEffectCategory.HARMFUL, color);
    }
}
