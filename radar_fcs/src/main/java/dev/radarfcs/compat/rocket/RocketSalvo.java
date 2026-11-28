package dev.radarfcs.compat.rocket;

import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.link.LinkRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Автопуск ракет программируемыми носами одной радарной сети - по настройке вычислителя этой сети (вкладка «Ракеты»):
 * <ul>
 *   <li>одиночные - по цели одна ракета: пока ракета сети к ней в полёте, новые по ней не пускаются (промах - следующая);</li>
 *   <li>очередь - по цели подряд заданное число ракет с настраиваемой задержкой между ними; новая очередь - когда
 *   ракеты прошлой долетели, а цель жива;</li>
 *   <li>время между пусками - не чаще, чем раз в столько, любыми носами сети; у очереди - пауза перед новой очередью
 *   (от последнего пуска сети), внутри очереди - задержка между ракетами.</li>
 * </ul>
 * Прерванная очередь не продолжается: ракеты кончились или цель пропала (по ней {@link #STALE_TICKS} никто не
 * спрашивал пуск) - очередь закрыта, следующая по этой цели - снова полностью (когда ракеты прерванной долетят).
 * Пуск по редстоуну этим не ограничивается. Вычислителя в сети нет - настройки по умолчанию (одиночные, 1 с).
 */
public final class RocketSalvo {
    /** Почему автопуск сейчас нельзя. */
    public enum Verdict { GO, INTERVAL, IN_FLIGHT, QUEUE_DONE }

    private record Key(ResourceKey<Level> dim, UUID network) {}

    private static final class Queue {
        int launched;
        long lastTick;
        /** Когда носы сети последний раз спрашивали пуск по этой цели. */
        long lastCheck;
        /** Очередь прервана (ракеты кончились, цель пропадала) - не продолжается. */
        boolean closed;
    }

    private static final class Net {
        long last = Long.MIN_VALUE / 2;
        final Map<String, Queue> queues = new HashMap<>();
    }

    /**
     * Очередь по цели сбрасывается, если по ней столько тиков никто не спрашивал пуск: ракеты кончились (носов на
     * автопуске больше нет) или цель пропала. Следующая очередь - снова полностью (носы спрашивают раз в 5 тиков).
     */
    static final long STALE_TICKS = 40;
    private static final EngageSettings DEFAULTS = new EngageSettings();
    private static final Map<Key, Net> NETS = new HashMap<>();

    private RocketSalvo() {}

    static void clear() {
        NETS.clear();
    }

    /** Можно ли сейчас автопуском пустить ракету сети network по цели target. */
    public static Verdict check(ServerLevel level, UUID network, String target, long now) {
        EngageSettings s = settings(level, network);
        Net n = NETS.get(new Key(level.dimension(), network));
        if (n != null) n.queues.entrySet().removeIf(e -> {
            Queue q = e.getValue();
            if (now - q.lastCheck <= STALE_TICKS) return false;
            if (inFlight(level, network, e.getKey()) == 0) return true;            // забыта: новая - полностью
            q.closed = true;                                                       // её ракеты ещё летят
            return false;
        });
        long sinceLast = n == null ? Long.MAX_VALUE : now - n.last;
        int flying = inFlight(level, network, target);
        if (s.rocketMode == EngageSettings.RocketMode.SINGLE) {
            if (sinceLast < s.rocketIntervalTicks) return Verdict.INTERVAL;
            return flying == 0 ? Verdict.GO : Verdict.IN_FLIGHT;
        }
        Queue q = n == null ? null : n.queues.get(target);
        if (q != null) q.lastCheck = now;
        if (q != null && !q.closed && q.launched > 0 && q.launched < s.rocketQueue) {   // очередь идёт - задержка между ракетами
            return now - q.lastTick < s.rocketQueueDelayTicks ? Verdict.INTERVAL : Verdict.GO;
        }
        if (q != null && (q.closed || q.launched >= s.rocketQueue) && flying > 0) return Verdict.QUEUE_DONE;
        // новая очередь (по этой цели впервые или прошлая долетела, а цель жива) - после паузы между очередями
        if (sinceLast < s.rocketIntervalTicks) return Verdict.INTERVAL;
        if (q != null) {
            q.launched = 0;
            q.closed = false;
        }
        return Verdict.GO;
    }

    /** Ракета ушла автопуском. */
    public static void launched(ServerLevel level, UUID network, String target, long now) {
        Net n = NETS.computeIfAbsent(new Key(level.dimension(), network), k -> new Net());
        n.last = now;
        Queue q = n.queues.computeIfAbsent(target, k -> new Queue());
        q.launched++;
        q.lastTick = now;
        q.lastCheck = now;
    }

    /** Ракеты сети, летящие сейчас к цели. */
    static int inFlight(ServerLevel level, UUID network, String target) {
        int n = 0;
        for (RocketGuidance.Guided g : RocketGuidance.guided(level)) {
            if (!g.rocket().isRemoved() && network.equals(g.data().network()) && target.equals(g.lock())) n++;
        }
        return n;
    }

    /** Настройки вычислителя сети (первого загруженного); нет - по умолчанию. */
    public static EngageSettings settings(ServerLevel level, UUID network) {
        for (BlockPos p : LinkRegistry.members(level, network)) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof FireControlBlockEntity f) return f.settings();
        }
        return DEFAULTS;
    }
}
