package dev.radarfcs.fcs;

import dev.radarfcs.compat.CbcBallistics;
import dev.radarfcs.compat.Munitions;
import dev.radarfcs.core.CbcFlightModel;
import dev.radarfcs.core.PathMotion;
import dev.radarfcs.core.ProjectileType;
import dev.radarfcs.core.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Летящие снаряды-цели (перехват): откуда и кем выпущены (свой-чужой) и прогноз их полёта по тикам.
 * Снаряд большой пушки летит по физике CBC (сопротивление и гравитация из его свойств), ракета - по замерам
 * (скорость точная, ускорение - по изменению скорости). Прогноз обрывается ударом о блоки.
 */
public final class ProjectileTracking {
    /**
     * Откуда и кем выпущен снаряд.
     * @param fcs      вычислитель, чья пушка его выпустила (позиция блока), или null
     * @param fcsOwner владелец этого вычислителя (null - неизвестен)
     */
    public record Origin(Vec3 pos, long tick, @Nullable BlockPos fcs, @Nullable UUID fcsOwner, String fcsTeam) {}

    /** Наибольшая длина прогноза, тиков. */
    public static final int MAX_PATH = 300;
    /** Как часто перепроверять удар прогноза о блоки, тиков. */
    private static final int IMPACT_RECHECK = 5;
    /** Ускорение ракеты по замерам доверяется стольким тикам вперёд (дальше скорость постоянна). */
    private static final int ROCKET_ACCEL_TICKS = 20;

    private static final class State {
        long tick = Long.MIN_VALUE;
        PathMotion path;
        Vec3 lastVel;
        long lastVelTick = Long.MIN_VALUE;
        Vec acc = Vec.ZERO;
        long impactChecked = Long.MIN_VALUE;
        /** Игровой тик удара прогноза о блоки (Long.MAX_VALUE - удара нет в пределах прогноза). */
        long impactTick = Long.MAX_VALUE;
    }

    private static final Map<Entity, Origin> ORIGINS = new WeakHashMap<>();
    private static final Map<Entity, State> STATES = new WeakHashMap<>();

    private ProjectileTracking() {}

    /** Снаряд появился: запомнить точку запуска. */
    public static synchronized void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (Munitions.classify(e) == null) return;
        ORIGINS.putIfAbsent(e, new Origin(e.position(), level.getGameTime(), null, null, ""));
    }

    /** Снаряд выпущен пушкой вычислителя (счёт выстрелов): свой для этого вычислителя, его владельца и команды. */
    public static synchronized void markFired(Entity e, BlockPos fcs, @Nullable UUID owner, String team) {
        if (Munitions.classify(e) == null) return;
        Origin o = ORIGINS.get(e);
        long tick = e.level().getGameTime();
        ORIGINS.put(e, new Origin(o != null ? o.pos() : e.position(), o != null ? o.tick() : tick, fcs, owner, team == null ? "" : team));
    }

    @Nullable
    public static synchronized Origin origin(Entity e) {
        return ORIGINS.get(e);
    }

    public static synchronized void clear() {
        ORIGINS.clear();
        STATES.clear();
    }

    /** Прогноз полёта снаряда по тикам от текущего положения (один раз за тик на снаряд). */
    public static synchronized PathMotion path(ServerLevel level, Entity e, ProjectileType type) {
        long now = level.getGameTime();
        State st = STATES.computeIfAbsent(e, k -> new State());
        if (st.tick == now && st.path != null) return st.path;
        Vec3 v = e.getDeltaMovement();
        // ускорение по замерам скорости (ракеты): сглаженное изменение скорости за тик
        if (st.lastVel != null && now > st.lastVelTick) {
            Vec3 dv = v.subtract(st.lastVel).scale(1.0 / (now - st.lastVelTick));
            Vec a = new Vec(dv.x, dv.y, dv.z);
            st.acc = st.lastVelTick == Long.MIN_VALUE ? a : st.acc.scale(0.5).add(a.scale(0.5));
        }
        st.lastVel = v;
        st.lastVelTick = now;

        boolean exact = Munitions.exactBallistics(e, type);
        CbcFlightModel model = exact ? CbcBallistics.fromProjectile(level, (AbstractCannonProjectile) e, v.length(), MAX_PATH,
                CbcFlightModel.ballistic(v.length(), -0.05, 0, false, MAX_PATH)) : null;
        int limit = MAX_PATH;
        if (st.impactTick != Long.MAX_VALUE) limit = (int) Math.max(1, Math.min(MAX_PATH, st.impactTick - now));
        List<Vec> pts = new ArrayList<>(limit + 1);
        Vec p = new Vec(e.getX(), e.getY(), e.getZ()), vel = new Vec(v.x, v.y, v.z);
        pts.add(p);
        double minY = level.getMinBuildHeight() - 16;
        for (int k = 0; k < limit; k++) {
            Vec a;
            if (model != null) {
                double s = vel.length();
                a = s < 1e-9 ? new Vec(0, model.vertical(0), 0)
                        : vel.scale(model.along(0, s) / s).add(new Vec(0, model.vertical(0), 0));
            } else {
                a = k < ROCKET_ACCEL_TICKS ? st.acc : Vec.ZERO;
            }
            p = p.add(vel).addScaled(a, 0.5);
            vel = vel.add(a);
            pts.add(p);
            if (p.y() < minY) break;
        }
        // удар о блоки: перепроверка раз в несколько тиков, только по загруженным чанкам
        if (now - st.impactChecked >= IMPACT_RECHECK) {
            st.impactChecked = now;
            int hit = impactIndex(level, e, pts);
            if (hit >= 0) {
                st.impactTick = now + hit;
                while (pts.size() > hit + 1) pts.remove(pts.size() - 1);
            } else {
                st.impactTick = Long.MAX_VALUE;
            }
        }
        st.path = new PathMotion(pts.toArray(new Vec[0]));
        st.tick = now;
        return st.path;
    }

    /** Номер тика, на котором прогноз упирается в блок (последняя точка заменяется точкой удара), или -1. */
    private static int impactIndex(ServerLevel level, Entity e, List<Vec> pts) {
        for (int k = 0; k + 1 < pts.size(); k++) {
            Vec a = pts.get(k), b = pts.get(k + 1);
            Vec3 from = new Vec3(a.x(), a.y(), a.z()), to = new Vec3(b.x(), b.y(), b.z());
            if (!loaded(level, from) || !loaded(level, to) || !loaded(level, from.add(to).scale(0.5))) return -1;
            var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3 h = hit.getLocation();
                pts.set(k + 1, new Vec(h.x, h.y, h.z));
                return k + 1;
            }
        }
        return -1;
    }

    private static boolean loaded(ServerLevel level, Vec3 p) {
        if (p.y < level.getMinBuildHeight() || p.y >= level.getMaxBuildHeight()) return true;
        return level.hasChunkAt(BlockPos.containing(p));
    }
}
