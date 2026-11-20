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
    /** Чанк площадки теста -> тик, с которого он не тикает. */
    private static final java.util.Map<Long, Long> WAITING = new java.util.HashMap<>();

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
                long now = server.getTickCount();
                net.minecraft.gametest.framework.StructureUtils.getStructureBoundingBox(sbe).intersectingChunks().forEach(cp -> {
                    if (level.isPositionEntityTicking(cp.getWorldPosition())) {
                        WAITING.remove(cp.toLong());
                        return;
                    }
                    long since = WAITING.computeIfAbsent(cp.toLong(), k -> now);
                    boolean forced = level.getForcedChunks().contains(cp.toLong());
                    if (!forced) {
                        level.setChunkForced(cp.x, cp.z, true);
                    } else if (now - since > 400) {
                        // принудительная загрузка числится, а чанк не тикает: переставить тикет
                        FcsMod.LOG.warn("[radar_fcs test] watchdog: test {} stuck on chunk {} for {} ticks, re-forcing",
                                info.getTestName(), cp, now - since);
                        level.setChunkForced(cp.x, cp.z, false);
                        level.setChunkForced(cp.x, cp.z, true);
                        WAITING.put(cp.toLong(), now);
                    }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            FcsMod.LOG.debug("[radar_fcs test] watchdog failed: {}", e.toString());
        }
    }

    /**
     * RPL (библиотека снарядов CBC) грузит чанки по пути снаряда и через несколько тиков снимает с них принудительную
     * загрузку - даже если чанк за это время принудительно загрузил кто-то ещё (общий тикет без счётчика). Снаряд
     * прошлого теста пролетает над местом следующей площадки, сервер тестов загружает её чанки, RPL их «отпускает»:
     * чанк числится загруженным, но сущности в нём стоят - снаряды зависают у ствола, по цели ни одного попадания.
     * Сторож возвращает тикет таким чанкам (только на сервере игровых тестов).
     */
    private static void keepForcedTickets(MinecraftServer server) {
        if (!(server instanceof net.minecraft.gametest.framework.GameTestServer)) return;
        for (var level : server.getAllLevels()) {
            for (long packed : level.getForcedChunks()) {
                var cp = new net.minecraft.world.level.ChunkPos(packed);
                if (level.isPositionEntityTicking(cp.getWorldPosition())) {
                    TICKING.add(packed);
                } else if (TICKING.remove(packed)) {
                    // уже тикал и перестал, хотя числится принудительно загруженным - тикет сняли со стороны
                    FcsMod.LOG.info("[radar_fcs test] forced chunk {} lost its ticket (RPL), restoring", cp);
                    level.getChunkSource().updateChunkForced(cp, true);
                }
            }
        }
    }

    /** Принудительно загруженные чанки, которые уже тикали для сущностей (только что загружаемые не трогаем). */
    private static final java.util.Set<Long> TICKING = new java.util.HashSet<>();

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Pre event) {
        keepForcedTickets(event.getServer());
    }

    /**
     * {@code -PfcsTests=имя[,имя...]}: сервер игровых тестов запускает только тесты, в имени которых
     * (класс.метод, строчными) есть одна из подстрок, - быстрая проверка одного теста без всего набора.
     */
    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public static void onAboutToStart(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        String filter = System.getProperty("radar_fcs.tests", "").toLowerCase(java.util.Locale.ROOT);
        if (filter.isBlank() || !(event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer server)) return;
        try {
            var f = net.minecraft.gametest.framework.GameTestServer.class.getDeclaredField("testFunctions");
            f.setAccessible(true);
            var tests = (java.util.List<net.minecraft.gametest.framework.TestFunction>) f.get(server);
            int all = tests.size();
            String[] keys = filter.split(",");
            tests.removeIf(t -> {
                String name = t.testName().toLowerCase(java.util.Locale.ROOT);
                for (String k : keys) if (!k.isBlank() && name.contains(k.trim())) return false;
                return true;
            });
            FcsMod.LOG.info("[radar_fcs test] filter '{}': {} of {} tests", filter, tests.size(), all);
        } catch (ReflectiveOperationException | RuntimeException e) {
            FcsMod.LOG.warn("[radar_fcs test] test filter failed: {}", e.toString());
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
