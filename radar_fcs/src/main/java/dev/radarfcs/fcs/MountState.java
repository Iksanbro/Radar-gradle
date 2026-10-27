package dev.radarfcs.fcs;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.CbcBallistics;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.ShotObserver;
import dev.radarfcs.core.FlightCorrection;
import dev.radarfcs.core.FlightModel;
import dev.radarfcs.core.Vec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Состояние одной пушки под управлением вычислителя. */
public final class MountState implements ShotObserver.Listener {
    public final BlockPos pos;
    public CbcMount mount;
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
    public long safetyTick = Long.MIN_VALUE / 2;
    @Nullable public String safetyTarget;
    public FireSafety.Verdict safety = FireSafety.Verdict.CLEAR;

    // огонь
    public boolean triggerOn;
    public long lastFire = Long.MIN_VALUE / 2;
    public long lastAssembleTry = Long.MIN_VALUE / 2;
    public Status status = Status.IDLE;
    /** Пояснение к состоянию (например, причина, по которой CBC не собрал пушку). */
    @Nullable public String note;

    private boolean listening = true;

    public MountState(BlockPos pos) {
        this.pos = pos.immutable();
    }

    /** Модель полёта: оценка по содержимому пушки + выученные поправки. */
    public FlightModel flightModel() {
        FlightModel base = estimate != null ? estimate.model() : FcsConfig.bigFallback();
        return FcsConfig.LEARN_FROM_SHOTS.get() ? correction.apply(base) : base;
    }

    public double muzzleDistance(double fromBlocks) {
        return Double.isNaN(learnedMuzzle) ? fromBlocks : learnedMuzzle;
    }

    /** Новая оценка баллистики; поправки сбрасываются при смене боеприпаса. */
    public void setEstimate(CbcBallistics.Estimate e, long now) {
        estimate = e;
        estimateTick = now;
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
        if (shotPivot != null && shotDir != null) {
            double m = spawnPos.subtract(shotPivot).dot(shotDir);
            if (m > 0 && m < 32) {
                double w = muzzleSamples < 3 ? 1.0 / (muzzleSamples + 1) : 0.2;
                learnedMuzzle = Double.isNaN(learnedMuzzle) ? m : learnedMuzzle + (m - learnedMuzzle) * w;
                muzzleSamples++;
            }
        }
    }

    @Override
    public void onShotObserved(List<Vec> velocities) {
        if (shotBase != null && FcsConfig.LEARN_FROM_SHOTS.get()) {
            correction.learn(shotBase, velocities, FcsConfig.LEARN_SMOOTHING.get());
        }
    }
}
