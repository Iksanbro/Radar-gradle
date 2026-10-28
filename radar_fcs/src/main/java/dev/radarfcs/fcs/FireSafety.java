package dev.radarfcs.fcs;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.BallisticSolver;
import dev.radarfcs.core.IffRule;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.Vec;
import dev.radarfcs.iff.IffRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Проверка траектории перед выстрелом: свои на линии огня и перекрытие рельефом. */
public final class FireSafety {
    public enum Verdict { CLEAR, FRIENDLY, TERRAIN }

    private FireSafety() {}

    /**
     * @param path      точки траектории от дульного среза до точки встречи
     * @param platform  корабли, на которых стоит сама батарея (их корпус не считается помехой)
     */
    public static Verdict check(ServerLevel level, List<Vec> path, Collection<Track> tracks, Track target,
                                IffRule iff, Set<UUID> platform) {
        if (path.size() < 2) return Verdict.CLEAR;
        Vec muzzle = path.get(0);
        double cone = Math.tan(Math.toRadians(FcsConfig.SAFETY_CONE.get()));
        double margin = FcsConfig.SAFETY_MARGIN.get();

        for (Track t : tracks) {
            if (!t.friendly || t == target) continue;
            if (t.handle instanceof SableCompat.Ship ship) {
                if (platform.contains(ship.id())) continue;
                AABB box = ship.box().inflate(margin);
                if (pathHitsBox(path, box)) return Verdict.FRIENDLY;
                continue;
            }
            if (t.pos.distance(muzzle) < 1.0) continue;
            if (pathNear(path, t.pos, t.hitRadius + margin, cone)) return Verdict.FRIENDLY;
        }

        for (IffRegistry.Zone z : IffRegistry.zones(level, iff)) {
            if (z.contains(muzzle)) continue;                               // своя зона - стреляем изнутри
            if (z.contains(target.pos)) continue;                           // цель в своей зоне (назначена вручную и т.п.)
            // через свою зону (база, свой корабль с охранением) по цели снаружи - нельзя
            if (z.box() != null ? pathHitsBox(path, z.box()) : pathNear(path, z.center(), z.radius(), 0)) return Verdict.FRIENDLY;
        }

        if (FcsConfig.REQUIRE_LOS.get() && terrainBlocked(level, path, target)) return Verdict.TERRAIN;
        return Verdict.CLEAR;
    }

    private static boolean pathNear(List<Vec> path, Vec p, double radius, double coneTan) {
        double along = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            Vec a = path.get(i), b = path.get(i + 1);
            Vec ab = b.sub(a);
            double len2 = ab.lengthSqr();
            double f = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, p.sub(a).dot(ab) / len2));
            Vec q = a.addScaled(ab, f);
            double r = radius + (along + Math.sqrt(len2) * f) * coneTan;
            if (q.distance(p) < r) return true;
            along += Math.sqrt(len2);
        }
        return false;
    }

    private static boolean pathHitsBox(List<Vec> path, AABB box) {
        for (int i = 0; i + 1 < path.size(); i++) {
            Vec3 a = mc(path.get(i)), b = mc(path.get(i + 1));
            if (box.contains(a) || box.contains(b) || box.clip(a, b).isPresent()) return true;
        }
        return false;
    }

    /**
     * Блоки на траектории до цели. Попадание в блок у самой цели (на ней или за ней) не считается помехой.
     * У цели с точным габаритом (мобы, игроки) «у самой цели» - в полублоке от габарита: блок перед ней
     * (край стены, за которой пролетает цель) - помеха.
     */
    private static boolean terrainBlocked(ServerLevel level, List<Vec> path, Track target) {
        Vec end = path.get(path.size() - 1);
        double total = 0;
        for (int i = 0; i + 1 < path.size(); i++) total += path.get(i).distance(path.get(i + 1));
        Vec half = target.half;
        double allowedNearTarget = half != null ? 0.5 + Math.max(half.x(), half.z()) : target.hitRadius + 2.0;
        Vec start = path.get(0);
        Vec first = path.get(1).sub(start);
        double firstLen = first.length();
        // первые полтора блока - сам ствол и станок
        Vec a = firstLen > 1.5 ? start.addScaled(first, 1.5 / firstLen) : path.get(1);
        double travelled = Math.min(1.5, firstLen);
        int startIdx = firstLen > 1.5 ? 1 : 2;
        for (int i = startIdx; i <= path.size() - 1; i++) {
            Vec b = path.get(i);
            BlockHitResult hit = level.clip(new ClipContext(mc(a), mc(b), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.BLOCK) {
                Vec h = new Vec(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);
                double nearEnd = half != null ? BallisticSolver.gap(h.sub(end), half) : h.distance(end);
                double nearAllowed = half != null ? 0.5 : allowedNearTarget;
                if (nearEnd > nearAllowed && travelled + a.distance(h) < total - allowedNearTarget) return true;
            }
            travelled += a.distance(b);
            a = b;
        }
        return false;
    }

    private static Vec3 mc(Vec v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
