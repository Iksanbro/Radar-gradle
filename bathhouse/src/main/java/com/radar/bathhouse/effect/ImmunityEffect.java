package com.radar.bathhouse.effect;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;

/** Removes the listed harmful effects and keeps them away while active (see {@code GameEvents}). */
public class ImmunityEffect extends BathEffect {
    private final List<Holder<MobEffect>> blocked;

    public ImmunityEffect(int color, List<Holder<MobEffect>> blocked) {
        super(color);
        this.blocked = blocked;
    }

    public boolean blocks(Holder<MobEffect> effect) {
        for (Holder<MobEffect> holder : blocked) {
            if (holder.value() == effect.value()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onEffectStarted(LivingEntity entity, int amplifier) {
        for (Holder<MobEffect> effect : blocked) {
            entity.removeEffect(effect);
        }
    }
}
