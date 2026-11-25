package com.radar.bathhouse.event;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.effect.PurityEffect;
import com.radar.bathhouse.registry.ModEffects;
import com.radar.bathhouse.session.BathSessionTracker;
import com.radar.bathhouse.session.PlungeTracker;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
        if (event.getEntity().hasEffect(ModEffects.PURITY) && PurityEffect.blocks(event.getEffectInstance().getEffect())) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
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
    }
}
