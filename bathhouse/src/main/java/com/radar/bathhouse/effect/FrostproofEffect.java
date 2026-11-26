package com.radar.bathhouse.effect;

import net.minecraft.world.entity.LivingEntity;

/** Cold plunge, part 3: hardened by contrast — powder snow and frost no longer freeze you. */
public class FrostproofEffect extends BathEffect {
    public FrostproofEffect(int color) {
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
