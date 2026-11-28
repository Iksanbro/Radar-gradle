package dev.radarfcs.compat.rocket;

import com.example.cbcenhancedshells.rocket.RocketEntity;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.radarfcs.FcsMod;
import dev.radarfcs.compat.ContraptionCompat;
import dev.radarfcs.compat.Munitions;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.EngageSettings.Category;
import dev.radarfcs.core.ProjectileType;
import dev.radarfcs.core.Vec;
import dev.radarfcs.fcs.NetworkTargets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;

/**
 * Наведение ракет CBC Enchanced Shells с нашими носами. Ракета сама доворачивает к точке {@code setGuidanceTarget}
 * (штатное наведение мода, после 20 тиков полёта); здесь каждый тик ей подставляется упреждённая точка цели:
 * позиция + скорость x время подлёта. Цель - одна на весь полёт: программируемый нос захватывает цель сети при пуске,
 * тепловая головка - первую подходящую цель после пуска; пропала из виду - ракета летит в её последнюю точку. Топливо
 * кончилось - наведение снимается, ракета падает по баллистике.
 * <p>
 * Настройки носа ракета получает при пуске: со стартовой площадки - нос снимается перед появлением ракеты
 * ({@link #noteRemoved}), с ракетного станка - нос ещё лежит в постройке станка.
 */
public final class RocketGuidance {
    /** Тепловая головка начинает поиск с этого тика полёта (ракета отошла от площадки). */
    static final int SEEK_FROM = 6;
    static final double SEEK_RANGE = 192;
    /** Угол обзора от направления полёта: почти вся передняя полусфера и немного назад - ракета стартует отвесно. */
    static final double SEEK_HALF_ANGLE = 110;
    /** Цель не видна дольше - ракета летит в последнюю точку без упреждения. */
    static final int LOST_TICKS = 40;
    /** Ракета рулит к ориентиру только после этого тика полёта (штатно у CBC Enchanced Shells). */
    static final int ARM_TICKS = 20;
    /** Поворот за тик не круче (смесь 0.9/0.1 даёт не больше 6.4°). */
    static final double MAX_TURN_DEG = 6.0;
    /** Кассетной ракете ближе к точке встречи - ориентир прямо на неё (раскрытие у ориентира). */
    static final double TERMINAL = 35;

    private record Pending(ResourceKey<Level> dim, BlockPos pos, long tick, NoseData data) {}

    private static final List<Pending> PENDING = new ArrayList<>();
    private static final Map<ResourceKey<Level>, List<Guided>> ACTIVE = new HashMap<>();

    /** Ракета под нашим наведением. */
    public static final class Guided {
        private final RocketEntity rocket;
        private final NoseData data;
        @Nullable private String lock;
        @Nullable private String lockName;
        @Nullable private Vec3 lastPos;
        private Vec3 lastVel = Vec3.ZERO;
        private long lastSeen;
        @Nullable private Vec3 aim;

        Guided(RocketEntity rocket, NoseData data) {
            this.rocket = rocket;
            this.data = data;
        }

        public Entity rocket() {
            return rocket;
        }

        public NoseData data() {
            return data;
        }

        /** Захваченная цель (UUID сущности или корабля строкой), null - ещё не захвачена. */
        @Nullable
        public String lock() {
            return lock;
        }

        /** Точка, к которой ракета наводится в этот тик (с упреждением). */
        @Nullable
        public Vec3 aim() {
            return aim;
        }
    }

    private RocketGuidance() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, RocketGuidance::onJoin);
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Pre.class, RocketGuidance::onTick);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> {
            PENDING.clear();
            ACTIVE.clear();
            RocketSalvo.clear();
        });
    }

    /** Наводимые ракеты измерения (игровые тесты). */
    public static List<Guided> guided(Level level) {
        return List.copyOf(ACTIVE.getOrDefault(level.dimension(), List.of()));
    }

    /** Нос снят со стенда (площадка уводит стек в ракету или нос сломали): его настройки - на этот тик. */
    static void noteRemoved(Level level, BlockPos pos, NoseData data) {
        long now = level.getGameTime();
        PENDING.removeIf(p -> now - p.tick() > 1);
        PENDING.add(new Pending(level.dimension(), pos.immutable(), now, data));
    }

    // ------------------------------------------------------------- пуск

    private static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk() || !(e.getEntity() instanceof RocketEntity rocket)
                || !(e.getLevel() instanceof ServerLevel level)) return;
        NoseData.Kind kind = kindOf(rocket);
        if (kind == null) return;
        NoseData data = takePending(level, rocket, kind);
        if (data == null) data = fromMount(level, rocket, kind);
        if (data == null && kind == NoseData.Kind.HEAT) data = new NoseData(kind, HeatNoseBlockEntity.DEFAULT, null, null, null);
        if (data == null) return;
        Guided g = new Guided(rocket, data);
        if (kind == NoseData.Kind.NETWORK) {
            // цель - захваченная носом при пуске, иначе цель сети сейчас; потом не меняется
            NetworkTargets.Target t = NetworkTargets.of(level, data.network(), new Vec(rocket.getX(), rocket.getY(), rocket.getZ()));
            String lock = data.lock() != null ? data.lock() : t == null ? null : t.id();
            if (lock == null) {
                FcsMod.LOG.info("[radar_fcs] network nose rocket launched without a network target - unguided");
                return;
            }
            g.lock = lock;
            if (t != null && t.id().equals(lock)) {
                g.lockName = t.name();
                g.lastPos = vec3(t.pos());
                g.lastVel = vec3(t.vel());
                g.lastSeen = level.getGameTime();
            }
        }
        ACTIVE.computeIfAbsent(level.dimension(), k -> new ArrayList<>()).add(g);
        FcsMod.LOG.info("[radar_fcs] guided rocket {} ({}) launched, target {}", rocket.getId(), kind, g.lock == null ? "-" : g.lock);
    }

    @Nullable
    private static NoseData.Kind kindOf(RocketEntity rocket) {
        for (var st : rocket.getStackedBlocks()) {
            if (st.getBlock() instanceof HeatNoseBlock) return NoseData.Kind.HEAT;
            if (st.getBlock() instanceof NetNoseBlock) return NoseData.Kind.NETWORK;
        }
        return null;
    }

    /** Нос, снятый площадкой в этот тик рядом с ракетой (площадка - в начале стека, нос - в конце). */
    @Nullable
    private static NoseData takePending(ServerLevel level, RocketEntity rocket, NoseData.Kind kind) {
        long now = level.getGameTime();
        Pending best = null;
        double bestD = 8 * 8;
        for (Pending p : PENDING) {
            if (p.dim() != level.dimension() || now - p.tick() > 1 || p.data().kind() != kind) continue;
            double d = SableCompat.toWorld(level, Vec3.atCenterOf(p.pos())).distanceToSqr(rocket.position());
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best == null) return null;
        PENDING.remove(best);
        return best.data();
    }

    /** Пуск с ракетного станка: нос ещё в постройке станка (блоки станка очищаются после появления ракеты). */
    @Nullable
    private static NoseData fromMount(ServerLevel level, RocketEntity rocket, NoseData.Kind kind) {
        for (AbstractContraptionEntity ace : level.getEntitiesOfClass(AbstractContraptionEntity.class, rocket.getBoundingBox().inflate(16))) {
            if (!(ace.getContraption() instanceof AbstractMountedCannonContraption mc)) continue;
            UUID ship = SableCompat.trackingShipId(ace);
            for (BlockEntity be : mc.presentBlockEntities.values()) {
                if (kind == NoseData.Kind.HEAT && be instanceof HeatNoseBlockEntity h) return new NoseData(kind, h.categories(), null, null, ship);
                if (kind == NoseData.Kind.NETWORK && be instanceof NetNoseBlockEntity n) return new NoseData(kind, 0, n.network(), null, ship);
            }
        }
        return null;
    }

    // ------------------------------------------------------------- полёт

    private static void onTick(LevelTickEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        List<Guided> list = ACTIVE.get(level.dimension());
        if (list == null || list.isEmpty()) return;
        long now = level.getGameTime();
        for (Iterator<Guided> it = list.iterator(); it.hasNext(); ) {
            Guided g = it.next();
            if (g.rocket.isRemoved()) {
                it.remove();
                continue;
            }
            // топливо кончилось - наведение снято: ракета летит по баллистике и падает (а не наматывает дуги)
            if (g.rocket.getThrustRemaining() <= 0 && g.rocket.tickCount > SEEK_FROM) {
                g.rocket.setGuidanceTarget(null);
                it.remove();
                continue;
            }
            steer(level, g, now);
        }
    }

    private static void steer(ServerLevel level, Guided g, long now) {
        if (g.lock == null) {
            if (g.data.kind() != NoseData.Kind.HEAT || g.rocket.tickCount < SEEK_FROM) return;
            seek(level, g, now);
            if (g.lock == null) return;
        }
        Vec3[] r = resolve(level, g);
        if (r != null) {
            g.lastPos = r[0];
            g.lastVel = r[1];
            g.lastSeen = now;
        } else if (now - g.lastSeen > LOST_TICKS) {
            g.lastVel = Vec3.ZERO;                                          // цель пропала - в её последнюю точку
        }
        if (g.lastPos == null) return;
        Vec3 p = g.rocket.position();
        double speed = Math.max(1.5, g.rocket.getDeltaMovement().length());
        Vec3 aim = g.lastPos;
        for (int i = 0; i < 3; i++) {
            double t = Math.min(100, aim.distanceTo(p) / speed);
            aim = g.lastPos.add(g.lastVel.scale(t));
        }
        g.aim = aim;
        g.rocket.setGuidanceTarget(BlockPos.containing(steerPoint(g.rocket, p, aim)));
    }

    /**
     * Точка-ориентир для штатного наведения ракеты. Ракета сама каждый тик (после {@link #ARM_TICKS}) поворачивает
     * направление полёта на смесь 0.9 x текущее + 0.1 x на ориентир - с ориентиром прямо на цель она доворачивает
     * медленно (на 10 % угла за тик) и на быстрой ракете проскакивает цель. Здесь ориентир подбирается так, чтобы смесь
     * дала нужное направление: на точку встречи, но не круче {@link #MAX_TURN_DEG} за тик (предел смеси - 6.4°).
     * Кассетной ракете ближе {@link #TERMINAL} к точке встречи - сама точка: она раскрывается у ориентира.
     */
    static Vec3 steerPoint(Entity rocket, Vec3 p, Vec3 aim) {
        Vec3 v = rocket.getDeltaMovement();
        Vec3 to = aim.subtract(p);
        double dist = to.length();
        if (rocket.tickCount <= ARM_TICKS || v.lengthSqr() < 0.0025) return aim;
        if (dist < TERMINAL && cluster(rocket)) return aim;
        Vec3 vd = v.normalize(), nd = to.scale(1 / dist);
        double cos = Math.max(-1, Math.min(1, vd.dot(nd)));
        double max = Math.toRadians(MAX_TURN_DEG);
        Vec3 n = nd;
        if (Math.acos(cos) > max) {
            Vec3 perp = nd.subtract(vd.scale(cos));
            if (perp.lengthSqr() < 1e-9) perp = vd.cross(new Vec3(0, 1, 0)).lengthSqr() > 1e-6 ? vd.cross(new Vec3(0, 1, 0)) : new Vec3(1, 0, 0);
            n = vd.scale(Math.cos(max)).add(perp.normalize().scale(Math.sin(max)));
        }
        // 0.9 vd + 0.1 d = k n, |d| = 1  ->  k = 0.9 c + sqrt(0.81 c^2 - 0.8), c = n . vd
        double c = n.dot(vd);
        double disc = 0.81 * c * c - 0.80;
        if (disc < 0) return aim;
        double k = 0.9 * c + Math.sqrt(disc);
        Vec3 d = n.scale(k).subtract(vd.scale(0.9)).scale(10);
        if (d.lengthSqr() < 1e-6) return aim;
        return p.add(d.normalize().scale(1000));
    }

    /** Позиция (центр) и скорость (бл/тик) захваченной цели; null - не видна (умерла, выгружена, не найдена). */
    @Nullable
    private static Vec3[] resolve(ServerLevel level, Guided g) {
        UUID id = uuid(g.lock);
        if (id == null) return null;
        Entity e = level.getEntity(id);
        if (e != null) {
            if (!e.isAlive() || e.isRemoved()) return null;
            Vec3 c = SableCompat.toWorld(level, e.getBoundingBox().getCenter());
            return new Vec3[]{c, e.position().subtract(e.xo, e.yo, e.zo)};
        }
        SableCompat.Ship s = SableCompat.find(level, id, g.lastPos != null ? g.lastPos : g.rocket.position(), 256);
        return s == null ? null : new Vec3[]{s.center(), s.velocity()};
    }

    /** Тепловая головка: подходящая цель в обзоре и прямой видимости - ближе к оси полёта и ближе по дальности. */
    private static void seek(ServerLevel level, Guided g, long now) {
        Vec3 p = g.rocket.position();
        Vec3 v = g.rocket.getDeltaMovement();
        Vec3 fwd = v.lengthSqr() > 1e-4 ? v.normalize() : new Vec3(0, 1, 0);
        int mask = g.data.categories();
        UUID own = g.data.ownShip();
        AABB box = new AABB(p, p).inflate(SEEK_RANGE);
        double bestScore = Double.POSITIVE_INFINITY;
        String best = null, bestName = null;
        Vec3 bestPos = null;
        for (Entity e : level.getEntities(g.rocket, box, x -> true)) {
            Category c = heatCategory(e);
            if (c == null || (mask & HeatNoseBlockEntity.bit(c)) == 0) continue;
            if (own != null && own.equals(SableCompat.trackingShipId(e))) continue;      // на своём корабле
            Vec3 tc = e.getBoundingBox().getCenter();
            double s = score(level, p, fwd, tc);
            if (s < bestScore) {
                bestScore = s;
                best = e.getUUID().toString();
                bestName = e.getName().getString();
                bestPos = tc;
            }
        }
        if ((mask & HeatNoseBlockEntity.bit(Category.SHIPS)) != 0) {
            for (SableCompat.Ship ship : SableCompat.shipsIn(level, box)) {
                if (ship.id().equals(own)) continue;
                double s = score(level, p, fwd, ship.center());
                if (s < bestScore) {
                    bestScore = s;
                    best = ship.id().toString();
                    bestName = ship.name();
                    bestPos = ship.center();
                }
            }
        }
        if (best == null) return;
        g.lock = best;
        g.lockName = bestName;
        g.lastPos = bestPos;
        g.lastSeen = now;
        FcsMod.LOG.info("[radar_fcs] heat seeker rocket {} locked on {} ({})", g.rocket.getId(), bestName, best);
    }

    /** Оценка цели: угол от оси полёта (градусы) + 0.1 x дальность; вне обзора или за препятствием - бесконечность. */
    private static double score(ServerLevel level, Vec3 p, Vec3 fwd, Vec3 tc) {
        Vec3 d = tc.subtract(p);
        double dist = d.length();
        if (dist < 1 || dist > SEEK_RANGE) return Double.POSITIVE_INFINITY;
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, fwd.dot(d.scale(1 / dist))))));
        if (angle > SEEK_HALF_ANGLE) return Double.POSITIVE_INFINITY;
        HitResult hit = SableCompat.plain(level).clip(new ClipContext(p, tc, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(p) < dist - 1.5) return Double.POSITIVE_INFINITY;
        return angle + dist * 0.1;
    }

    /** Тип цели для тепловой головки (типы целей вычислителя); null - не цель (мирные мобы, служебные сущности). */
    @Nullable
    static Category heatCategory(Entity e) {
        if (e.isSpectator() || !e.isAlive() || e.isRemoved()) return null;
        if (e instanceof Player pl) return pl.isCreative() ? null : Category.PLAYERS;
        if (e instanceof Mob) return e instanceof Enemy ? Category.MOBS : null;
        if (ContraptionCompat.isContraption(e) || e instanceof VehicleEntity) return Category.ENTITIES;
        ProjectileType t = Munitions.classify(e);
        if (t == ProjectileType.SHELL) return Category.SHELLS;
        if (t == ProjectileType.ROCKET) return Category.ROCKETS;
        return null;
    }

    /** Полезная нагрузка - кассетный снаряд (по имени класса: он есть не во всех версиях мода). */
    private static boolean cluster(Entity rocket) {
        if (!(rocket instanceof RocketEntity r)) return false;
        for (var st : r.getStackedBlocks()) if (st.getBlock().getClass().getSimpleName().equals("ClusterShellBlock")) return true;
        return false;
    }

    @Nullable
    private static UUID uuid(@Nullable String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Vec3 vec3(Vec v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
