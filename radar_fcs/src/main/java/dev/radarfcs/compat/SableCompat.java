package dev.radarfcs.compat;

import dev.radarfcs.FcsMod;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Физические конструкции Sable / Create Aeronautics через Sable companion (MIT).
 * Companion встроен в Create Radars, поэтому доступен всегда; без Sable он возвращает пустые результаты.
 * Блоки корабля живут в «плот»-координатах: для мировых позиций нужен перевод через позу корабля.
 */
public final class SableCompat {
    private static boolean broken;

    private SableCompat() {}

    /** Корабль: id, имя, мировой центр, мировые границы, скорость (блоков/тик). */
    public record Ship(UUID id, String name, Vec3 center, AABB box, Vec3 velocity, SubLevelAccess access) {
        public double radius() {
            return Math.max(1.0, Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) * 0.5);
        }
    }

    @Nullable
    private static SableCompanion api() {
        if (broken) return null;
        try {
            return SableCompanion.INSTANCE;
        } catch (Throwable t) {
            broken = true;
            FcsMod.LOG.warn("[radar_fcs] Sable companion unavailable: {}", t.toString());
            return null;
        }
    }

    private static void fail(Throwable t) {
        if (!broken) FcsMod.LOG.warn("[radar_fcs] Sable companion call failed, ships disabled: {}", t.toString());
        broken = true;
    }

    /** Корабль, в плоте которого находится блок, или null (блок в обычном мире). */
    @Nullable
    public static SubLevelAccess containing(Level level, BlockPos pos) {
        SableCompanion api = api();
        if (api == null) return null;
        try {
            return api.getContaining(level, (net.minecraft.core.Vec3i) pos);
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    @Nullable
    public static UUID containingId(Level level, BlockPos pos) {
        SubLevelAccess s = containing(level, pos);
        return s == null ? null : s.getUniqueId();
    }

    /** Корабль, на котором стоит или едет сущность. */
    @Nullable
    public static UUID trackingShipId(Entity e) {
        SableCompanion api = api();
        if (api == null) return null;
        try {
            SubLevelAccess s = api.getTrackingOrVehicleSubLevel(e);
            if (s == null) s = api.getContaining(e);
            return s == null ? null : s.getUniqueId();
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    /** Мировая позиция точки (если точка в плоте корабля - через позу корабля). */
    public static Vec3 toWorld(Level level, Vec3 pos) {
        SableCompanion api = api();
        if (api == null) return pos;
        try {
            return api.projectOutOfSubLevel(level, (net.minecraft.core.Position) pos);
        } catch (Throwable t) {
            fail(t);
            return pos;
        }
    }

    /** Направление из плот-координат корабля в мировые. */
    public static Vec3 dirToWorld(@Nullable SubLevelAccess ship, Vec3 dir) {
        if (ship == null) return dir;
        try {
            Quaterniondc q = ship.logicalPose().orientation();
            Vector3d v = q.transform(new Vector3d(dir.x, dir.y, dir.z));
            return new Vec3(v.x, v.y, v.z);
        } catch (Throwable t) {
            fail(t);
            return dir;
        }
    }

    /** Направление из мировых координат в плот-координаты корабля. */
    public static Vec3 dirToLocal(@Nullable SubLevelAccess ship, Vec3 dir) {
        if (ship == null) return dir;
        try {
            Quaterniondc q = ship.logicalPose().orientation();
            Vector3d v = q.transformInverse(new Vector3d(dir.x, dir.y, dir.z));
            return new Vec3(v.x, v.y, v.z);
        } catch (Throwable t) {
            fail(t);
            return dir;
        }
    }

    /** Скорость точки корабля (в плот-координатах), блоков/тик. */
    public static Vec3 pointVelocity(Level level, @Nullable SubLevelAccess ship, Vec3 localPoint) {
        SableCompanion api = api();
        if (api == null || ship == null) return Vec3.ZERO;
        try {
            Vector3d v = api.getVelocity(level, ship, new Vector3d(localPoint.x, localPoint.y, localPoint.z), new Vector3d());
            return new Vec3(v.x / 20.0, v.y / 20.0, v.z / 20.0);
        } catch (Throwable t) {
            fail(t);
            return Vec3.ZERO;
        }
    }

    /** Скорость центра масс корабля, блоков/тик (Sable отдаёт блоки/сек). */
    public static Vec3 velocity(Level level, SubLevelAccess ship) {
        try {
            Vector3dc rp = ship.logicalPose().rotationPoint();
            return pointVelocity(level, ship, new Vec3(rp.x(), rp.y(), rp.z()));
        } catch (Throwable t) {
            fail(t);
            return Vec3.ZERO;
        }
    }

    public static Ship describe(Level level, SubLevelAccess s) {
        BoundingBox3dc b = s.boundingBox();
        AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        String name = s.getName();
        if (name == null || name.isBlank()) name = "ship-" + s.getUniqueId().toString().substring(0, 4);
        return new Ship(s.getUniqueId(), name, box.getCenter(), box, velocity(level, s), s);
    }

    /** Корабли, пересекающие мировую область. */
    public static List<Ship> shipsIn(Level level, AABB area) {
        List<Ship> out = new ArrayList<>();
        SableCompanion api = api();
        if (api == null) return out;
        try {
            for (SubLevelAccess s : api.getAllIntersecting(level, new BoundingBox3d(area))) {
                if (s != null) out.add(describe(level, s));
            }
        } catch (Throwable t) {
            fail(t);
        }
        return out;
    }

    @Nullable
    public static Ship find(Level level, UUID id, Vec3 near, double searchRadius) {
        for (Ship s : shipsIn(level, new AABB(near, near).inflate(searchRadius))) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }
}
