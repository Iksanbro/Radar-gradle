package dev.radarfcs.fcs;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.CbcBallistics;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.ShotObserver;
import dev.radarfcs.core.FlightCorrection;
import dev.radarfcs.core.FlightModel;
import dev.radarfcs.core.Vec;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Состояние одной пушки под управлением вычислителя. */
public final class MountState implements ShotObserver.Listener {
    public final BlockPos pos;
    /** Станок CBC; null - орудие на вращателях без станка (огонь сигналом прицела). */
    @Nullable public CbcMount mount;
    /** Орудие на вращателях, в которое входит этот станок (или прицел без станка), иначе null. */
    @Nullable public Turret turret;
    /** Настройки орудия на этот тик (у орудия; у обычной пушки null). */
    @Nullable public GunSettings gunSettings;
    public boolean locked;
    public boolean claimed;

    // привод
    public double cmdYaw = Double.NaN, cmdPitch = Double.NaN;
    public long lastSync;
    public boolean syncPending;

    // баллистика
    @Nullable public CbcBallistics.Estimate estimate;
    public long estimateTick = Long.MIN_VALUE / 2;
    public final FlightCorrection correction = new FlightCorrection();
    public String correctionKey = "";
    public double learnedMuzzle = Double.NaN;
    private int muzzleSamples;

    // выстрел, за которым следим
    @Nullable private FlightModel shotBase;
    @Nullable private Vec3 shotPivot, shotDir;
    public long lastLearnArm = Long.MIN_VALUE / 2;
    public int shotsSeen;

    // цель
    @Nullable public String targetId;
    @Nullable public String targetName;
    public double hintPitch = Double.NaN;
    public double aimError = Double.NaN, tolerance = Double.NaN, distance = Double.NaN, flightTicks = Double.NaN;
    /** Расчётный промах выстрела из фактического положения ствола, блоков; tolerance - допустимый промах. */
    public double predictedMiss = Double.NaN;
    /** Тик (с долей) сближения в расчёте промаха: доля близко к 0 или 1 - встреча на границе тика. */
    public double predictedMissTick = Double.NaN;
    /** Точка и направление вылета следующего снаряда в расчёте промаха (для диагностики). */
    @Nullable public dev.radarfcs.core.Vec predictedLaunch, predictedDir;
    public long safetyTick = Long.MIN_VALUE / 2;
    @Nullable public String safetyTarget;
    @Nullable public dev.radarfcs.core.Vec safetyAim;
    public FireSafety.Verdict safety = FireSafety.Verdict.CLEAR;

    // огонь
    public boolean triggerOn;
    /** Выбор цели этой пушки (приоритет, удержание, переключение). */
    public final dev.radarfcs.core.TargetPicker.State pick = new dev.radarfcs.core.TargetPicker.State();
    /** Выстрелы, замеченные у дульного среза и ещё не учтённые. */
    public int pendingShots;
    /** Выстрелов в текущей очереди и конец паузы между очередями. */
    public int salvoCount;
    public long pauseUntil = Long.MIN_VALUE / 2;
    /** Тик, в который учтён последний выстрел. */
    public long lastCountedShot = Long.MIN_VALUE / 2;
    /** Всего выстрелов этой пушки (для отчёта). */
    public int shotsFired;
    public long lastFire = Long.MIN_VALUE / 2;
    public long lastAssembleTry = Long.MIN_VALUE / 2;
    public Status status = Status.IDLE;
    /** Пояснение к состоянию (например, причина, по которой CBC не собрал пушку). */
    @Nullable public String note;

    private boolean listening = true;

    /** Диагностика выстрелов в лог (игровые тесты с -PfcsProbe). */
    private static final boolean PROBE = Boolean.getBoolean("radarfcs.probe");

    public MountState(BlockPos pos) {
        this.pos = pos.immutable();
    }

    /**
     * Станок этого состояния (блок из мира, при каждом пересмотре аппаратуры). Блок сменился - чанк станка выгружался
     * и загрузился снова, пока вычислитель не тикал: у нового блока валы не заблокированы и спуск не наш - захват
     * заново (блокировка валов и спуск - как у только что найденного станка).
     */
    public void bind(@Nullable CbcMount found) {
        if (mount != null && (found == null || found.blockEntity() != mount.blockEntity())) {
            locked = false;
            claimed = false;
            triggerOn = false;
        }
        mount = found;
    }

    /** Модель полёта: оценка по содержимому пушки + выученные поправки. */
    /** Последняя оценка с заряженной пушкой: пока пушка пуста (перезарядка), целимся по ней, а не по скорости «по умолчанию». */
    @Nullable private CbcBallistics.Estimate lastLoaded;

    /** Орудие без станка CBC: баллистика неизвестна - прицел наводится прямо на цель (почти мгновенный «снаряд»). */
    private static final FlightModel DIRECT = dev.radarfcs.core.CbcFlightModel.ballistic(64.0, 0.0, 0.0, false, 128);

    public FlightModel flightModel() {
        if (mount == null) return gunSettings != null ? gunSettings.model() : DIRECT;
        CbcBallistics.Estimate src = estimate != null && estimate.loaded() ? estimate : lastLoaded != null ? lastLoaded : estimate;
        FlightModel base = src != null ? src.model() : FcsConfig.bigFallback();
        return FcsConfig.LEARN_FROM_SHOTS.get() ? correction.apply(base) : base;
    }

    public double muzzleDistance(double fromBlocks) {
        return Double.isNaN(learnedMuzzle) ? fromBlocks : learnedMuzzle;
    }

    /** Новая оценка баллистики; поправки сбрасываются при смене боеприпаса. */
    public void setEstimate(CbcBallistics.Estimate e, long now) {
        estimate = e;
        estimateTick = now;
        if (e.loaded()) lastLoaded = e;
        if (e.loaded() && !e.fingerprint().equals(correctionKey)) {
            if (!correctionKey.isEmpty()) correction.reset();
            correctionKey = e.fingerprint();
        }
    }

    public void prepareShot(FlightModel base, Vec3 pivot, Vec3 dir) {
        shotBase = base;
        shotPivot = pivot;
        shotDir = dir;
    }

    public void stopListening() {
        listening = false;
        ShotObserver.disarm(this);
    }

    @Override
    public boolean isListening() {
        return listening;
    }

    @Override
    public void onShotSpawned(Entity projectile, Vec3 spawnPos, Vec3 velocity) {
        shotsSeen++;
        if (PROBE && predictedDir != null && predictedLaunch != null && velocity.lengthSqr() > 1e-6) {
            Vec3 pd = new Vec3(predictedDir.x(), predictedDir.y(), predictedDir.z()).normalize();
            double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, pd.dot(velocity.normalize())))));
            Vec3 dl = spawnPos.subtract(predictedLaunch.x(), predictedLaunch.y(), predictedLaunch.z());
            dev.radarfcs.FcsMod.LOG.info("[radar_fcs probe] shot from {}: direction error {}°, launch error {} (along {}), speed {}",
                    pos.toShortString(), String.format(java.util.Locale.ROOT, "%.3f", ang), String.format(java.util.Locale.ROOT, "%.3f", dl.length()),
                    String.format(java.util.Locale.ROOT, "%.3f", dl.dot(pd)), String.format(java.util.Locale.ROOT, "%.3f", velocity.length()));
        }
        if (shotPivot != null && shotDir != null) {
            // направление - по скорости самого снаряда: ствол быстро ведущей пушки за тик до выстрела
            // смотрел иначе, и проекция на его направление занижала вынос дульного среза
            Vec3 dir = velocity.lengthSqr() > 1e-6 ? velocity.normalize() : shotDir;
            double m = spawnPos.subtract(shotPivot).dot(dir);
            if (m > 0 && m < 32) {
                double w = muzzleSamples < 3 ? 1.0 / (muzzleSamples + 1) : 0.2;
                learnedMuzzle = Double.isNaN(learnedMuzzle) ? m : learnedMuzzle + (m - learnedMuzzle) * w;
                muzzleSamples++;
            }
        }
    }

    /** Вычислитель, управляющий станком (его снаряды свои для него, его владельца и команды). */
    @Nullable public BlockPos fcsPos;
    @Nullable public UUID fcsOwner;
    public String fcsTeam = "";

    /** Тик последнего засчитанного выстрела: несколько снарядов одного тика (картечь, спаренные стволы) - один выстрел. */
    private long lastShotTick = Long.MIN_VALUE;
    /** Выстрелы этого орудия по целям (для сброса после N выстрелов) и цели, которые оно уже сбросило. */
    public final java.util.Map<String, Integer> shotsAt = new java.util.HashMap<>();
    public final java.util.Set<String> dropped = new java.util.HashSet<>();
    /** Тик последнего выстрела, засчитанного этому орудию от его станков. */
    public long lastSharedShot = Long.MIN_VALUE / 2;

    @Override
    public void onShotCounted(Entity projectile) {
        long t = projectile.level().getGameTime();
        if (t != lastShotTick) {
            lastShotTick = t;
            pendingShots++;
        }
        shotsFired++;
        if (fcsPos != null) ProjectileTracking.markFired(projectile, fcsPos, fcsOwner, fcsTeam);
    }

    @Override
    public void onShotObserved(List<Vec> velocities) {
        if (shotBase != null && FcsConfig.LEARN_FROM_SHOTS.get()) {
            correction.learn(shotBase, velocities, FcsConfig.LEARN_SMOOTHING.get());
        }
    }
}
