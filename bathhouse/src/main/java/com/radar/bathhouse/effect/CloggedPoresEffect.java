package com.radar.bathhouse.effect;

import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * Failed mist hall, part 1 — the inverse of Purity at half strength: instead of healing 1 HP every 5 s it takes
 * 1 HP every 10 s, but never below one heart.
 */
public class CloggedPoresEffect extends BathEffect {
    public static final float MIN_HEALTH = 2.0F;

    public CloggedPoresEffect(int color) {
        super(MobEffectCategory.HARMFUL, color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % Math.max(40, 200 >> amplifier) == 0;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.getHealth() > MIN_HEALTH) {
            entity.hurt(entity.damageSources().magic(), 1.0F);
        }
        return true;
    }
}
