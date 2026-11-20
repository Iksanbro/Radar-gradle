package dev.radarfcs.dock;

import dev.radarfcs.core.Vec;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Маяки орбиты с кодами по измерениям: мировая точка и скорость (маяк на корабле). Маяк выгруженного чанка остаётся с
 * последним положением. Мост с кодом маяка летает вокруг маяка с этим кодом - в его радарной сети маяк быть не обязан.
 */
public final class BeaconRegistry {
    public record Beacon(BlockPos pos, String code, Vec center, Vec vel) {}

    private static final Map<ResourceKey<Level>, Map<BlockPos, Beacon>> BEACONS = new ConcurrentHashMap<>();

    private BeaconRegistry() {}

    /** Код маяка: без пробелов по краям и служебных символов, не длиннее 16. */
    public static String clean(@Nullable String code) {
        if (code == null) return "";
        String s = code.replaceAll("\\p{Cntrl}", "").trim();
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    public static void put(Level level, Beacon b) {
        Map<BlockPos, Beacon> m = BEACONS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        if (b.code().isEmpty()) m.remove(b.pos());
        else m.put(b.pos().immutable(), b);
    }

    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, Beacon> m = BEACONS.get(level.dimension());
        if (m != null) m.remove(pos);
    }

    /** Ближайший к точке near маяк с кодом (без учёта регистра); null - такого нет. */
    @Nullable
    public static Beacon find(Level level, String code, Vec near) {
        Map<BlockPos, Beacon> m = BEACONS.get(level.dimension());
        if (m == null || code.isEmpty()) return null;
        String c = code.toLowerCase(Locale.ROOT);
        Beacon best = null;
        for (Beacon b : m.values()) {
            if (!b.code().toLowerCase(Locale.ROOT).equals(c)) continue;
            if (best == null || b.center().distanceSqr(near) < best.center().distanceSqr(near)) best = b;
        }
        return best;
    }

    public static void clear() {
        BEACONS.clear();
    }
}
