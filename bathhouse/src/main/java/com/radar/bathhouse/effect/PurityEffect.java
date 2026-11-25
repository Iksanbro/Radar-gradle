package com.radar.bathhouse.effect;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * Mist bath buff: cleansed and calm. Slow regeneration; poison, hunger and nausea are removed and cannot be
 * applied while it lasts (see {@code GameEvents#onEffectApplicable}).
 */
public class PurityEffect extends BathEffect {
    public static final List<Holder<MobEffect>> BLOCKED = List.of(MobEffects.POISON, MobEffects.HUNGER, MobEffects.CONFUSION);

    public PurityEffect(int color) {
        super(color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        int interval = Math.max(20, 100 >> amplifier);
        return duration % interval == 0;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.getHealth() < entity.getMaxHealth()) {
            entity.heal(1.0F);
        }
        return true;
    }

    @Override
    public void onEffectStarted(LivingEntity entity, int amplifier) {
        for (Holder<MobEffect> effect : BLOCKED) {
            entity.removeEffect(effect);
        }
    }

    public static boolean blocks(Holder<MobEffect> effect) {
        for (Holder<MobEffect> blocked : BLOCKED) {
            if (blocked.value() == effect.value()) {
                return true;
            }
        }
        return false;
    }
}
