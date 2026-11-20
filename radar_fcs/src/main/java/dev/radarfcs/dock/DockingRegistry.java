package dev.radarfcs.dock;

import dev.radarfcs.core.Docking;
import dev.radarfcs.core.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Порты стыковки по измерениям: что сообщают о себе стыковочные контроллеры в роли «порт» (номер, сети, мировой
 * конец порта Simulated, направление, скорость, сцеплен ли). Порт, чей чанк выгрузился, остаётся с последним
 * положением - корабль летит к нему, чанк подгружается по дороге. Брони портов: корабль (его штекер) держит порт за
 * собой, продлевая бронь каждый тик; бронь говорит порту, когда выдвигаться.
 */
public final class DockingRegistry {
    /** Порт: контроллер pos, номер, сети, конец порта и направление (мир), скорость (бл/с), корабль порта, сцеплен ли. */
    public record PortInfo(BlockPos pos, int number, Set<UUID> networks, Vec tip, Vec facing, Vec vel, @Nullable UUID ship,
                           boolean locked, boolean loaded, List<Vec> tips) {
        public Docking.Port port() {
            return new Docking.Port(tip, facing, vel, tips);
        }

        public int count() {
            return tips.size();
        }
    }

    /** Бронь: чей штекер (ключ), до какого тика, выдвигать ли порт. */
    public record Reservation(long owner, long until, boolean extend) {}

    /** Выбор порта: порт и свободен ли он (false - все с этим номером заняты, это ближайший). */
    public record Choice(PortInfo port, boolean free) {}

    private static final Map<ResourceKey<Level>, Map<BlockPos, PortInfo>> PORTS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<BlockPos, Reservation>> RESERVED = new ConcurrentHashMap<>();

    private DockingRegistry() {}

    public static void put(Level level, PortInfo p) {
        PORTS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(p.pos().immutable(), p);
    }

    /** Контроллер убран (или стал штекером, или без порта Simulated). */
    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, PortInfo> m = PORTS.get(level.dimension());
        if (m != null) m.remove(pos);
        Map<BlockPos, Reservation> r = RESERVED.get(level.dimension());
        if (r != null) r.remove(pos);
    }

    /** Чанк порта выгружен: положение помнится. */
    public static void unloaded(Level level, BlockPos pos) {
        Map<BlockPos, PortInfo> m = PORTS.get(level.dimension());
        if (m == null) return;
        m.computeIfPresent(pos, (k, p) -> new PortInfo(p.pos(), p.number(), p.networks(), p.tip(), p.facing(), Vec.ZERO, p.ship(),
                p.locked(), false, p.tips()));
    }

    @Nullable
    public static PortInfo get(Level level, BlockPos pos) {
        Map<BlockPos, PortInfo> m = PORTS.get(level.dimension());
        return m == null ? null : m.get(pos);
    }

    /** Порты с номером в одной из сетей (кроме портов на корабле exceptShip). */
    public static List<PortInfo> ports(Level level, Set<UUID> networks, int number, @Nullable UUID exceptShip) {
        List<PortInfo> out = new ArrayList<>();
        Map<BlockPos, PortInfo> m = PORTS.get(level.dimension());
        if (m == null || networks.isEmpty()) return out;
        for (PortInfo p : m.values()) {
            if (p.number() != number || (exceptShip != null && exceptShip.equals(p.ship()))) continue;
            boolean shared = false;
            for (UUID n : p.networks()) {
                if (networks.contains(n)) {
                    shared = true;
                    break;
                }
            }
            if (shared) out.add(p);
        }
        out.sort(java.util.Comparator.comparingLong(p -> p.pos().asLong()));
        return out;
    }

    @Nullable
    public static Reservation reservation(Level level, BlockPos port, long now) {
        Map<BlockPos, Reservation> m = RESERVED.get(level.dimension());
        Reservation r = m == null ? null : m.get(port);
        return r != null && r.until() >= now ? r : null;
    }

    /** Порт свободен для штекера owner: не занят чужой бронью и не сцеплен (сцеплен с ним самим - его). */
    public static boolean freeFor(Level level, PortInfo p, long owner, long now) {
        Reservation r = reservation(level, p.pos(), now);
        if (r != null) return r.owner() == owner;
        return !p.locked();
    }

    /**
     * Порт для штекера owner с серединой в точке from и count портами: прежний (keep), если он ещё годится и свободен
     * для него, иначе ближайший свободный с тем же числом портов, иначе ближайший свободный, иначе ближайший занятый
     * (free = false); null - портов с номером в сетях нет.
     */
    @Nullable
    public static Choice choose(Level level, Set<UUID> networks, int number, @Nullable UUID ship, long owner, Vec from,
                                @Nullable BlockPos keep, long now, int count) {
        List<PortInfo> all = ports(level, networks, number, ship);
        if (all.isEmpty()) return null;
        PortInfo bestFree = null, bestAny = null;
        double dFree = Double.POSITIVE_INFINITY, dAny = Double.POSITIVE_INFINITY;
        for (PortInfo p : all) {
            boolean free = freeFor(level, p, owner, now);
            if (free && p.pos().equals(keep)) return new Choice(p, true);
            double d = p.tip().distanceSqr(from);
            if (p.count() != count) d += 1e12;                           // сначала порты с той же расстановкой
            if (free && d < dFree) {
                dFree = d;
                bestFree = p;
            }
            if (d < dAny) {
                dAny = d;
                bestAny = p;
            }
        }
        return bestFree != null ? new Choice(bestFree, true) : new Choice(bestAny, false);
    }

    /** Занять (продлить) порт за штекером owner; extend - выдвигать порт. */
    public static void reserve(Level level, BlockPos port, long owner, long now, boolean extend) {
        RESERVED.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .put(port.immutable(), new Reservation(owner, now + Docking.RESERVE_TICKS, extend));
    }

    /** Отпустить порт, если он за этим штекером. */
    public static void release(Level level, BlockPos port, long owner) {
        Map<BlockPos, Reservation> m = RESERVED.get(level.dimension());
        if (m != null) m.computeIfPresent(port, (k, r) -> r.owner() == owner ? null : r);
    }

    public static void clear() {
        PORTS.clear();
        RESERVED.clear();
    }
}
