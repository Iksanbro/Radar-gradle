package dev.radarfcs.fcs;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.compat.CbcBallistics;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.compat.ShotObserver;
import dev.radarfcs.core.Aim;
import dev.radarfcs.core.BallisticSolver;
import dev.radarfcs.core.FlightModel;
import dev.radarfcs.core.TargetMotion;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.TrackTable;
import dev.radarfcs.core.Vec;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/** Наведение и огонь одной пушки. */
public final class FcsControl {
    private static final DustParticleOptions MARK_TRACKED = new DustParticleOptions(new Vector3f(1.0f, 0.65f, 0.1f), 1.2f);
    private static final DustParticleOptions MARK_ENGAGED = new DustParticleOptions(new Vector3f(1.0f, 0.05f, 0.05f), 1.8f);
    private static final int SAFETY_CACHE_TICKS = 3;

    private FcsControl() {}

    /**
     * @param designated id цели, назначенной с радарного монитора канала (или null)
     */
    public static void control(ServerLevel level, FireControlBlockEntity fcs, MountState m, TargetSensor sensor, long now,
                               boolean hold, int channel, Set<UUID> platform, @Nullable String designated) {
        CbcMount d = m.mount;
        if (d == null || !d.isValid()) {
            m.status = Status.LOST;
            return;
        }
        if (FcsConfig.YIELD_TO_RADAR_CONTROLLERS.get() && d.hasRadarControllers()) {
            yieldMount(level, fcs, m);
            m.status = Status.RADAR_CONTROLLED;
            return;
        }
        if (!MountClaims.claim(level, m.pos, fcs.getBlockPos(), now)) {
            if (m.locked || m.triggerOn) releaseHardware(m);
            m.status = Status.OTHER_FCS;
            return;
        }
        m.claimed = true;
        if (!d.isAssembled()) {
            setTrigger(m, false);
            m.cmdYaw = Double.NaN;
            if (FcsConfig.AUTO_ASSEMBLE.get() && now - m.lastAssembleTry >= 40) {
                m.lastAssembleTry = now;
                d.assemble();
                m.note = d.assemblyError();
            }
            m.status = Status.NOT_ASSEMBLED;
            return;
        }
        m.note = null;
        if (!d.canControl()) {
            releaseHardware(m);
            m.status = Status.MANUAL;
            return;
        }
        CbcMount.Kind kind = d.kind();
        if (!m.locked) {
            d.setDriveLock(true);
            m.locked = true;
        }
        if (Double.isNaN(m.cmdYaw)) {
            m.cmdYaw = d.yaw();
            m.cmdPitch = d.pitch();
        }
        if (m.estimate == null || now - m.estimateTick >= 20) m.setEstimate(CbcBallistics.estimate(level, d), now);

        // система координат станка: на корабле - через позу корабля
        SubLevelAccess ship = SableCompat.containing(level, d.pos());
        Vec3 pivotLocal = d.localPivot();
        Vec3 pivotW = SableCompat.toWorld(level, pivotLocal);
        Vec pivot = new Vec(pivotW.x, pivotW.y, pivotW.z);
        Vec platformVel = Vec.ZERO;
        if (ship != null && FcsConfig.INHERIT_PLATFORM_VELOCITY.get()) {
            Vec3 pv = SableCompat.pointVelocity(level, ship, pivotLocal);
            platformVel = new Vec(pv.x, pv.y, pv.z);
        }

        if (kind == CbcMount.Kind.UNSUPPORTED) {
            setTrigger(m, false);
            m.status = Status.UNSUPPORTED;
            return;
        }
        List<Track> active = sensor.active();
        if (active.isEmpty()) {
            idle(m, Status.IDLE);
            syncIfNeeded(d, m, now);
            return;
        }

        FlightModel model = m.flightModel();
        double muzzleDist = m.muzzleDistance(d.muzzleDistance());
        BallisticSolver.Muzzle muzzle = BallisticSolver.Muzzle.offset(pivot, muzzleDist);
        boolean discrete = FcsConfig.DISCRETE_INTERCEPT.get();
        BallisticSolver solver = new BallisticSolver(-d.maxDepression(), d.maxElevation(), FcsConfig.HIGH_ARC.get())
                .discrete(discrete);

        // приоритет - ближайшая к этой пушке цель; текущую держим, пока новая не заметно ближе
        List<Track> cands = TrackTable.sortedByDistance(active, pivot);
        if (m.targetId != null) {
            Track cur = sensor.get(m.targetId);
            if (cur != null && cur.active && cands.indexOf(cur) > 0
                    && cands.get(0).pos.distance(pivot) >= cur.pos.distance(pivot) * FcsConfig.TARGET_SWITCH_RATIO.get()) {
                cands.remove(cur);
                cands.add(0, cur);
            }
        }
        // цель, назначенная с монитора, - в приоритете, если по ней есть решение
        if (designated != null) {
            Track des = sensor.get(designated);
            if (des != null && des.active && cands.remove(des)) cands.add(0, des);
        }
        // Команда станку исполнится к следующему тику: решаем для снаряда, вылетающего через тик.
        BallisticSolver.Solution sol = null;
        Track target = null;
        TargetMotion motion = null;
        double age = 0;
        for (int i = 0; i < Math.min(3, cands.size()); i++) {
            Track t = cands.get(i);
            double horizon = FcsConfig.USE_ACCELERATION.get() && !t.grounded ? FcsConfig.ACCEL_HORIZON.get() : 0;
            TargetMotion mo = t.motion(horizon);
            if (platformVel.lengthSqr() > 0) mo = new TargetMotion(mo.pos(), mo.vel().sub(platformVel), mo.acc(), mo.grounded(), mo.accelHorizon());
            double a = (now - t.lastTick) + FcsConfig.LATENCY.get();
            double hint = t.id.equals(m.targetId) ? m.hintPitch : Double.NaN;
            sol = solver.solve(muzzle, mo, a + 1, model, FcsConfig.LEAD_ITERATIONS.get(), hint, FcsConfig.MAX_RANGE.get());
            if (sol != null) {
                target = t;
                motion = mo;
                age = a;
                break;
            }
        }
        if (sol == null) {
            m.targetId = null;
            m.targetName = null;
            m.hintPitch = Double.NaN;
            idle(m, Status.NO_SOLUTION);
            syncIfNeeded(d, m, now);
            return;
        }
        if (!target.id.equals(m.targetId)) {
            m.safetyTick = Long.MIN_VALUE / 2;
            if (kind == CbcMount.Kind.RAPID) setTrigger(m, false);
        }
        m.targetId = target.id;
        m.targetName = target.name;
        m.hintPitch = Math.toRadians(sol.pitchDeg());
        m.flightTicks = sol.flightTicks();
        m.distance = sol.aimPoint().distance(sol.muzzle());

        // желаемые углы станка
        Vec3 wantW = new Vec3(sol.direction().x(), sol.direction().y(), sol.direction().z());
        Vec3 wantL = SableCompat.dirToLocal(ship, wantW);
        Vec wl = new Vec(wantL.x, wantL.y, wantL.z);
        double wantYaw = Aim.wrap180(wl.yawDeg() + FcsConfig.YAW_OFFSET.get());
        double wantPitch = wl.pitchDeg() * (FcsConfig.INVERT_PITCH.get() ? -1 : 1);
        double lo = -d.maxDepression(), hi = d.maxElevation();
        boolean outOfArc = wantPitch < lo - 0.5 || wantPitch > hi + 0.5;
        wantPitch = Aim.clamp(wantPitch, lo, hi);

        // скорость поворота: от валов станка (как в CBC) или без кинетики
        double slewYaw, slewPitch;
        if (FcsConfig.REQUIRE_ROTATION.get()) {
            double k = FcsConfig.SHAFT_SPEED.get();
            slewYaw = d.shaftYawRate() * k;
            slewPitch = d.shaftPitchRate() * k;
        } else {
            slewYaw = slewPitch = FcsConfig.SLEW.get();
        }
        double db = FcsConfig.DEADBAND.get();
        boolean noPower = (slewYaw <= 0 && Math.abs(Aim.wrap180(wantYaw - m.cmdYaw)) > db)
                || (slewPitch <= 0 && Math.abs(wantPitch - m.cmdPitch) > db);
        double ny = Aim.wrap180(Aim.stepAngle(m.cmdYaw, wantYaw, slewYaw, db));
        double np = Aim.stepLinear(m.cmdPitch, wantPitch, slewPitch, db);
        if (ny != m.cmdYaw || np != m.cmdPitch) {
            m.cmdYaw = ny;
            m.cmdPitch = np;
            d.setAngles(Aim.norm360(ny), np);
            m.syncPending = true;
        }
        syncIfNeeded(d, m, now);

        // Проверка выстрела фактическим положением ствола, которое возьмёт CBC:
        // большая пушка стреляет сразу - по положению на начало тика, снаряд появляется сейчас;
        // автопушка стреляет в следующем тике - по только что выставленным углам, снаряд на тик позже.
        boolean big = kind != CbcMount.Kind.RAPID;
        Vec3 shotL = d.localBarrelDirection(big ? 0.0F : 1.0F);
        Vec3 shotW = shotL == null ? null : SableCompat.dirToWorld(ship, shotL);
        double allowed = Math.max(target.hitRadius * 0.8 + 0.2,
                m.distance * Math.tan(Math.toRadians(FcsConfig.FIRE_TOLERANCE.get())));
        if (!big) allowed += FcsConfig.RAPID_SPREAD.get();
        double missDist = Double.POSITIVE_INFINITY;
        if (shotW != null) {
            Vec sd = new Vec(shotW.x, shotW.y, shotW.z);
            Vec launch = muzzle.at(sd.normalize());
            int horizonTicks = (int) Math.ceil(sol.flightTicks() * 1.5) + 20;
            missDist = BallisticSolver.miss(launch, sd, model, motion, big ? age : age + 1, horizonTicks, discrete).distance();
            m.aimError = sd.angleTo(sol.direction());
        } else {
            m.aimError = 180;
        }
        m.predictedMiss = missDist;
        m.tolerance = allowed;
        // очередь автопушки не рвём на мелких колебаниях: продолжаем, пока промах меньше удвоенного порога
        boolean onTarget = missDist <= allowed || (!big && m.triggerOn && missDist <= allowed * 2);
        // молодой трек или резкий манёвр: оценка движения ненадёжна - наводимся, но не стреляем
        if (target.observedSpan() < FcsConfig.FIRE_MIN_TRACK.get()) onTarget = false;
        if (target.residual > Math.max(Track.MANEUVER_RESIDUAL, target.hitRadius * 0.25)) onTarget = false;
        boolean aligned = onTarget && !outOfArc;
        boolean loaded = m.estimate == null || m.estimate.loaded();

        FireSafety.Verdict verdict = FireSafety.Verdict.CLEAR;
        if (aligned) {
            if (now - m.safetyTick >= SAFETY_CACHE_TICKS || !target.id.equals(m.safetyTarget)) {
                List<Vec> path = new ArrayList<>();
                BallisticSolver.trajectory(sol, model, 2, path::add);
                m.safety = FireSafety.check(level, path, sensor.all(), target, channel, platform);
                m.safetyTick = now;
                m.safetyTarget = target.id;
            }
            verdict = m.safety;
        }
        boolean fire = aligned && loaded && verdict == FireSafety.Verdict.CLEAR && !hold;
        boolean wasOn = m.triggerOn;
        long lastFireBefore = m.lastFire;
        fireControl(level, d, m, kind, fire, pivotW, shotW, muzzleDist, now);
        boolean shot = big ? m.lastFire != lastFireBefore : m.triggerOn && !wasOn;
        if (FcsConfig.LOG_SHOTS.get() && shot) {
            FcsMod.LOG.info("[radar_fcs] FIRE {} at {} pos={} vel={} acc={} res={} age={} aim={} ttf={} pitch={} yaw={} err={} miss={} allowed={} speed={} muzzle={}",
                    d.typeName(), target.name, target.pos, target.vel, target.acc, String.format("%.3f", target.residual),
                    now - target.lastTick,
                    sol.aimPoint(), String.format("%.2f", sol.flightTicks()), String.format("%.2f", sol.pitchDeg()),
                    String.format("%.2f", sol.yawDeg()), String.format("%.3f", m.aimError), String.format("%.2f", missDist),
                    String.format("%.2f", allowed), String.format("%.3f", model.muzzleSpeed()), String.format("%.2f", muzzleDist));
        }

        if (outOfArc) m.status = Status.OUT_OF_ARC;
        else if (!aligned && noPower) m.status = Status.NO_POWER;
        else if (!aligned) m.status = Status.AIMING;
        else if (verdict == FireSafety.Verdict.FRIENDLY) m.status = Status.BLOCKED_FRIENDLY;
        else if (verdict == FireSafety.Verdict.TERRAIN) m.status = Status.BLOCKED_TERRAIN;
        else if (!loaded) m.status = Status.NO_AMMO;
        else if (hold) m.status = Status.HOLD;
        else m.status = Status.FIRE;
    }

    private static void fireControl(ServerLevel level, CbcMount d, MountState m, CbcMount.Kind kind, boolean fire,
                                    Vec3 pivotW, Vec3 barrelW, double muzzleDist, long now) {
        boolean learn = FcsConfig.LEARN_FROM_SHOTS.get() && barrelW != null && m.estimate != null;
        if (kind == CbcMount.Kind.RAPID) {
            if (fire) {
                if (learn && !ShotObserver.isWatching(m) && (m.correction.shots() < 5 || now - m.lastLearnArm > 100)) {
                    m.prepareShot(m.estimate.model(), pivotW, barrelW);
                    ShotObserver.arm(level, pivotW.add(barrelW.scale(muzzleDist)), barrelW, 6, m);
                    m.lastLearnArm = now;
                }
                if (!m.triggerOn || !d.firePowered()) {
                    d.setFire(true, FcsConfig.RAPID_FIRE_POWER.get());
                    m.triggerOn = true;
                }
                m.lastFire = now;
            } else {
                setTrigger(m, false);
            }
            return;
        }
        // большая пушка: импульс огня (фронт сигнала), затем сброс
        if (m.triggerOn && now - m.lastFire >= 2) setTrigger(m, false);
        if (fire && !m.triggerOn && now - m.lastFire >= FcsConfig.BIG_PULSE_INTERVAL.get()) {
            if (learn) {
                m.prepareShot(m.estimate.model(), pivotW, barrelW);
                ShotObserver.arm(level, pivotW.add(barrelW.scale(muzzleDist)), barrelW, 3, m);
            }
            d.setFire(true, 15);
            m.triggerOn = true;
            m.lastFire = now;
            m.estimateTick = Long.MIN_VALUE / 2;          // после выстрела пересчитать заряжание
        }
    }

    private static void setTrigger(MountState m, boolean on) {
        if (m.triggerOn == on) return;
        if (m.mount != null && m.mount.isValid()) m.mount.setFire(on, on ? 15 : 0);
        m.triggerOn = on;
    }

    private static void idle(MountState m, Status s) {
        setTrigger(m, false);
        m.status = s;
        m.aimError = Double.NaN;
        if (s == Status.IDLE) {
            m.targetId = null;
            m.targetName = null;
        }
    }

    private static void syncIfNeeded(CbcMount d, MountState m, long now) {
        if (m.syncPending && now - m.lastSync >= 2) {
            d.sync();
            m.lastSync = now;
            m.syncPending = false;
        }
    }

    /** Прекратить огонь (нет радара и т.п.), пушку не отпускать. */
    public static void cease(ServerLevel level, MountState m, Status status) {
        setTrigger(m, false);
        m.status = status;
        m.targetId = null;
        m.targetName = null;
    }

    /** Отпустить станок навсегда (станок отвязан или вычислитель убран). */
    public static void release(ServerLevel level, FireControlBlockEntity fcs, MountState m) {
        yieldMount(level, fcs, m);
        m.stopListening();
    }

    /** Временно отдать станок (им управляют контроллеры Create Radars и т.п.). */
    private static void yieldMount(ServerLevel level, FireControlBlockEntity fcs, MountState m) {
        releaseHardware(m);
        if (m.claimed) MountClaims.release(level, m.pos, fcs.getBlockPos());
        m.claimed = false;
    }

    private static void releaseHardware(MountState m) {
        CbcMount d = m.mount;
        if (d != null && d.isValid()) {
            if (m.triggerOn) d.setFire(false, 0);
            if (m.locked) d.setDriveLock(false);
        }
        m.triggerOn = false;
        m.locked = false;
        m.cmdYaw = Double.NaN;
    }

    public static void clearClaims() {
        MountClaims.clear();
    }

    // ------------------------------------------------------------ отображение

    /** Свечение и отметки частицами над активными целями. Свои и неактивные цели не отмечаются. */
    public static void visuals(ServerLevel level, TargetSensor sensor, Collection<MountState> mounts, Vec origin) {
        boolean glow = FcsConfig.GLOW_TARGETS.get(), mark = FcsConfig.MARK_TARGETS.get();
        if (!glow && !mark) return;
        Set<String> engaged = new HashSet<>();
        for (MountState m : mounts) {
            if (m.targetId != null && (m.status == Status.FIRE || m.status == Status.AIMING || m.status == Status.HOLD))
                engaged.add(m.targetId);
        }
        double range2 = FcsConfig.MARKER_RANGE.get() * FcsConfig.MARKER_RANGE.get();
        for (Track t : sensor.active()) {
            if (glow && t.handle instanceof LivingEntity le) {
                le.addEffect(new MobEffectInstance(MobEffects.GLOWING, 15, 0, false, false));
            }
            if (!mark) continue;
            double top = t.handle instanceof SableCompat.Ship s ? s.box().maxY + 1.0 : t.pos.y() + t.hitRadius + 0.8;
            DustParticleOptions p = engaged.contains(t.id) ? MARK_ENGAGED : MARK_TRACKED;
            for (ServerPlayer pl : level.players()) {
                if (pl.distanceToSqr(t.pos.x(), t.pos.y(), t.pos.z()) > range2) continue;
                level.sendParticles(pl, p, true, t.pos.x(), top, t.pos.z(), 3, 0.15, 0.15, 0.15, 0);
            }
        }
    }
}
