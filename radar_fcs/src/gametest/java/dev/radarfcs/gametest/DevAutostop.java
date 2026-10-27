package dev.radarfcs.gametest;

import dev.radarfcs.FcsMod;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Dev-запуск: -Dradar_fcs.autostop=true останавливает сервер (с сохранением мира) после старта. */
@EventBusSubscriber(modid = FcsMod.MODID)
public final class DevAutostop {
    private static final AtomicInteger TICKS = new AtomicInteger();

    private DevAutostop() {}

    /** Урон по тестовым целям - для подсчёта попаданий в игровых тестах. */
    @SubscribeEvent
    public static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        FcsGameTests.onDamage(event);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("radar_fcs.autostop")) return;
        if (TICKS.incrementAndGet() == 60) {
            MinecraftServer server = event.getServer();
            FcsMod.LOG.info("[radar_fcs dev] autostop");
            server.halt(false);
        }
    }
}
