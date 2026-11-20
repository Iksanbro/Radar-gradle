package dev.radarfcs.dock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Пристыкованные корабли: мост автопилота отмечает свой корабль каждый тик, пока он пристыкован. Вычислители не
 * наводят и не стреляют пушками на отмеченных кораблях (статус «пристыкован»).
 */
public final class DockHold {
    private static final Map<ResourceKey<Level>, Map<UUID, Long>> HELD = new ConcurrentHashMap<>();

    private DockHold() {}

    /** Корабль пристыкован ещё пару тиков. */
    public static void mark(Level level, UUID ship, long now) {
        HELD.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(ship, now + 3);
    }

    public static void release(Level level, UUID ship) {
        Map<UUID, Long> m = HELD.get(level.dimension());
        if (m != null) m.remove(ship);
    }

    /** Есть ли в измерении пристыкованные корабли (чтобы не искать корабль каждой пушки зря). */
    public static boolean any(Level level, long now) {
        Map<UUID, Long> m = HELD.get(level.dimension());
        if (m == null || m.isEmpty()) return false;
        m.values().removeIf(until -> until < now);
        return !m.isEmpty();
    }

    public static boolean held(Level level, @Nullable UUID ship, long now) {
        if (ship == null) return false;
        Map<UUID, Long> m = HELD.get(level.dimension());
        Long until = m == null ? null : m.get(ship);
        return until != null && until >= now;
    }

    public static void clear() {
        HELD.clear();
    }
}
