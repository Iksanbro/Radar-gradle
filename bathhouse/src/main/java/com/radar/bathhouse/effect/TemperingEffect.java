package com.radar.bathhouse.effect;

import net.minecraft.world.entity.LivingEntity;

/** Contrast buff (cold plunge after the steam room): powder snow and frost no longer freeze you. */
public class TemperingEffect extends BathEffect {
    public TemperingEffect(int color) {
        super(color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.getTicksFrozen() > 0) {
            entity.setTicksFrozen(0);
        }
        return true;
    }
}
