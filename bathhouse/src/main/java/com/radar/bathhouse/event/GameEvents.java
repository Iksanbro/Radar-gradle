package com.radar.bathhouse.event;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.effect.ImmunityEffect;
import com.radar.bathhouse.plunge.PlungeRegistry;
import com.radar.bathhouse.registry.ModEffects;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.world.effect.MobEffectInstance;
import com.radar.bathhouse.session.BathSessionTracker;
import com.radar.bathhouse.session.PlungeTracker;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = Bathhouse.MOD_ID)
public final class GameEvents {
    private GameEvents() {
    }

    @SubscribeEvent
    public static void onEffectApplicable(MobEffectEvent.Applicable event) {
        for (MobEffectInstance active : event.getEntity().getActiveEffects()) {
            if (active.getEffect().value() instanceof ImmunityEffect immunity && immunity.blocks(event.getEffectInstance().getEffect())) {
                event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
                return;
            }
        }
    }

    /** Toxins (failed mist hall): poison and wither hurt 50 % more — the inverse of Cleansing at half strength. */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().hasEffect(ModEffects.TOXINS)
                && (event.getSource().is(Tags.DamageTypes.IS_POISON) || event.getSource().is(DamageTypes.WITHER))) {
            event.setAmount(event.getAmount() * 1.5F);
        }
    }

    /** Unsteadiness (failed steam lodge): knockback +10 % — the inverse of Steadfastness at half strength. */
    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (event.getEntity().hasEffect(ModEffects.UNSTEADINESS)) {
            event.setStrength(event.getStrength() * 1.1F);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int now = event.getServer().getTickCount();
        if (now % 200 == 0) {
            BathSessionTracker.cleanup(now);
        }
    }

    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlungeTracker.beforeTick(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlungeTracker.tick(player);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlungeTracker.forget(player);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BathSessionTracker.clear();
        PlungeTracker.clear();
        PlungeRegistry.clear();
    }
}
