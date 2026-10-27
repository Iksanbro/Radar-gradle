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

    /**
     * Сторож игровых тестов: площадка теста ждёт, пока её чанки станут «тикающими» для сущностей.
     * Если чанк застрял (бывает после тестов с кораблями Sable), пишем это в лог и догружаем его.
     */
    private static void watchTests(MinecraftServer server) {
        try {
            var f = net.minecraft.gametest.framework.GameTestTicker.class.getDeclaredField("testInfos");
            f.setAccessible(true);
            var sbeField = net.minecraft.gametest.framework.GameTestInfo.class.getDeclaredField("structureBlockEntity");
            sbeField.setAccessible(true);
            var loadedField = net.minecraft.gametest.framework.GameTestInfo.class.getDeclaredField("chunksLoaded");
            loadedField.setAccessible(true);
            for (Object o : (java.util.Collection<?>) f.get(net.minecraft.gametest.framework.GameTestTicker.SINGLETON)) {
                var info = (net.minecraft.gametest.framework.GameTestInfo) o;
                if (info.isDone() || loadedField.getBoolean(info)) continue;
                var sbe = (net.minecraft.world.level.block.entity.StructureBlockEntity) sbeField.get(info);
                if (sbe == null || !(sbe.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) continue;
                net.minecraft.gametest.framework.StructureUtils.getStructureBoundingBox(sbe).intersectingChunks().forEach(cp -> {
                    if (!level.isPositionEntityTicking(cp.getWorldPosition())) {
                        FcsMod.LOG.warn("[radar_fcs test] watchdog: test {} waits for chunk {} (forced={}), forcing it",
                                info.getTestName(), cp, level.getForcedChunks().contains(cp.toLong()));
                        level.setChunkForced(cp.x, cp.z, true);
                    }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            FcsMod.LOG.debug("[radar_fcs test] watchdog failed: {}", e.toString());
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 == 0) watchTests(event.getServer());
        if (!Boolean.getBoolean("radar_fcs.autostop")) return;
        if (TICKS.incrementAndGet() == 60) {
            MinecraftServer server = event.getServer();
            FcsMod.LOG.info("[radar_fcs dev] autostop");
            server.halt(false);
        }
    }
}
