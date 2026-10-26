package dev.radarfcs.radar;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.ContraptionCompat;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.Vec;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Общая модель обнаружения для всех радаров мода:
 * дальность зависит от размера цели, зона обзора задаётся углами, блоки перекрывают луч.
 * Обнаруживаются мобы, игроки, движущиеся контрапшены Create, корабли Sable и (по настройке) снаряды.
 */
public final class RadarScanner {
    private static final int LOS_CACHE_TICKS = 10;
    private static final double MAX_SIZE_FACTOR = 1.5;

    private RadarScanner() {}

    /**
     * @param origin   мировая позиция антенны
     * @param range    дальность для цели размером с игрока
     * @param ttl      сколько живёт отметка без повторного обнаружения, тиков
     * @param ownShip  корабль, на котором стоит радар (не цель)
     * @param self     собственная сущность радара (вращающаяся антенна)
     */
    public record Spec(Vec3 origin, double range, Coverage coverage, int ttl, @Nullable UUID ownShip, @Nullable Entity self) {}

    public static void scan(ServerLevel level, Spec spec, ContactBook book, long now) {
        Vec3 o = spec.origin();
        double reach = spec.range() * (FcsConfig.RADAR_SIZE_AFFECTS_RANGE.get() ? MAX_SIZE_FACTOR : 1.0);
        AABB area = new AABB(o, o).inflate(reach);
        boolean los = FcsConfig.RADAR_LOS.get();
        for (Entity e : level.getEntities((Entity) null, area, RadarScanner::detectable)) {
            if (e == spec.self()) continue;
            AABB bb = e.getBoundingBox();
            Vec3 c = bb.getCenter();
            Vec3 d = c.subtract(o);
            double dist = d.length();
            if (dist < 0.75) continue;
            if (dist > spec.range() * sizeFactor(bb)) continue;
            Vec dv = new Vec(d.x, d.y, d.z);
            if (!spec.coverage().covers(dv.yawDeg(), dv.pitchDeg())) continue;
            if (los && !visible(level, book, e.getUUID(), o, c, bb, now)) continue;
            Track.Kind kind = kindOf(e);
            double radius = kind == Track.Kind.CONTRAPTION
                    ? Math.min(6.0, Math.max(1.0, Math.min(bb.getXsize(), bb.getZsize()) * 0.5))
                    : Math.max(0.35, Math.min(bb.getXsize(), bb.getYsize()) * 0.5);
            book.see(e.getUUID(), kind, e.getName().getString(), c, null, radius, e instanceof Enemy, e, now, spec.ttl());
        }
        for (SableCompat.Ship ship : SableCompat.shipsIn(level, area)) {
            if (ship.id().equals(spec.ownShip())) continue;
            Vec3 c = ship.center();
            Vec3 d = c.subtract(o);
            if (d.length() > spec.range() * sizeFactor(ship.box())) continue;
            Vec dv = new Vec(d.x, d.y, d.z);
            if (!spec.coverage().covers(dv.yawDeg(), dv.pitchDeg())) continue;
            if (los && !visible(level, book, ship.id(), o, c, ship.box(), now)) continue;
            book.see(ship.id(), Track.Kind.SHIP, ship.name(), c, ship.velocity(), ship.radius(), true, null, now, spec.ttl());
        }
        book.expire(now);
    }

    /** Множитель дальности по силуэту цели (радарное уравнение: дальность ~ площадь^(1/4)). */
    public static double sizeFactor(AABB bb) {
        if (!FcsConfig.RADAR_SIZE_AFFECTS_RANGE.get()) return 1.0;
        double area = Math.max(bb.getXsize(), bb.getZsize()) * bb.getYsize();
        return Mth.clamp(Math.pow(area / 1.1, 0.25), 0.6, MAX_SIZE_FACTOR);
    }

    private static boolean visible(ServerLevel level, ContactBook book, UUID id, Vec3 from, Vec3 to, AABB box, long now) {
        Boolean cached = book.cachedLos(id, now, LOS_CACHE_TICKS);
        if (cached != null) return cached;
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        boolean v = hit.getType() == HitResult.Type.MISS || box.inflate(1.0).contains(hit.getLocation());
        book.putLos(id, now, v);
        return v;
    }

    // ------------------------------------------------------------ что видно

    /** Сущность, которую видит радар (без проверки дальности и зоны). */
    public static boolean detectable(Entity e) {
        if (!e.isAlive() || e.isSpectator() || e.isRemoved()) return false;
        if (e instanceof Player) return true;
        if (ContraptionCompat.isContraption(e)) return !ContraptionCompat.isIgnored(e);
        if (e instanceof Projectile) return FcsConfig.TARGET_PROJECTILES.get();
        return e instanceof Mob && !isIgnoredEntity(e);
    }

    public static boolean isIgnoredEntity(Entity e) {
        if (ContraptionCompat.isContraption(e)) return ContraptionCompat.isIgnored(e);
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        return FcsConfig.IGNORED_ENTITIES.get().contains(id);
    }

    public static Track.Kind kindOf(Entity e) {
        if (e instanceof Player) return Track.Kind.PLAYER;
        if (ContraptionCompat.isContraption(e)) return Track.Kind.CONTRAPTION;
        if (e instanceof Projectile) return Track.Kind.PROJECTILE;
        return Track.Kind.MOB;
    }
}
