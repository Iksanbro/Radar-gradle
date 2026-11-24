package dev.radarfcs.fcs;

import dev.radarfcs.core.Aim;

import dev.radarfcs.core.AngleLimit;

import dev.radarfcs.block.GunDriveBlockEntity;
import dev.radarfcs.block.SightBlockEntity;
import dev.radarfcs.compat.SableCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Сборное орудие: прицел, приводы и станки CBC одной орудийной сети. Вычислитель наводит не станок, а всю постройку:
 * приводы (на подшипники Aeronautics, валы станка) поворачивают её так, чтобы направление прицела совпало с решением. Огонь - станками CBC орудия
 * или, если их нет, сигналом редстоуна от прицела.
 */
public final class Turret {
    /** Прицел орудия или null: тогда направление наведения - ствол ведущего станка. */
    @Nullable public final BlockPos sight;
    /** Ключ орудия: прицел или (без прицела) первый станок. */
    public final BlockPos key;
    public List<BlockPos> drives = List.of();
    public List<BlockPos> mounts = List.of();
    /** Орудийные сети орудия - по ним настройки (тип, автоприцел, стрельба, имя). */
    public java.util.Set<UUID> networks = java.util.Set.of();

    public GunSettings settings(Level level) {
        return dev.radarfcs.link.GunLinks.settingsOf(level, networks);
    }
    /** Направление наведения прошлого тика - по нему приводы учат свой отклик. */
    @Nullable private Vec3 prevAim;
    private long prevTick = Long.MIN_VALUE / 2;
    /** Основная пушка из настроек орудия (станок орудия) или null. */
    @Nullable public BlockPos main;
    /** Состояние, которое наводит орудие (остальные станки орудия только стреляют вместе с ним). */
    @Nullable public BlockPos leader;

    // решение ведущего на этот тик - для остальных станков орудия
    public boolean fire;
    /** Последний расчёт приводов: нужное направление за пределами поворота приводов (стоят у предела). */
    public boolean outOfArc;
    public long fireTick = Long.MIN_VALUE / 2;

    public Turret(@Nullable BlockPos sight, BlockPos key) {
        this.sight = sight == null ? null : sight.immutable();
        this.key = key.immutable();
    }

    @Nullable
    public SightBlockEntity sightEntity(Level level) {
        return sight != null && level.isLoaded(sight) && level.getBlockEntity(sight) instanceof SightBlockEntity s && !s.isRemoved() ? s : null;
    }

    public List<GunDriveBlockEntity> driveEntities(Level level) {
        List<GunDriveBlockEntity> out = new ArrayList<>();
        for (BlockPos p : drives) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof GunDriveBlockEntity d && !d.isRemoved()) out.add(d);
        }
        return out;
    }

    /**
     * Приводы орудия (выдают вращение - на подшипник, вал станка CBC...): курс - вокруг «верха» основания привода
     * курса, наклон - вокруг горизонтали, перпендикулярной направлению наведения. Выход привода пропорционален
     * нужному повороту (дойти примерно за {@link #DRIVE_TICKS} тиков) по выученному отклику; отклик учится по тому,
     * как на самом деле повернулось направление наведения за прошлый тик.
     *
     * @param aim   фактическое направление наведения (прицел или ствол)
     * @param after направление наведения для команды (обычно то же aim)
     * @return true - нужному приводу не подано вращение
     */
    public boolean drive(Level level, List<GunDriveBlockEntity> list, Vec3 aim, Vec3 after, Vec3 want, double deadband, long now) {
        if (list.isEmpty()) return false;
        Vec3 a = aim.normalize(), d = want.normalize();
        Vec3 up = new Vec3(0, 1, 0);
        for (GunDriveBlockEntity dr : list) {
            if (!dr.pitch()) {
                up = SableCompat.dirToWorld(SableCompat.containing(level, dr.getBlockPos()), up).normalize();
                break;
            }
        }
        Vec3 pitchAxis = a.cross(up);
        boolean fresh = prevAim != null && prevTick == now - 1;
        // поворотная часть подшипника орудия заметно не там, куда её ведёт вал (только что собрана, упёрлась): прицел
        // движется не от команды - привод не учится, иначе выучил бы нелепый отклик
        boolean settling = dev.radarfcs.compat.Mods.SABLE && dev.radarfcs.compat.Mods.SIMULATED
                && dev.radarfcs.block.GunBearingBlockEntity.settling(level);
        // обучение: поворот за прошлый тик вокруг оси каждого привода
        for (GunDriveBlockEntity dr : list) {
            if (settling) {
                dr.skipLearn();
                continue;
            }
            if (!fresh) {
                dr.learn(0);
                continue;
            }
            Vec3 axis = dr.pitch() ? pitchAxis : up;
            dr.learn(axis.lengthSqr() < 1e-6 ? 0 : Math.toDegrees(signedAngle(prevAim, a, axis.normalize())));
        }
        prevAim = a;
        prevTick = now;
        // команда: сначала курс, затем наклон уже повёрнутого направления
        Vec3 s = after.normalize();
        double yaw = Math.toDegrees(signedAngle(s, d, up));
        outOfArc = false;
        // пределы курса (ползунок привода): не через запретный сектор, за пределом - стоять у предела
        GunDriveBlockEntity yawLimit = limitedDrive(list, false);
        if (yawLimit != null) {
            double t = yawLimit.trackYaw(a);
            AngleLimit.Plan p = AngleLimit.plan(t, yaw, yawLimit.limits.second(), yawLimit.limits.first());
            yaw = p.delta();
            if (!p.reachable()) outOfArc = true;
        }
        Vec3 s2 = rotate(s, up, Math.toRadians(yaw));
        Vec3 k = s2.cross(up);
        double pitch = k.lengthSqr() < 1e-6 ? 0 : Math.toDegrees(signedAngle(s2, d, k.normalize()));
        // пределы наклона - от горизонта основания
        GunDriveBlockEntity pitchLimit = limitedDrive(list, true);
        if (pitchLimit != null) {
            double e = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, s2.dot(up)))));
            AngleLimit.Plan p = AngleLimit.planLinear(e, pitch, pitchLimit.limits.second(), pitchLimit.limits.first());
            pitch = p.delta();
            if (!p.reachable()) outOfArc = true;
        }
        boolean noPower = false;
        for (GunDriveBlockEntity dr : list) {
            double need = dr.pitch() ? pitch : yaw;
            double rate = dr.rate();
            float out = 0;
            if (Math.abs(need) > deadband) {
                if (Math.abs(rate) < 1e-6) noPower = true;
                else out = (float) Math.max(-1, Math.min(1, need / (rate * DRIVE_TICKS)));
            }
            dr.command(out);
        }
        return noPower;
    }

    /** Привод курса (pitch = false) или наклона с включёнными пределами, или null. */
    @Nullable
    private static GunDriveBlockEntity limitedDrive(List<GunDriveBlockEntity> list, boolean pitch) {
        for (GunDriveBlockEntity dr : list) if (dr.pitch() == pitch && dr.limits != null && dr.limits.limited()) return dr;
        return null;
    }

    /** Направление want в пределах поворота приводов (курс - от их нуля, наклон - от горизонта основания). */
    public static boolean inArc(Level level, List<GunDriveBlockEntity> list, Vec3 want) {
        if (list == null || list.isEmpty()) return true;
        Vec3 d = want.normalize();
        GunDriveBlockEntity y = limitedDrive(list, false);
        if (y != null) {
            double rel = y.yawOf(d), t = y.turned();
            if (!Double.isNaN(rel) && !Double.isNaN(t)
                    && !AngleLimit.plan(t, Aim.wrap180(rel - t), y.limits.second(), y.limits.first()).reachable()) return false;
        }
        GunDriveBlockEntity p = limitedDrive(list, true);
        if (p != null) {
            Vec3 up = new Vec3(0, 1, 0);
            for (GunDriveBlockEntity dr : list) {
                if (!dr.pitch()) {
                    up = SableCompat.dirToWorld(SableCompat.containing(level, dr.getBlockPos()), up).normalize();
                    break;
                }
            }
            double e = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d.dot(up)))));
            if (!AngleLimit.planLinear(0, e, p.limits.second(), p.limits.first()).reachable()) return false;
        }
        return true;
    }

    /** За сколько тиков привод старается довернуть: меньше - быстрее, но с перерегулированием (отклик запаздывает на тик). */
    private static final double DRIVE_TICKS = 3;

    /** Угол поворота вокруг оси a от u к v по их проекциям на плоскость, перпендикулярную a (правый винт). */
    static double signedAngle(Vec3 u, Vec3 v, Vec3 a) {
        Vec3 pu = u.subtract(a.scale(a.dot(u))), pv = v.subtract(a.scale(a.dot(v)));
        if (pu.lengthSqr() < 1e-8 || pv.lengthSqr() < 1e-8) return 0;
        return Math.atan2(a.dot(pu.cross(pv)), pu.dot(pv));
    }

    /** Поворот вектора v вокруг единичной оси k на угол t (формула Родрига). */
    static Vec3 rotate(Vec3 v, Vec3 k, double t) {
        double c = Math.cos(t), sn = Math.sin(t);
        return v.scale(c).add(k.cross(v).scale(sn)).add(k.scale(k.dot(v) * (1 - c)));
    }
}
