package dev.radarfcs.fcs;

import dev.radarfcs.block.GunRotatorBlockEntity;
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
 * Орудие на вращателях: прицел, вращатели и станки CBC одной орудийной сети. Вычислитель наводит не станок, а всю
 * постройку: поворачивает вращатели так, чтобы направление прицела совпало с решением. Огонь - станками CBC орудия
 * или, если их нет, сигналом редстоуна от прицела.
 */
public final class Turret {
    public final BlockPos sight;
    public List<BlockPos> rotators = List.of();
    public List<BlockPos> mounts = List.of();
    /** Состояние, которое наводит орудие (остальные станки орудия только стреляют вместе с ним). */
    @Nullable public BlockPos leader;

    // решение ведущего на этот тик - для остальных станков орудия
    public boolean fire;
    public long fireTick = Long.MIN_VALUE / 2;

    public Turret(BlockPos sight) {
        this.sight = sight.immutable();
    }

    @Nullable
    public SightBlockEntity sightEntity(Level level) {
        return level.isLoaded(sight) && level.getBlockEntity(sight) instanceof SightBlockEntity s && !s.isRemoved() ? s : null;
    }

    public List<GunRotatorBlockEntity> rotatorEntities(Level level) {
        List<GunRotatorBlockEntity> out = new ArrayList<>();
        for (BlockPos p : rotators) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof GunRotatorBlockEntity r && !r.isRemoved()) out.add(r);
        }
        return out;
    }

    /**
     * Вращатели, которые поворачивают прицел, от внешнего (основание) к внутреннему: прицел - в под-уровне последнего,
     * тот стоит в под-уровне предыдущего и т.д. Вращатели орудия вне этой цепочки прицел не двигают.
     */
    public static List<GunRotatorBlockEntity> chain(Level level, BlockPos sightPos, List<GunRotatorBlockEntity> rotators) {
        Map<UUID, GunRotatorBlockEntity> bySub = new HashMap<>();
        for (GunRotatorBlockEntity r : rotators) if (r.subLevel() != null) bySub.put(r.subLevel(), r);
        List<GunRotatorBlockEntity> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID sub = SableCompat.containingId(level, sightPos);
        while (sub != null && seen.add(sub)) {
            GunRotatorBlockEntity r = bySub.get(sub);
            if (r == null) break;                                   // дальше - корабль или обычный мир
            out.add(r);
            sub = SableCompat.containingId(level, r.getBlockPos());
        }
        Collections.reverse(out);
        return out;
    }

    /** Итог наведения: нет вращения на нужном вращателе; угловая ошибка прицела, градусов. */
    public record Drive(boolean noPower, double errorDeg) {}

    /**
     * Повернуть вращатели цепочки, чтобы прицел смотрел в want. Замкнутый контур по фактическому положению:
     * для каждого вращателя (от внешнего) поворот - угол между проекциями направления прицела и нужного направления на
     * плоскость, перпендикулярную его оси; поворот внешнего учитывается в направлении прицела и осях внутренних.
     * Каждый тик заново: угол вращателя = фактический угол + поворот.
     */
    public static Drive aim(List<GunRotatorBlockEntity> chain, Vec3 sightDir, Vec3 want, double deadband) {
        Vec3 s = sightDir.normalize(), d = want.normalize();
        List<Vec3> axes = new ArrayList<>();
        for (GunRotatorBlockEntity r : chain) axes.add(r.worldAxis().normalize());
        boolean noPower = false;
        for (int i = 0; i < chain.size(); i++) {
            GunRotatorBlockEntity r = chain.get(i);
            Vec3 a = axes.get(i);
            double delta = signedAngle(s, d, a);
            double deg = Math.toDegrees(delta);
            double angle = r.angle();
            if (Double.isNaN(angle)) continue;
            if (Math.abs(deg) > deadband) {
                r.setWant(angle + deg);
                if (r.degPerTick() <= 0) noPower = true;
            }
            // поворот этого вращателя уносит прицел и оси внутренних вращателей
            s = rotate(s, a, delta);
            for (int j = i + 1; j < axes.size(); j++) axes.set(j, rotate(axes.get(j), a, delta));
        }
        double err = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, sightDir.normalize().dot(d)))));
        return new Drive(noPower, err);
    }

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
