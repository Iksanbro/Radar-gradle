package dev.radarfcs.compat;

import dev.radarfcs.core.Vec;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Наблюдение за выстрелами. СУО «взводит» наблюдатель перед спуском; снаряд, появившийся у дульного
 * среза по направлению ствола, связывается с пушкой. Дальше каждый тик записывается его скорость.
 * По этим данным вычислитель уточняет начальную скорость, положение дульного среза и модель полёта
 * (тяга ракет, нестандартные снаряды аддонов).
 */
public final class ShotObserver {

    public interface Listener {
        /** Снаряд появился: позиция и начальная скорость (блоков/тик). */
        void onShotSpawned(Entity projectile, Vec3 spawnPos, Vec3 velocity);

        /** Полёт записан: скорость на вылете и в конце каждого тика полёта. */
        void onShotObserved(List<Vec> velocities);

        /** Продолжать ли наблюдение (СУО ещё существует). */
        boolean isListening();
    }

    private record Armed(ResourceKey<Level> dim, Vec3 muzzle, Vec3 dir, long until, Listener listener) {}

    private static final class Watch {
        final Entity entity;
        final Listener listener;
        final List<Vec> velocities = new ArrayList<>();
        final int maxTicks;
        Vec3 lastPos;
        int age;

        Watch(Entity entity, Listener listener, int maxTicks) {
            this.entity = entity;
            this.listener = listener;
            this.maxTicks = maxTicks;
            this.lastPos = entity.position();
            Vec3 v = entity.getDeltaMovement();
            velocities.add(new Vec(v.x, v.y, v.z));
        }
    }

    private static final List<Armed> ARMED = new ArrayList<>();
    private static final List<Watch> WATCHES = new ArrayList<>();
    private static final int MAX_WATCHES = 64;

    private ShotObserver() {}

    /** Ожидать снаряд из этой пушки ближайшие window тиков. */
    public static synchronized void arm(ServerLevel level, Vec3 muzzle, Vec3 dir, int window, Listener listener) {
        ARMED.removeIf(a -> a.listener == listener);
        ARMED.add(new Armed(level.dimension(), muzzle, dir.normalize(), level.getGameTime() + window, listener));
    }

    public static synchronized void disarm(Listener listener) {
        ARMED.removeIf(a -> a.listener == listener);
    }

    public static synchronized boolean isWatching(Listener listener) {
        for (Watch w : WATCHES) if (w.listener == listener) return true;
        return false;
    }

    public static synchronized void onEntityJoin(EntityJoinLevelEvent event) {
        if (ARMED.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (!(e instanceof Projectile)) return;
        Vec3 v = e.getDeltaMovement();
        double speed = v.length();
        if (speed < 0.05) return;
        Vec3 vd = v.scale(1 / speed);
        long now = level.getGameTime();
        Vec3 pos = e.position();
        Armed best = null;
        double bestD = Double.MAX_VALUE;
        for (Armed a : ARMED) {
            if (a.dim != level.dimension() || now > a.until) continue;
            double d = pos.distanceTo(a.muzzle);
            if (d > 4.5 || vd.dot(a.dir) < 0.94) continue;
            if (d < bestD) { bestD = d; best = a; }
        }
        if (best == null) return;
        best.listener.onShotSpawned(e, pos, v);
        boolean already = false;
        for (Watch w : WATCHES) if (w.listener == best.listener) { already = true; break; }
        if (!already && WATCHES.size() < MAX_WATCHES) {
            int life = e instanceof AbstractCannonProjectile ? 200 : 100;
            WATCHES.add(new Watch(e, best.listener, life));
        }
    }

    public static synchronized void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        ARMED.removeIf(a -> now > a.until + 1 || !a.listener.isListening());
        Iterator<Watch> it = WATCHES.iterator();
        while (it.hasNext()) {
            Watch w = it.next();
            if (w.entity.level() != level) continue;
            boolean done = !w.listener.isListening() || w.entity.isRemoved() || w.age++ > w.maxTicks
                    || (w.entity instanceof AbstractCannonProjectile acp && acp.isInGround());
            if (!done) {
                Vec3 p = w.entity.position();
                if (p.distanceToSqr(w.lastPos) > 1e-10) {               // снаряд реально сдвинулся в этом тике
                    Vec3 v = w.entity.getDeltaMovement();
                    w.velocities.add(new Vec(v.x, v.y, v.z));
                    w.lastPos = p;
                }
            }
            if (done) {
                it.remove();
                if (w.listener.isListening() && w.velocities.size() >= 3) w.listener.onShotObserved(w.velocities);
            }
        }
    }

    public static synchronized void clear() {
        ARMED.clear();
        WATCHES.clear();
    }
}
