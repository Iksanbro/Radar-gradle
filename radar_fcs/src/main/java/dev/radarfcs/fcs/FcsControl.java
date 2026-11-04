package dev.radarfcs.fcs;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AimControllerBlockEntity;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.GunDriveBlockEntity;
import dev.radarfcs.block.GunRotatorBlockEntity;
import dev.radarfcs.block.SightBlockEntity;
import dev.radarfcs.compat.CbcBallistics;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.compat.ShotObserver;
import dev.radarfcs.core.Aim;
import dev.radarfcs.core.BallisticSolver;
import dev.radarfcs.core.FlightModel;
import dev.radarfcs.core.IffRule;
import dev.radarfcs.compat.Interception;
import dev.radarfcs.core.ProjectileType;
import dev.radarfcs.core.TargetPath;
import dev.radarfcs.core.TargetMotion;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.TargetPicker;
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
    /** Смещение точки встречи, после которого проверка линии огня повторяется сразу, блоков. */
    private static final double SAFETY_RECHECK_BLOCKS = 0.75;

    private FcsControl() {}

    /**
     * @param designated id цели, назначенной с радарного монитора канала (или null)
     * @param manual     ручное наведение с камеры сети (или null): автоприцеливание приостановлено, пушка наводится
     *                   на точку взгляда камеры и стреляет, только пока игрок держит огонь
     */
    public static void control(ServerLevel level, FireControlBlockEntity fcs, MountState m, TargetSensor sensor, long now,
                               boolean hold, IffRule iff, Set<UUID> platform, @Nullable String designated,
                               @Nullable ManualAim manual) {
        CbcMount d = m.mount;
        Turret tu = m.turret;
        if (tu != null && !m.pos.equals(tu.leader)) {                 // второй станок орудия - стреляет с ведущим
            follow(level, fcs, m, tu, now, manual);
            return;
        }
        SightBlockEntity sight = tu != null ? tu.sightEntity(level) : null;
        // орудие: прицел (если он в орудии) на месте; без прицела наводится ствол станка
        boolean lost = tu != null ? (tu.sight != null && sight == null) || (d != null && !d.isValid()) || (tu.sight == null && d == null)
                : d == null || !d.isValid();
        if (lost) {
            m.status = Status.LOST;
            return;
        }
        if (tu == null && FcsConfig.YIELD_TO_RADAR_CONTROLLERS.get() && d.hasRadarControllers()) {
            yieldMount(level, fcs, m);
            m.status = Status.RADAR_CONTROLLED;
            return;
        }
        if (d != null) {
            if (!prepareMount(level, fcs, m, d, now, tu == null)) return;
        } else if (!claim(level, fcs, m, now)) {
            return;
        }
        // орудие на вращателях: вращатели, которые поворачивают прицел
        List<GunRotatorBlockEntity> chain = null;
        List<GunDriveBlockEntity> drives = null;
        if (tu != null) {
            List<GunRotatorBlockEntity> all = tu.rotatorEntities(level);
            chain = Turret.chain(level, tu.sight != null ? tu.sight : d.pos(), all);
            drives = tu.driveEntities(level);
            if (chain.isEmpty() && drives.isEmpty()) {
                setTrigger(m, false);
                boolean unassembled = false;
                for (GunRotatorBlockEntity r : all) if (!r.assembled()) unassembled = true;
                m.status = unassembled ? Status.NOT_ASSEMBLED : Status.TURRET_NO_ROTATOR;
                return;
            }
        }
        // настройки орудия (Shift+ПКМ по орудийному линку): тип, автоприцел, стрельба
        GunSettings gs = tu != null ? tu.settings(level) : null;
        m.gunSettings = gs;
        CbcMount.Kind kind = d != null ? d.kind() : gs != null && gs.type.pulse() ? CbcMount.Kind.BIG : CbcMount.Kind.RAPID;
        boolean noLead = d == null && gs != null && gs.type == GunSettings.Type.CUSTOM;
        boolean gunFireOff = gs != null && !gs.fire;
        EngageSettings settings = fcs.settings();
        countShots(fcs, m, now);

        // система координат станка: на корабле - через позу корабля
        SubLevelAccess ship = SableCompat.containing(level, d != null ? d.pos() : tu.sight);
        // направление наведения орудия: прицел или ствол станка
        Vec3 aimDir = null;
        if (tu != null) {
            if (sight != null) {
                aimDir = sight.worldDirection();
            } else {
                Vec3 bl = d.localBarrelDirection(1.0F);
                if (bl == null) {
                    setTrigger(m, false);
                    m.status = Status.NOT_ASSEMBLED;
                    return;
                }
                aimDir = SableCompat.dirToWorld(ship, bl).normalize();
            }
        }
        Vec3 pivotLocal = d != null ? d.localPivot() : Vec3.atCenterOf(tu.sight);
        Vec3 pivotW = SableCompat.toWorld(level, pivotLocal);
        Vec pivot = new Vec(pivotW.x, pivotW.y, pivotW.z);
        Vec platformVel = Vec.ZERO;
        if (ship != null && FcsConfig.INHERIT_PLATFORM_VELOCITY.get()) {
            Vec3 pv = SableCompat.pointVelocity(level, ship, pivotLocal);
            platformVel = new Vec(pv.x, pv.y, pv.z);
        }

        if (d != null && kind == CbcMount.Kind.UNSUPPORTED) {
            setTrigger(m, false);
            m.status = Status.UNSUPPORTED;
            return;
        }
        List<Track> active = sensor.active();
        if (gs != null && !gs.autoAim && manual == null) {                // орудие только для камеры
            idle(m, Status.GUN_AUTO_OFF);
            m.targetId = null;
            m.targetName = null;
            return;
        }
        if (active.isEmpty() && manual == null) {
            idle(m, Status.IDLE);
            if (d != null) syncIfNeeded(d, m, now);
            return;
        }

        FlightModel model = m.flightModel();
        double muzzleDist = d != null ? m.muzzleDistance(d.muzzleDistance()) : 0.5;
        BallisticSolver.Muzzle muzzle = BallisticSolver.Muzzle.offset(pivot, muzzleDist);
        boolean discrete = FcsConfig.DISCRETE_INTERCEPT.get();
        // орудие на вращателях: пределы наклона задаёт постройка, а не станок
        BallisticSolver solver = (tu != null ? new BallisticSolver(-90, 90, FcsConfig.HIGH_ARC.get())
                : new BallisticSolver(-d.maxDepression(), d.maxElevation(), FcsConfig.HIGH_ARC.get())).discrete(discrete);

        // Выбор цели по настройкам вычислителя: уровень приоритета типа, режим приоритета, удержание текущей,
        // переключение по приоритету и по правилу; назначенная с монитора - вне очереди.
        List<Track> cands;
        if (manual != null) {
            // ручное наведение с камеры: одна цель - точка взгляда камеры
            cands = List.of(Track.point(MANUAL_ID, manual.camera(), manual.point(), now));
        } else {
            List<Track> pool = new ArrayList<>(active);
            pool.removeIf(t -> fcs.isDropped(t.id));
            // свои цели пушки: разрешённые типы и уровни (назначенная вручную и белый список - в обход типов)
            EngageSettings ms = fcs.effectiveSettings().forMount(m.pos.asLong());
            pool.removeIf(t -> !t.id.equals(designated) && !TargetLists.matches(ms.whitelist, t)
                    && !ms.enabled(EngageSettings.Category.of(t)));
            cands = TargetPicker.order(pool, pivot, ms, m.pick, now, FcsConfig.TARGET_SWITCH_RATIO.get(), designated);
        }
        // Команда станку исполнится к следующему тику: решаем для снаряда, вылетающего через тик.
        BallisticSolver.Solution sol = null;
        Track target = null;
        TargetPath motion = null;
        double age = 0;
        for (int i = 0; i < Math.min(3, cands.size()); i++) {
            Track t = cands.get(i);
            double horizon = FcsConfig.USE_ACCELERATION.get() && !t.grounded ? FcsConfig.ACCEL_HORIZON.get() : 0;
            // снаряд-цель: его путь по тикам (физика CBC или замеры); прочие цели - движение по оценке
            // кастомное орудие - без упреждения: цель там, где она сейчас
            TargetPath mo = noLead ? new TargetMotion(t.pos, Vec.ZERO, Vec.ZERO, false, 0)
                    : t.predictor(horizon).relativeTo(platformVel);
            double a = (now - t.lastTick) + FcsConfig.LATENCY.get();
            double hint = t.id.equals(m.targetId) ? m.hintPitch : Double.NaN;
            // встреча снарядов проверяется непрерывно (отрезки обоих за тик), а не по положению цели на конец тика
            solver.discrete(discrete && t.kind != Track.Kind.PROJECTILE);
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
            if (d != null) syncIfNeeded(d, m, now);
            if (manual != null) {                                      // ЛКМ с камеры - огонь и без решения
                manualTrigger(level, m, d, sight, tu, manual.fire(), pivotW, null, 0, now);
                m.status = Status.CAMERA;
            }
            return;
        }
        if (!target.id.equals(m.targetId)) {
            m.safetyTick = Long.MIN_VALUE / 2;
            if (kind == CbcMount.Kind.RAPID) setTrigger(m, false);
        }
        m.pick.acquire(target.id, now);
        m.targetId = target.id;
        m.targetName = target.name;
        m.hintPitch = Math.toRadians(sol.pitchDeg());
        m.flightTicks = sol.flightTicks();
        m.distance = sol.aimPoint().distance(sol.muzzle());

        // желаемые углы станка или поворот вращателей орудия
        Vec3 wantW = new Vec3(sol.direction().x(), sol.direction().y(), sol.direction().z());
        boolean outOfArc = false, noPower, noController = false;
        if (tu != null) {
            Turret.Drive drv = Turret.aim(chain, aimDir, wantW, FcsConfig.DEADBAND.get());
            noPower = drv.noPower();
            if (tu.drive(level, drives, aimDir, drv.after(), wantW, FcsConfig.DEADBAND.get(), now)) noPower = true;
        } else {
            Vec3 wantL = SableCompat.dirToLocal(ship, wantW);
            Vec wl = new Vec(wantL.x, wantL.y, wantL.z);
            double wantYaw = Aim.wrap180(wl.yawDeg() + FcsConfig.YAW_OFFSET.get());
            double wantPitch = wl.pitchDeg() * (FcsConfig.INVERT_PITCH.get() ? -1 : 1);
            double lo = -d.maxDepression(), hi = d.maxElevation();
            outOfArc = wantPitch < lo - 0.5 || wantPitch > hi + 0.5;
            wantPitch = Aim.clamp(wantPitch, lo, hi);

            // скорость поворота: от контроллеров наводки (вращение на их валах, как у валов CBC) или без кинетики
            double slewYaw, slewPitch;
            if (FcsConfig.REQUIRE_ROTATION.get()) {
                double k = FcsConfig.SHAFT_SPEED.get();
                AimControllerBlockEntity yc = AimControllerBlockEntity.find(level, d.pos(), false);
                AimControllerBlockEntity pc = AimControllerBlockEntity.find(level, d.pos(), true);
                noController = yc == null || pc == null;
                slewYaw = yc == null ? 0 : yc.degPerTick() * k;
                slewPitch = pc == null ? 0 : pc.degPerTick() * k;
            } else {
                slewYaw = slewPitch = FcsConfig.SLEW.get();
            }
            double db = FcsConfig.DEADBAND.get();
            noPower = (slewYaw <= 0 && Math.abs(Aim.wrap180(wantYaw - m.cmdYaw)) > db)
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
        }

        // Проверка выстрела фактическим положением ствола, которое возьмёт CBC:
        // большая пушка стреляет сразу - по положению на начало тика, снаряд появляется сейчас;
        // автопушка стреляет в следующем тике - по только что выставленным углам, снаряд на тик позже.
        // у орудия на вращателях ствол смотрит туда же, куда прицел
        boolean big = kind != CbcMount.Kind.RAPID;
        Vec3 shotW;
        if (tu != null && sight != null) {
            shotW = aimDir;
        } else {
            Vec3 shotL = d.localBarrelDirection(big ? 0.0F : 1.0F);
            shotW = shotL == null ? null : SableCompat.dirToWorld(ship, shotL);
        }
        // Попадание CBC: отрезок полёта за тик задевает габарит цели, расширенный на половину размера снаряда.
        // Габарит сущности известен точно - промах считаем до него (с небольшим запасом внутрь);
        // у кораблей и контрапшенов - до центра с допуском по размеру.
        CbcBallistics.Estimate est = m.estimate;
        double reach = est != null ? est.reach() : CbcBallistics.Estimate.defaultReach(kind);
        // разброс автопушки CBC: отклонение направления до 0.0172*spread рад по каждой оси; запас - половина этого
        double spread = est != null ? est.spread() : Double.NaN;
        double spreadBlocks = Double.isNaN(spread) ? 0.3 : 0.5 * 0.0172 * spread * m.distance;
        double margin = big ? 0 : FcsConfig.RAPID_SPREAD.get() + spreadBlocks;
        Vec half = null;
        double allowed;
        boolean intercept = target.kind == Track.Kind.PROJECTILE;
        if (intercept) {
            // CBC Interception: снаряды сталкиваются, если сблизились меньше суммы половин их размеров плюс extraHitRadius
            double contact = target.hitRadius + reach + Interception.extraHitRadius();
            double inset = big
                    ? Math.min(0.35 * contact, 0.1 + m.distance * Math.tan(Math.toRadians(FcsConfig.FIRE_TOLERANCE.get())))
                    : Math.min(0.15, 0.25 * contact);
            allowed = Math.max(0.1, contact - inset) + margin;
        } else if (target.half != null) {
            Vec th = target.half;
            double minHalf = Math.min(th.x(), Math.min(th.y(), th.z())) + reach;
            // запас внутрь габарита на ошибку прогноза: у автопушки промах стоит один патрон - небольшой;
            // у большой пушки - на угловой допуск (перезарядка долгая, стрелять «впритирку» невыгодно)
            double inset = big
                    ? Math.min(0.5 * minHalf, 0.1 + m.distance * Math.tan(Math.toRadians(FcsConfig.FIRE_TOLERANCE.get())))
                    : Math.min(0.15, 0.3 * minHalf);
            double k = reach - inset;
            half = new Vec(Math.max(0.05, th.x() + k), Math.max(0.05, th.y() + k), Math.max(0.05, th.z() + k));
            allowed = margin;
        } else {
            allowed = Math.max(target.hitRadius * 0.8 + 0.2,
                    m.distance * Math.tan(Math.toRadians(FcsConfig.FIRE_TOLERANCE.get()))) + margin;
        }
        double missDist = Double.POSITIVE_INFINITY;
        if (shotW != null) {
            Vec sd = new Vec(shotW.x, shotW.y, shotW.z);
            Vec launch = muzzle.at(sd.normalize());
            int horizonTicks = (int) Math.ceil(sol.flightTicks() * 1.5) + 20;
            BallisticSolver.Miss mm = intercept
                    ? BallisticSolver.miss(launch, sd, model, motion, big ? age : age + 1, horizonTicks, false, null, Interception.ARMING_TICKS)
                    : BallisticSolver.miss(launch, sd, model, motion, big ? age : age + 1, horizonTicks, discrete, half);
            missDist = mm.distance();
            // снаряды, летящие почти вместе («один залп»), CBC Interception не сталкивает - догонять бесполезно
            if (intercept && mm.closing() < Interception.SAME_VOLLEY_SPEED + 0.25) missDist = Double.POSITIVE_INFINITY;
            m.predictedMissTick = mm.tick();
            m.predictedLaunch = launch;
            m.predictedDir = sd;
            m.aimError = sd.angleTo(sol.direction());
        } else {
            m.aimError = 180;
        }
        m.predictedMiss = missDist;
        m.tolerance = allowed;
        // Промах считается для каждого следующего выстрела автопушки, поэтому очередь держим, пока он мал;
        // небольшой запас на продолжение очереди убирает дребезг сигнала на границе порога
        boolean onTarget = missDist <= allowed || (!big && m.triggerOn && missDist <= allowed + 0.5 * margin);
        // молодой трек или резкий манёвр: оценка движения ненадёжна - наводимся, но не стреляем.
        // Снаряд-цель с точной физикой ведётся с первого замера; ракете - пара тиков на оценку разгона.
        if (target.path != null) {
            boolean exactPath = target.munition == ProjectileType.SHELL;
            if (target.observedSpan() < (exactPath ? 1 : 3)) onTarget = false;
        } else {
            if (target.observedSpan() < FcsConfig.FIRE_MIN_TRACK.get()) onTarget = false;
            if (target.residual > Math.max(Track.MANEUVER_RESIDUAL, target.hitRadius * 0.25)) onTarget = false;
        }
        // без вращения на нужном валу станок не довернуть: не стреляем, даже если цель случайно на линии огня
        boolean aligned = onTarget && !outOfArc && !noPower;
        boolean loaded = m.estimate == null || m.estimate.loaded();

        FireSafety.Verdict verdict = FireSafety.Verdict.CLEAR;
        if (aligned) {
            // по быстрой цели точка встречи за тик уходит на блоки: проверку не кэшируем, если она сместилась
            boolean moved = m.safetyAim == null || m.safetyAim.distance(sol.aimPoint()) > SAFETY_RECHECK_BLOCKS;
            if (now - m.safetyTick >= SAFETY_CACHE_TICKS || moved || !target.id.equals(m.safetyTarget)) {
                List<Vec> path = new ArrayList<>();
                BallisticSolver.trajectory(sol, model, 2, path::add);
                m.safety = FireSafety.check(level, path, sensor.all(), target, iff, platform);
                m.safetyTick = now;
                m.safetyTarget = target.id;
                m.safetyAim = sol.aimPoint();
            }
            verdict = m.safety;
        }
        boolean fire = aligned && loaded && verdict == FireSafety.Verdict.CLEAR && (manual != null ? manual.fire() : !hold && !gunFireOff);
        boolean wasOn = m.triggerOn;
        long lastFireBefore = m.lastFire;
        if (manual != null) {
            // ЛКМ с камеры - сигнал огня как редстоун 15, пока кнопка зажата (без проверки наводки)
            manualTrigger(level, m, d, sight, tu, manual.fire(), pivotW, shotW, muzzleDist, now);
        } else {
            if (tu != null) {
                tu.fire = fire;
                tu.fireTick = now;
            }
            fireControl(level, d, sight, m, kind, fire, pivotW, shotW, muzzleDist, now, settings);
        }
        boolean shot = big ? m.lastFire != lastFireBefore : m.triggerOn && !wasOn;
        if (FcsConfig.LOG_SHOTS.get() && shot) {
            FcsMod.LOG.info("[radar_fcs] FIRE {} at {} pos={} vel={} acc={} res={} age={} aim={} ttf={} pitch={} yaw={} err={} miss={} allowed={} speed={} muzzle={}",
                    d != null ? d.typeName() : "sight", target.name, target.pos, target.vel, target.acc, String.format("%.3f", target.residual),
                    now - target.lastTick,
                    sol.aimPoint(), String.format("%.2f", sol.flightTicks()), String.format("%.2f", sol.pitchDeg()),
                    String.format("%.2f", sol.yawDeg()), String.format("%.3f", m.aimError), String.format("%.2f", missDist),
                    String.format("%.2f", allowed), String.format("%.3f", model.muzzleSpeed()), String.format("%.2f", muzzleDist));
        }

        if (outOfArc) m.status = Status.OUT_OF_ARC;
        else if (noPower) m.status = tu != null ? Status.TURRET_NO_POWER : noController ? Status.NO_CONTROLLER : Status.NO_POWER;
        else if (!aligned) m.status = Status.AIMING;
        else if (verdict == FireSafety.Verdict.FRIENDLY) m.status = Status.BLOCKED_FRIENDLY;
        else if (verdict == FireSafety.Verdict.TERRAIN) m.status = Status.BLOCKED_TERRAIN;
        else if (!loaded) m.status = Status.NO_AMMO;
        else if (hold || gunFireOff) m.status = Status.HOLD;
        else if (now < m.pauseUntil) m.status = Status.PAUSE;
        else m.status = Status.FIRE;
        if (manual != null && (m.status == Status.FIRE || m.status == Status.AIMING || m.status == Status.HOLD || m.status == Status.PAUSE)) {
            m.status = Status.CAMERA;
        }
    }

    /** Захват пушки вычислителем (одна пушка - один вычислитель); false - ею управляет другой. */
    private static boolean claim(ServerLevel level, FireControlBlockEntity fcs, MountState m, long now) {
        if (!MountClaims.claim(level, m.pos, fcs.getBlockPos(), now)) {
            if (m.locked || m.triggerOn) releaseHardware(m);
            m.status = Status.OTHER_FCS;
            return false;
        }
        m.claimed = true;
        m.fcsPos = fcs.getBlockPos();
        m.fcsOwner = fcs.owner();
        m.fcsTeam = fcs.ownerTeam();
        return true;
    }

    /** Захват, сборка, блокировка валов и оценка заряжания станка; false - станок не готов (статус выставлен). */
    private static boolean prepareMount(ServerLevel level, FireControlBlockEntity fcs, MountState m, CbcMount d, long now, boolean lock) {
        if (!claim(level, fcs, m, now)) return false;
        if (!d.isAssembled()) {
            setTrigger(m, false);
            m.cmdYaw = Double.NaN;
            if (!fcs.assembleWanted()) {                           // разобрана кнопкой - не собирать
                m.note = null;
                m.status = Status.DISASSEMBLED;
                return false;
            }
            if (FcsConfig.AUTO_ASSEMBLE.get() && now - m.lastAssembleTry >= 40) {
                m.lastAssembleTry = now;
                d.assemble();
                m.note = d.assemblyError();
            }
            m.status = Status.NOT_ASSEMBLED;
            return false;
        }
        m.note = null;
        if (!d.canControl()) {
            releaseHardware(m);
            m.status = Status.MANUAL;
            return false;
        }
        // станок орудия поворачивают валы (приводы орудия) или вращатели - его валы не блокируются
        if (lock && !m.locked) {
            d.setDriveLock(true);
            m.locked = true;
        } else if (!lock && m.locked) {
            d.setDriveLock(false);
            m.locked = false;
        }
        if (Double.isNaN(m.cmdYaw)) {
            m.cmdYaw = d.yaw();
            m.cmdPitch = d.pitch();
        }
        // заряжание: пока пушка пуста, проверять часто (автомат заряжания успевает за несколько тиков)
        if (m.estimate == null || now - m.estimateTick >= (m.estimate.loaded() ? 20 : 2)) m.setEstimate(CbcBallistics.estimate(level, d), now);
        return true;
    }

    /** Выстрелы прошлого тика: по какой цели, сколько в очереди. */
    private static void countShots(FireControlBlockEntity fcs, MountState m, long now) {
        if (m.pendingShots <= 0) return;
        int n = m.pendingShots;
        m.pendingShots = 0;
        m.salvoCount += n;
        m.lastCountedShot = now;
        if (m.targetId != null) {
            if (m.targetId.equals(m.pick.currentId)) m.pick.shotsAtCurrent += n;
            for (int i = 0; i < n; i++) fcs.onShotAt(m.targetId);
        }
    }

    /**
     * Второй (и следующий) станок орудия на вращателях: наводит ведущий, этот стреляет вместе с ним - когда ствол
     * (по прицелу) на цели и этот станок заряжен.
     */
    private static void follow(ServerLevel level, FireControlBlockEntity fcs, MountState m, Turret tu, long now,
                               @Nullable ManualAim manual) {
        CbcMount d = m.mount;
        SightBlockEntity sight = tu.sightEntity(level);
        if (d == null || !d.isValid() || (tu.sight != null && sight == null)) {
            m.status = Status.LOST;
            return;
        }
        if (!prepareMount(level, fcs, m, d, now, false)) return;
        CbcMount.Kind kind = d.kind();
        if (kind == CbcMount.Kind.UNSUPPORTED) {
            setTrigger(m, false);
            m.status = Status.UNSUPPORTED;
            return;
        }
        countShots(fcs, m, now);
        MountState lead = fcs.mountState(tu.leader);
        if (lead != null) {
            m.status = lead.status;
            m.targetId = lead.targetId;
            m.targetName = lead.targetName;
            m.distance = lead.distance;
            m.aimError = lead.aimError;
        }
        boolean loaded = m.estimate == null || m.estimate.loaded();
        if (!loaded && m.status == Status.FIRE) m.status = Status.NO_AMMO;
        boolean fire = tu.fire && tu.fireTick == now && loaded;
        Vec3 pivotW = SableCompat.toWorld(level, d.localPivot());
        Vec3 bl = d.localBarrelDirection(kind == CbcMount.Kind.RAPID ? 1.0F : 0.0F);
        Vec3 barrel = sight != null ? sight.worldDirection() : bl == null ? null
                : SableCompat.dirToWorld(SableCompat.containing(level, d.pos()), bl);
        if (manual != null) {
            manualTrigger(level, m, d, null, null, tu.fire && tu.fireTick == now, pivotW, barrel, m.muzzleDistance(d.muzzleDistance()), now);
            return;
        }
        // сигнал прицела выдаёт ведущий; второй станок - только свой огонь
        fireControl(level, d, null, m, kind, fire, pivotW, barrel, m.muzzleDistance(d.muzzleDistance()), now, fcs.settings());
    }

    /**
     * Спуск с камеры: пока зажата ЛКМ - сигнал огня станку (сила 15, как редстоун: большая пушка стреляет по фронту,
     * скорострельная - непрерывно) и прицелу; без проверки наводки и режима огня.
     */
    private static void manualTrigger(ServerLevel level, MountState m, @Nullable CbcMount d, @Nullable SightBlockEntity sight,
                                      @Nullable Turret tu, boolean on, Vec3 pivotW, @Nullable Vec3 barrelW, double muzzleDist, long now) {
        if (tu != null) {
            tu.fire = on;
            tu.fireTick = now;
        }
        if (sight != null) sight.fire(on);
        if (d != null && d.isValid()) {
            if (on != m.triggerOn || (on && !d.firePowered())) d.setFire(on, on ? 15 : 0);
            // свои снаряды - свои (не цели перехвата)
            if (on && barrelW != null) ShotObserver.count(level, pivotW.add(barrelW.scale(muzzleDist)), barrelW, 3, m);
        }
        m.triggerOn = on;
        if (on) m.lastFire = now;
    }

    /** id цели ручного наведения с камеры. */
    public static final String MANUAL_ID = "camera";

    /**
     * Спуск по режиму огня: автоматический - пока выстрел попадёт; одиночный и очередь - выстрел (очередь из
     * burstLength выстрелов), затем пауза pauseTicks. Выстрелы считаются по снарядам у дульного среза.
     */
    private static void fireControl(ServerLevel level, @Nullable CbcMount d, @Nullable SightBlockEntity sight, MountState m,
                                    CbcMount.Kind kind, boolean fire, Vec3 pivotW, Vec3 barrelW, double muzzleDist, long now,
                                    EngageSettings settings) {
        if (d == null) {
            // орудие без станка: сигнал прицела, пока огонь разрешён (паузы режима огня - по времени)
            boolean on = fire && now >= m.pauseUntil;
            GunSettings gs = m.gunSettings;
            if (gs != null && gs.type.pulse()) {
                // среднее орудие и большая пушка - импульс сигнала (как фронт редстоуна у CBC), не чаще интервала
                boolean pulse = on && now - m.lastFire >= FcsConfig.BIG_PULSE_INTERVAL.get();
                if (sight != null) sight.fire(pulse);
                if (pulse) m.lastFire = now;
                m.triggerOn = pulse;
                return;
            }
            if (sight != null) sight.fire(on);
            if (on && !m.triggerOn && settings.roundsPerSalvo() <= 1 && settings.pauseTicks > 0) m.pauseUntil = now + settings.pauseTicks;
            m.triggerOn = on;
            if (on) m.lastFire = now;
            return;
        }
        // прицел в орудии со станками тоже выдаёт сигнал огня (для своих схем и других пушек)
        if (sight != null) sight.fire(fire && now >= m.pauseUntil);
        boolean learn = FcsConfig.LEARN_FROM_SHOTS.get() && barrelW != null && m.estimate != null;
        int salvo = settings.roundsPerSalvo();
        // перерыв в стрельбе не короче паузы (цель пропала, пушка перезаряжается) - уже пауза: очередь заново
        if (m.salvoCount > 0 && m.salvoCount < salvo && now - m.lastCountedShot > settings.pauseTicks) m.salvoCount = 0;
        if (m.salvoCount >= salvo) {                          // очередь (или одиночный выстрел) сделана - пауза
            m.salvoCount = 0;
            m.pauseUntil = now + settings.pauseTicks;
            setTrigger(m, false);
        }
        boolean paused = now < m.pauseUntil;
        if (kind == CbcMount.Kind.RAPID) {
            if (fire && !paused) {
                if (learn && !ShotObserver.isWatching(m) && (m.correction.shots() < 5 || now - m.lastLearnArm > 100)) {
                    m.prepareShot(m.estimate.model(), pivotW, barrelW);
                    ShotObserver.arm(level, pivotW.add(barrelW.scale(muzzleDist)), barrelW, 6, m);
                    m.lastLearnArm = now;
                }
                if (barrelW != null) ShotObserver.count(level, pivotW.add(barrelW.scale(muzzleDist)), barrelW, 3, m);
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
        if (fire && !paused && !m.triggerOn && now - m.lastFire >= FcsConfig.BIG_PULSE_INTERVAL.get()) {
            if (learn) {
                m.prepareShot(m.estimate.model(), pivotW, barrelW);
                ShotObserver.arm(level, pivotW.add(barrelW.scale(muzzleDist)), barrelW, 3, m);
            }
            if (barrelW != null) ShotObserver.count(level, pivotW.add(barrelW.scale(muzzleDist)), barrelW, 3, m);
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
    /** Собрать пушку сейчас (кнопка «Собрать»); true - собрана. */
    public static boolean assemble(MountState m, long now) {
        CbcMount d = m.mount;
        if (d == null || !d.isValid()) return false;
        if (d.isAssembled()) return true;
        m.lastAssembleTry = now;
        boolean ok = d.assemble() && d.isAssembled();
        m.note = ok ? null : d.assemblyError();
        return ok;
    }

    /** Разобрать пушку (кнопка «Разобрать»): сначала отпустить привод и спуск; true - была собрана. */
    public static boolean disassemble(MountState m) {
        CbcMount d = m.mount;
        if (d == null || !d.isValid() || !d.isAssembled()) return false;
        releaseHardware(m);
        d.disassemble();
        m.status = Status.DISASSEMBLED;
        m.targetId = null;
        m.targetName = null;
        return true;
    }

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
