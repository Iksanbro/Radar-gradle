package com.radar.bathhouse.effect;

import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Failed mist hall, part 2 — the inverse of Clarity (which keeps hunger away) at half strength: you get hungry like
 * under Hunger, but half as fast.
 */
public class StuffinessEffect extends BathEffect {
    /** Vanilla Hunger I adds 0.005 exhaustion per tick. */
    public static final float EXHAUSTION_PER_TICK = 0.0025F;

    public StuffinessEffect(int color) {
        super(MobEffectCategory.HARMFUL, color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity instanceof Player player) {
            player.causeFoodExhaustion(EXHAUSTION_PER_TICK * (amplifier + 1));
        }
        return true;
    }
}
