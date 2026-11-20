package dev.radarfcs.fcs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Один станок - один вычислитель: второй вычислитель рядом не перехватывает управление. */
final class MountClaims {
    private record Key(ResourceKey<Level> dim, BlockPos mount) {}

    private record Claim(BlockPos fcs, long tick) {}

    private static final Map<Key, Claim> CLAIMS = new ConcurrentHashMap<>();
    private static final long EXPIRE = 60;

    private MountClaims() {}

    static boolean claim(Level level, BlockPos mount, BlockPos fcs, long now) {
        Key k = new Key(level.dimension(), mount);
        Claim c = CLAIMS.get(k);
        if (c == null || c.fcs().equals(fcs) || now - c.tick() > EXPIRE || now < c.tick()) {
            CLAIMS.put(k, new Claim(fcs.immutable(), now));
            return true;
        }
        return false;
    }

    static void release(Level level, BlockPos mount, BlockPos fcs) {
        Key k = new Key(level.dimension(), mount);
        Claim c = CLAIMS.get(k);
        if (c != null && c.fcs().equals(fcs)) CLAIMS.remove(k);
    }

    static void clear() {
        CLAIMS.clear();
    }
}
