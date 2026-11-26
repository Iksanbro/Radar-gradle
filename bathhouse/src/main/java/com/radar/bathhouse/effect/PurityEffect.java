package com.radar.bathhouse.effect;

import net.minecraft.world.entity.LivingEntity;

/** Mist hall, part 1: steamed-open pores — slow regeneration. */
public class PurityEffect extends BathEffect {
    public PurityEffect(int color) {
        super(color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % Math.max(20, 100 >> amplifier) == 0;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.getHealth() < entity.getMaxHealth()) {
            entity.heal(1.0F);
        }
        return true;
    }
}
