import dev.radarfcs.core.*;
import java.util.*;

/**
 * Офлайн-тест ядра (без Minecraft): ./gradlew coreTest
 * 1) баллистика CBC: решение -> 3D-симуляция тем же интегратором, что в CBC -> промах по движущейся цели;
 * 2) ракеты CBC-AT: базовая модель без тяги, обучение по одному выстрелу, повторная проверка;
 * 3) фильтр треков: шумные нерегулярные замеры -> ошибка оценки скорости.
 */
public class SolverTest {
    static int failures = 0;

    /** «Истинный» снаряд CBC в 3D. */
    static final class Truth {
        final double speed, g, k; final boolean quad; final double thrust; final int burn; final double burnG, coastG; final int life;
        Truth(double speed, double g, double k, boolean quad, double thrust, int burn, double burnG, double coastG, int life) {
            this.speed = speed; this.g = g; this.k = k; this.quad = quad; this.thrust = thrust; this.burn = burn;
            this.burnG = burnG; this.coastG = coastG; this.life = life;
        }
        Vec accel(int t, Vec v) {
            double s = v.length();
            Vec u = v.scale(1 / s);
            double d = Math.min(k * (quad ? s * s : s), s);
            double a = -d + (t < burn ? thrust : 0);
            double gm = burn > 0 ? (t < burn ? burnG : coastG) : 1;
            return u.scale(a).add(new Vec(0, g * gm, 0));
        }
    }

    /** Минимальное сближение снаряда и цели (цель движется с ускорением, затем равномерно). */
    static double miss(Truth p, Vec muzzle, Vec dir, TargetMotion target, double lag, List<Vec> velOut) {
        Vec pos = muzzle, vel = dir.normalize().scale(p.speed);
        double best = 1e9;
        if (velOut != null) velOut.add(vel);
        for (int t = 0; t < p.life; t++) {
            Vec a = p.accel(t, vel);
            Vec np = pos.add(vel).add(a.scale(0.5));
            Vec tp = target.predict(lag + t), ntp = target.predict(lag + t + 1);
            for (int i = 0; i <= 10; i++) {
                double f = i / 10.0;
                best = Math.min(best, pos.lerp(np, f).distance(tp.lerp(ntp, f)));
            }
            pos = np;
            vel = vel.add(a);
            if (velOut != null) velOut.add(vel);
        }
        return best;
    }

    static FlightModel modelOf(Truth t) {
        return new CbcFlightModel(t.speed, t.g, t.k, t.quad, t.life, t.thrust, t.burn, t.burnG, t.coastG);
    }

    static TargetMotion randomTarget(Random r, Vec base, double maxDist, boolean accel) {
        double dist = 20 + r.nextDouble() * maxDist;
        double ang = r.nextDouble() * Math.PI * 2;
        Vec p = base.add(new Vec(Math.cos(ang) * dist, (r.nextDouble() - 0.35) * 50, Math.sin(ang) * dist));
        double spd = 0.05 + r.nextDouble() * 0.6;
        double va = r.nextDouble() * Math.PI * 2;
        Vec v = new Vec(Math.cos(va) * spd, (r.nextDouble() - 0.5) * 0.1, Math.sin(va) * spd);
        Vec a = accel ? new Vec((r.nextDouble() - 0.5) * 0.02, 0, (r.nextDouble() - 0.5) * 0.02) : Vec.ZERO;
        return new TargetMotion(p, v, a, false, accel ? 30 : 0);
    }

    static void ballisticCase(String name, Truth truth, double maxDist, boolean accel, double minHitPct) {
        BallisticSolver solver = new BallisticSolver(-30, 60, false);
        FlightModel model = modelOf(truth);
        Random r = new Random(42);
        Vec pivot = new Vec(0, 70, 0);
        BallisticSolver.Muzzle muzzle = BallisticSolver.Muzzle.offset(pivot, 3.0);
        int n = 0, ok = 0, nosol = 0; double worst = 0, sum = 0; long ns = 0;
        for (int i = 0; i < 500; i++) {
            TargetMotion tgt = randomTarget(r, pivot, maxDist, accel);
            double lag = r.nextInt(4);
            long t0 = System.nanoTime();
            BallisticSolver.Solution s = solver.solve(muzzle, tgt, lag, model, 8, Double.NaN, 2000);
            ns += System.nanoTime() - t0;
            if (s == null) { nosol++; continue; }
            double m = miss(truth, s.muzzle(), s.direction(), tgt, lag, null);
            n++; sum += m; worst = Math.max(worst, m); if (m < 1.0) ok++;
        }
        double pct = 100.0 * ok / Math.max(1, n);
        System.out.printf(Locale.ROOT, "%-34s solved %3d (no sol %3d)  miss<1: %5.1f%%  avg %.3f  worst %.3f  %.0f us/solve%n",
                name, n, nosol, pct, sum / Math.max(1, n), worst, ns / 1000.0 / 500);
        if (pct < minHitPct) { failures++; System.out.println("  FAIL: expected >= " + minHitPct + "%"); }
    }

    static void rocketLearning() {
        // ракета: тяга 0.3 бл/тик² 15 тиков, гравитация x0.875 при тяге и x1.3 после
        Truth truth = new Truth(1.5, -0.025, 0.01, false, 0.3, 15, 0.875, 1.3, 120);
        CbcFlightModel base = CbcFlightModel.ballistic(1.5, -0.025, 0.01, false, 120);   // модель без тяги
        BallisticSolver solver = new BallisticSolver(-30, 60, false);
        Vec pivot = new Vec(0, 70, 0);
        BallisticSolver.Muzzle muzzle = BallisticSolver.Muzzle.offset(pivot, 2.0);
        FlightCorrection corr = new FlightCorrection();
        Random r = new Random(7);
        for (int phase = 0; phase < 2; phase++) {
            FlightModel m = corr.apply(base);
            int n = 0, ok = 0;
            double sum = 0;
            for (int i = 0; i < 300; i++) {
                TargetMotion tgt = randomTarget(r, pivot, 220, false);
                BallisticSolver.Solution s = solver.solve(muzzle, tgt, 0, m, 8, Double.NaN, 2000);
                if (s == null) continue;
                List<Vec> vels = phase == 0 && n < 3 ? new ArrayList<>() : null;
                double miss = miss(truth, s.muzzle(), s.direction(), tgt, 0, vels);
                if (vels != null) corr.learn(base, vels.subList(0, Math.min(vels.size(), 80)), 0.3);
                n++; sum += miss; if (miss < 1.5) ok++;
            }
            double pct = 100.0 * ok / Math.max(1, n);
            System.out.printf(Locale.ROOT, "rocket %-27s solved %3d  miss<1.5: %5.1f%%  avg %.2f  (learned ticks %d, speed x%.3f)%n",
                    phase == 0 ? "before learning" : "after 3 observed shots", n, pct, sum / Math.max(1, n),
                    corr.learnedTicks(), corr.speedRatio());
            if (phase == 1 && pct < 90) { failures++; System.out.println("  FAIL: learning did not converge"); }
        }
    }

    static void trackFilter() {
        Random r = new Random(3);
        TrackTable table = new TrackTable();
        double errSum = 0; int n = 0;
        for (int k = 0; k < 200; k++) {
            table.clear();
            Vec p = new Vec(r.nextDouble() * 100, 64, r.nextDouble() * 100);
            double spd = 0.05 + r.nextDouble() * 0.5, h = r.nextDouble() * Math.PI * 2;
            Vec v = new Vec(Math.cos(h) * spd, 0, Math.sin(h) * spd);
            long tick = 0;
            Track t = null;
            for (int s = 0; s < 20; s++) {
                tick += 1 + r.nextInt(3);                                   // нерегулярные замеры
                Vec truePos = p.add(v.scale(tick));
                Vec noisy = truePos.add(new Vec(r.nextGaussian() * 0.03, r.nextGaussian() * 0.03, r.nextGaussian() * 0.03));
                t = table.observe("x", Track.Kind.MOB, "x", noisy, 0.5, tick, false, null, TrackTable.Settings.DEFAULT);
            }
            errSum += t.vel.sub(v).length() / spd;
            n++;
        }
        double rel = errSum / n * 100;
        System.out.printf(Locale.ROOT, "track filter: mean relative velocity error %.1f%%%n", rel);
        if (rel > 15) { failures++; System.out.println("  FAIL: velocity estimate too noisy"); }

        // гистерезис неподвижности
        table.clear();
        Track t = null;
        for (int s = 1; s <= 10; s++) t = table.observe("s", Track.Kind.MOB, "s", new Vec(0.002 * s, 64, 0), 0.5, s * 2L, false, null, TrackTable.Settings.DEFAULT);
        table.classify(0.6 / 20, 3, x -> false, x -> true, Vec.ZERO, 0, 1e9);
        System.out.println("slow target active = " + t.active + " (expected false)");
        if (t.active) failures++;
    }

    public static void main(String[] a) {
        ballisticCase("big cannon 4 charges", new Truth(8, -0.05, 0.01, false, 0, 0, 1, 1, 600), 300, false, 97);
        ballisticCase("big cannon 2 charges", new Truth(4, -0.05, 0.01, false, 0, 0, 1, 1, 600), 200, false, 97);
        ballisticCase("big cannon, accelerating target", new Truth(8, -0.05, 0.01, false, 0, 0, 1, 1, 600), 250, true, 95);
        ballisticCase("autocannon (lifetime 40)", new Truth(7.5, -0.025, 0.01, false, 0, 0, 1, 1, 40), 200, false, 97);
        ballisticCase("quadratic drag", new Truth(6, -0.05, 0.002, true, 0, 0, 1, 1, 600), 200, false, 97);
        rocketLearning();
        trackFilter();
        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("ALL CORE TESTS PASSED");
    }
}
