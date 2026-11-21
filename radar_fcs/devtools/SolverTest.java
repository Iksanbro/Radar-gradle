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
    static class Truth {
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
            // как в Minecraft: отрезок снаряда за тик против цели на конец тика
            Vec seg = np.sub(pos);
            double f = seg.lengthSqr() < 1e-12 ? 1 : Math.max(0, Math.min(1, ntp.sub(pos).dot(seg) / seg.lengthSqr()));
            best = Math.min(best, pos.add(seg.scale(f)).distance(ntp));
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

    /**
     * Быстрые цели (1.5..4 бл/тик = 30..80 м/с), любые направления, включая догон и встречный курс:
     * решатель должен сходиться, а оценка промаха BallisticSolver.miss - совпадать с «истинной» 3D-симуляцией.
     */
    static void fastCase(String name, Truth truth, double maxDist, double minHitPct) {
        BallisticSolver solver = new BallisticSolver(-30, 85, false);
        FlightModel model = modelOf(truth);
        Random r = new Random(11);
        Vec pivot = new Vec(0, 70, 0);
        BallisticSolver.Muzzle muzzle = BallisticSolver.Muzzle.offset(pivot, 3.0);
        int n = 0, ok = 0, nosol = 0, agree = 0, edge = 0; double sum = 0;
        for (int i = 0; i < 500; i++) {
            double dist = 25 + r.nextDouble() * maxDist, ang = r.nextDouble() * Math.PI * 2;
            Vec p = pivot.add(new Vec(Math.cos(ang) * dist, 5 + r.nextDouble() * 60, Math.sin(ang) * dist));
            double spd = 1.5 + r.nextDouble() * 2.5, va = r.nextDouble() * Math.PI * 2;
            Vec v = new Vec(Math.cos(va) * spd, (r.nextDouble() - 0.5) * 0.4, Math.sin(va) * spd);
            TargetMotion tgt = new TargetMotion(p, v, Vec.ZERO, false, 0);
            double lag = 1 + r.nextInt(2);
            BallisticSolver.Solution s = solver.solve(muzzle, tgt, lag, model, 16, Double.NaN, 2000);
            if (s == null) { nosol++; continue; }
            double est = BallisticSolver.miss(s.muzzle(), s.direction(), model, tgt, lag, truth.life, true).distance();
            double m0 = miss(truth, s.muzzle(), s.direction(), tgt, lag, null);
            // оценка может быть осторожнее истины (встреча на границе тика не считается), но не наоборот
            if (est >= m0 - 0.05) agree++;
            if (est > m0 + 0.05) edge++;
            // вычислитель ждёт тика, когда выстрел попадёт: пробуем вылет через 0..4 тика
            double m = m0;
            for (int j = 1; j <= 4 && m >= 0.5; j++) {
                BallisticSolver.Solution sj = solver.solve(muzzle, tgt, lag + j, model, 16, Double.NaN, 2000);
                if (sj != null) m = Math.min(m, miss(truth, sj.muzzle(), sj.direction(), tgt, lag + j, null));
            }
            n++; sum += m; if (m < 0.5) ok++;
        }
        double pct = 100.0 * ok / Math.max(1, n), agr = 100.0 * agree / Math.max(1, n);
        System.out.printf(Locale.ROOT, "%-34s solved %3d (no sol %3d)  hit<0.5 in 5 ticks: %5.1f%%  avg %.3f  miss-estimate safe %5.1f%% (tick-edge %4.1f%%)%n",
                name, n, nosol, pct, sum / Math.max(1, n), agr, 100.0 * edge / Math.max(1, n));
        if (pct < minHitPct || agr < 97) { failures++; System.out.println("  FAIL"); }
    }

    /**
     * Ракета исправления CBC-AT (cbcatfix): старт 0.15 бл/тик, пока есть топливо - разгон к крейсерской скорости
     * (ускорение = недобор скорости / время отклика) без гравитации, затем обычный баллистический полёт.
     */
    static final class CruiseRocket extends Truth {
        final double cruise, response; final int powered;
        CruiseRocket(double cruise, double response, int powered) {
            super(0.15, -0.025, 0.01, false, 0, 0, 1, 1, 160);
            this.cruise = cruise; this.response = response; this.powered = powered;
        }
        @Override Vec accel(int t, Vec v) {
            if (t >= powered) return super.accel(t, v);
            double s = v.length(), err = cruise - s;
            double a = err >= 0 ? Math.min(err / response, cruise / response) : err * 0.035;
            return v.scale(1 / s).scale(a);
        }
    }

    static void rocketLearning() {
        // ракета CBC-AT: тяга 0.3 бл/тик² 15 тиков, гравитация x0.875 при тяге и x1.3 после
        rocketLearning("rocket", new Truth(1.5, -0.025, 0.01, false, 0.3, 15, 0.875, 1.3, 120),
                CbcFlightModel.ballistic(1.5, -0.025, 0.01, false, 120));
        // ракета с исправлением CBC-AT: медленный старт и разгон до 3 бл/тик; базовая модель - как у автопушки
        rocketLearning("slow-start rocket", new CruiseRocket(3.0, 3.0, 40), CbcFlightModel.ballistic(9, -0.025, 0.01, false, 160));
    }

    /**
     * Снаряд пробил цель на 5-м тике и резко затормозил: обучение должно остановиться на этом тике
     * и не записать торможение в модель полёта.
     */
    static void penetrationLearning() {
        Truth truth = new Truth(9, -0.025, 0.01, false, 0, 0, 1, 1, 40);
        FlightModel base = modelOf(truth);
        FlightCorrection corr = new FlightCorrection();
        for (int shot = 0; shot < 3; shot++) {
            List<Vec> vels = new ArrayList<>();
            Vec v = new Vec(9, 0, 0);
            vels.add(v);
            for (int t = 0; t < 20; t++) {
                v = v.add(truth.accel(t, v));
                if (t == 5) v = v.scale(0.72);                                  // пробитие: скорость падает на 28 %
                vels.add(v);
            }
            corr.learn(base, vels, 0.3);
        }
        FlightModel m = corr.apply(base);
        double worst = 0;
        for (int t = 0; t < 20; t++) worst = Math.max(worst, Math.abs(m.along(t, 9) - base.along(t, 9)));
        System.out.printf(Locale.ROOT, "penetration: learned ticks %d (expected 5), largest model change %.3f%n", corr.learnedTicks(), worst);
        if (corr.learnedTicks() != 5 || worst > 0.01) { failures++; System.out.println("  FAIL"); }
    }

    static void rocketLearning(String name, Truth truth, CbcFlightModel base) {
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
            System.out.printf(Locale.ROOT, "%-17s %-23s solved %3d  miss<1.5: %5.1f%%  avg %.2f  (learned ticks %d, speed x%.3f)%n",
                    name, phase == 0 ? "before learning" : "after 3 observed shots", n, pct, sum / Math.max(1, n),
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

    /** Разворот цели на 5 бл/тик: окно оценки укорачивается, скорость восстанавливается за несколько тиков. */
    static void maneuver() {
        TrackTable table = new TrackTable();
        Vec p = new Vec(0, 80, 0);
        Track t = null;
        int recovered = -1, flagged = 0;
        for (int tick = 1; tick <= 60; tick++) {
            double z = tick <= 30 ? 5.0 * tick : 150 - 5.0 * (tick - 30);
            t = table.observe("m", Track.Kind.MOB, "m", p.add(new Vec(0, 0, z)), 0.5, tick, false, null, TrackTable.Settings.DEFAULT);
            if (tick > 30 && t.residual > Track.MANEUVER_RESIDUAL) flagged++;
            if (tick > 30 && recovered < 0 && Math.abs(t.vel.z() + 5.0) < 0.1 && t.residual <= Track.MANEUVER_RESIDUAL) recovered = tick - 30;
        }
        System.out.printf(Locale.ROOT, "maneuver: reversal at 5 b/t, velocity recovered after %d ticks, flagged %d ticks%n", recovered, flagged);
        if (recovered < 0 || recovered > 8) { failures++; System.out.println("  FAIL: slow recovery after reversal"); }
        // настоящее ускорение (разгон 0.05 бл/тик²) должно распознаваться, несмотря на выбор модели
        table.clear();
        Random r = new Random(5);
        for (int tick = 1; tick <= 20; tick++) {
            double z = 1.0 * tick + 0.5 * 0.05 * tick * tick;
            t = table.observe("a", Track.Kind.MOB, "a", p.add(new Vec(r.nextGaussian() * 0.02, 0, z + r.nextGaussian() * 0.02)), 0.5, tick,
                    false, null, TrackTable.Settings.DEFAULT);
        }
        System.out.printf(Locale.ROOT, "acceleration: estimated %.4f (true 0.0500), velocity %.3f (true %.3f)%n", t.acc.z(), t.vel.z(), 1.0 + 0.05 * 20);
        if (Math.abs(t.acc.z() - 0.05) > 0.01) { failures++; System.out.println("  FAIL: acceleration not detected"); }
    }

    /**
     * Промах до габарита цели: выстрел над центром высокой цели (моб) - попадание, хотя до центра далеко;
     * сбоку на ту же величину - промах; встреча на самой границе тика не засчитывается.
     */
    static void boxMiss() {
        CbcFlightModel m = CbcFlightModel.ballistic(9, 0, 0, false, 40);
        Vec half = new Vec(0.3, 0.975, 0.3);
        TargetMotion still = new TargetMotion(new Vec(40.5, 0, 0), Vec.ZERO, Vec.ZERO, false, 0);
        double above = BallisticSolver.miss(new Vec(0, 0.8, 0), new Vec(1, 0, 0), m, still, 0, 20, true, half).distance();
        double aboveC = BallisticSolver.miss(new Vec(0, 0.8, 0), new Vec(1, 0, 0), m, still, 0, 20, true, null).distance();
        double side = BallisticSolver.miss(new Vec(0, 0, 0.8), new Vec(1, 0, 0), m, still, 0, 20, true, half).distance();
        // цель поперёк линии огня 4.5 бл/тик: в тик 5 снаряд доходит до x=45, а габарит цели начинается с 44.95 -
        // отрезок задевает его лишь самым концом
        TargetMotion edge = new TargetMotion(new Vec(45.25, 0, -22.5), new Vec(0, 0, 4.5), Vec.ZERO, false, 0);
        double atEdge = BallisticSolver.miss(Vec.ZERO, new Vec(1, 0, 0), m, edge, 0, 20, true, half).distance();
        boolean ok = above < 1e-6 && Math.abs(aboveC - 0.8) < 1e-6 && Math.abs(side - 0.5) < 1e-6 && atEdge > 0.01;
        System.out.printf(Locale.ROOT, "box miss: above centre %.2f (centre %.2f), side %.2f, tick edge %.2f%n", above, aboveC, side, atEdge);
        if (!ok) { failures++; System.out.println("  FAIL"); }
    }

    /** Выбор цели по настройкам вычислителя: уровни типов, режимы приоритета, удержание, переключение. */
    static void targetPicker() {
        int before = failures;
        TrackTable table = new TrackTable();
        Vec pivot = new Vec(0, 64, 0);
        Track a = table.observe("A", Track.Kind.MOB, "A", new Vec(10, 64, 0), 0.5, 5, false, null, TrackTable.Settings.DEFAULT);
        Track b = table.observe("B", Track.Kind.MOB, "B", new Vec(11, 64, 0), 0.5, 1, false, null, TrackTable.Settings.DEFAULT);
        Track c = table.observe("C", Track.Kind.SHIP, "C", new Vec(30, 64, 0), 3, 3, false, null, TrackTable.Settings.DEFAULT);
        a.vel = new Vec(0.5, 0, 0); b.vel = new Vec(2, 0, 0); c.vel = new Vec(1, 0, 0);
        a.volume = 0.5; b.volume = 2; c.volume = 400;
        List<Track> all = List.of(a, b, c);
        EngageSettings s = new EngageSettings();

        // уровни по умолчанию: корабли (2) выше мобов (1)
        expect("levels: ship first", first(all, pivot, s, new TargetPicker.State(), 100), "C");
        s.level[EngageSettings.Category.SHIPS.ordinal()] = 1;
        String[][] modes = {{"NEAREST", "A"}, {"FARTHEST", "C"}, {"EARLIEST", "B"}, {"LARGEST", "C"}, {"SMALLEST", "A"},
                {"FASTEST", "B"}, {"SLOWEST", "A"}};
        for (String[] m : modes) {
            s.priority = EngageSettings.Priority.valueOf(m[0]);
            expect("priority " + m[0], first(all, pivot, s, new TargetPicker.State(), 100), m[1]);
        }
        s.priority = EngageSettings.Priority.NEAREST;

        // удержание: B чуть дальше A - остаёмся на B; A заметно ближе - переходим
        TargetPicker.State st = new TargetPicker.State();
        st.acquire("B", 0);
        expect("keeps current when not clearly better", first(all, pivot, s, st, 100), "B");
        b.pos = new Vec(20, 64, 0);
        expect("switches to clearly nearer", first(all, pivot, s, st, 100), "A");
        b.pos = new Vec(11, 64, 0);

        // переключение по приоритету: цель выше уровнем перехватывает огонь, только если включено
        s.level[EngageSettings.Category.SHIPS.ordinal()] = 3;
        st = new TargetPicker.State();
        st.acquire("A", 0);
        s.switchByPriority = false;
        expect("no switch by priority", first(all, pivot, s, st, 100), "A");
        s.switchByPriority = true;
        expect("switch by priority", first(all, pivot, s, st, 100), "C");
        s.level[EngageSettings.Category.SHIPS.ordinal()] = 1;

        // после N выстрелов: обход всех целей по кругу
        s.switchMode = EngageSettings.SwitchMode.AFTER_SHOTS;
        s.switchShots = 3;
        st = new TargetPicker.State();
        StringBuilder seq = new StringBuilder();
        long now = 100;
        for (int round = 0; round < 6; round++) {
            String id = first(all, pivot, s, st, now);
            st.acquire(id, now);
            seq.append(id);
            st.shotsAtCurrent += 3;
            now += 10;
        }
        expect("after shots: round robin", seq.toString(), "ABCABC");
        // единственная цель: огонь по ней продолжается, счётчик правила заново
        st = new TargetPicker.State();
        st.acquire("A", 0);
        st.shotsAtCurrent = 5;
        expect("single target stays", first(List.of(a), pivot, s, st, 100), "A");
        if (st.shotsAtCurrent != 0) { failures++; System.out.println("  FAIL: counter not reset"); }

        // через время
        s.switchMode = EngageSettings.SwitchMode.AFTER_TIME;
        s.switchTicks = 100;
        st = new TargetPicker.State();
        st.acquire("A", 0);
        expect("after time: not yet", first(all, pivot, s, st, 99), "A");
        expect("after time: switched", first(all, pivot, s, st, 100), "B");

        // при остановке
        s.switchMode = EngageSettings.SwitchMode.ON_STOP;
        st = new TargetPicker.State();
        st.acquire("A", 0);
        a.moving = true;
        expect("on stop: moving", first(all, pivot, s, st, 100), "A");
        a.moving = false;
        a.lastMovingTick = a.lastTick - 5;                     // только что остановилась (разворот) - ещё не стоит
        expect("on stop: brief stop", first(all, pivot, s, st, 100), "A");
        a.lastMovingTick = a.lastTick - s.staticTicks;
        expect("on stop: stopped", first(all, pivot, s, st, 100), "B");
        s.switchMode = EngageSettings.SwitchMode.NONE;

        // назначенная с монитора - вне очереди
        if (!"C".equals(TargetPicker.order(all, pivot, s, new TargetPicker.State(), 100, 0.85, "C").get(0).id)) {
            failures++; System.out.println("  FAIL: designated");
        }

        // фильтры и режимы огня
        EngageSettings f = new EngageSettings();
        java.util.Arrays.fill(f.speedMin, 10); java.util.Arrays.fill(f.speedMax, 50);
        f.speedMin[EngageSettings.Category.SHELLS.ordinal()] = 0.1; f.speedMax[EngageSettings.Category.SHELLS.ordinal()] = 0;
        // стоящая с начала, движущаяся, на миг остановившаяся, давно стоящая
        Track still = table.observe("S1", Track.Kind.MOB, "S1", new Vec(5, 64, 0), 0.5, 50, false, null, TrackTable.Settings.DEFAULT);
        Track mov = table.observe("S2", Track.Kind.MOB, "S2", new Vec(6, 64, 0), 0.5, 50, false, null, TrackTable.Settings.DEFAULT);
        Track brief = table.observe("S3", Track.Kind.MOB, "S3", new Vec(7, 64, 0), 0.5, 50, false, null, TrackTable.Settings.DEFAULT);
        Track parked = table.observe("S4", Track.Kind.MOB, "S4", new Vec(8, 64, 0), 0.5, 50, false, null, TrackTable.Settings.DEFAULT);
        mov.moving = true; brief.lastMovingTick = 45; parked.lastMovingTick = 20;
        f.staticTicks = 20;
        EngageSettings.Category M = EngageSettings.Category.MOBS;
        boolean ok = !f.speedAllowed(M, 5) && f.speedAllowed(M, 30) && !f.speedAllowed(M, 60)
                && f.speedAllowed(EngageSettings.Category.SHELLS, 200) && !f.speedAllowed(EngageSettings.Category.SHELLS, 0.05)
                && !f.motionAllowed(still) && f.motionAllowed(mov) && f.motionAllowed(brief) && !f.motionAllowed(parked);
        f.dropStatic = false; f.dropMoving = true;
        ok &= f.motionAllowed(still) && !f.motionAllowed(mov) && !f.motionAllowed(brief) && f.motionAllowed(parked);
        f.staticTicks = 0;
        ok &= f.motionAllowed(brief);
        f.fireMode = EngageSettings.FireMode.SINGLE;
        ok &= f.roundsPerSalvo() == 1;
        f.fireMode = EngageSettings.FireMode.BURST; f.burstLength = 7;
        ok &= f.roundsPerSalvo() == 7;
        f.fireMode = EngageSettings.FireMode.AUTO;
        ok &= f.roundsPerSalvo() == Integer.MAX_VALUE;
        f.burstLength = 0; f.level[0] = 99; f.speedMin[0] = Double.NaN; f.speedMax[1] = 0.04; f.speedMin[2] = 3.14159; f.switchShots = -4; f.switchTicks = 0;
        f.sanitize();
        ok &= f.burstLength == 2 && f.level[0] == EngageSettings.MAX_LEVEL && f.speedMin[0] == 0 && f.speedMax[1] == 0
                && f.speedMin[2] == 3.1 && f.switchShots == 1 && f.switchTicks == 2;
        ok &= EngageSettings.Category.of(Track.Kind.PROJECTILE) == EngageSettings.Category.SHELLS
                && EngageSettings.Category.of(Track.Kind.SHIP) == EngageSettings.Category.SHIPS;
        f.threatRadius = 9999; f.threat = null;
        f.sanitize();
        ok &= f.threatRadius == EngageSettings.MAX_THREAT_RADIUS && f.threat == EngageSettings.Threat.THREATENING;
        if (!ok) { failures++; System.out.println("  FAIL: settings filters"); }
        System.out.println("target picker: " + (failures == before ? "all checks passed" : (failures - before) + " failed"));
    }

    static String first(List<Track> cands, Vec pivot, EngageSettings s, TargetPicker.State st, long now) {
        List<Track> o = TargetPicker.order(cands, pivot, s, st, now, 0.85, null);
        return o.isEmpty() ? "-" : o.get(0).id;
    }

    static void expect(String what, String got, String want) {
        if (!want.equals(got)) { failures++; System.out.println("  FAIL " + what + ": got " + got + ", expected " + want); }
    }

    /**
     * Перехват снарядов: прогноз пути по тикам, непрерывная встреча двух снарядов (как CBC Interception: отрезки обоих
     * за тик), взвод (первые тики не сталкиваются), скорость сближения, решение по летящему снаряду, порядок перехвата.
     */
    static void interception() {
        int before = failures;
        // путь: внутри тика - по прямой, после конца - с последней скоростью
        PathMotion path = new PathMotion(new Vec[]{new Vec(0, 0, 0), new Vec(4, 1, 0), new Vec(8, 1.5, 0)});
        boolean ok = path.predict(0.5).distance(new Vec(2, 0.5, 0)) < 1e-9 && path.predict(3).distance(new Vec(12, 2, 0)) < 1e-9
                && path.pos().equals(new Vec(0, 0, 0)) && path.velocity(1).distance(new Vec(4, 0.5, 0)) < 1e-9;
        TargetPath shifted = path.relativeTo(new Vec(1, 0, 0));
        ok &= shifted.predict(2).distance(new Vec(6, 1.5, 0)) < 1e-9;
        if (!ok) { failures++; System.out.println("  FAIL: path motion"); }

        // снаряд-цель летит навстречу по прямой 5 бл/тик, перехватчик 6 бл/тик без сопротивления
        FlightModel interceptor = CbcFlightModel.ballistic(6, 0, 0, false, 60);
        Vec[] pts = new Vec[60];
        for (int k = 0; k < pts.length; k++) pts[k] = new Vec(200 - 5.0 * k, 10, 0.3);
        PathMotion incoming = new PathMotion(pts);
        BallisticSolver solver = new BallisticSolver(-10, 60, false).discrete(false);
        BallisticSolver.Muzzle muzzle = BallisticSolver.Muzzle.fixed(new Vec(0, 10, 0));
        BallisticSolver.Solution sol = solver.solve(muzzle, incoming, 1, interceptor, 16, Double.NaN, 400);
        if (sol == null) {
            failures++;
            System.out.println("  FAIL: no solution against an incoming shell");
        } else {
            BallisticSolver.Miss m = BallisticSolver.miss(new Vec(0, 10, 0), sol.direction(), interceptor, incoming, 1, 80, false, null, 2);
            System.out.printf(Locale.ROOT, "  head-on shell: miss %.3f at tick %.2f, closing %.2f b/t%n", m.distance(), m.tick(), m.closing());
            if (m.distance() > 0.1 || Math.abs(m.closing() - 11) > 0.01) { failures++; System.out.println("  FAIL: head-on interception"); }
        }
        // взвод: встреча на первом тике полёта не считается
        Vec[] close = new Vec[10];
        for (int k = 0; k < close.length; k++) close[k] = new Vec(9 - 3.0 * k, 10, 0);
        BallisticSolver.Miss early = BallisticSolver.miss(new Vec(0, 10, 0), new Vec(1, 0, 0), interceptor, new PathMotion(close), 0, 20,
                false, null, 0);
        BallisticSolver.Miss armed = BallisticSolver.miss(new Vec(0, 10, 0), new Vec(1, 0, 0), interceptor, new PathMotion(close), 0, 20,
                false, null, 2);
        if (!(early.distance() < 0.01 && early.tick() < 1.1 && armed.distance() > 3)) {
            failures++;
            System.out.printf(Locale.ROOT, "  FAIL: arming (unarmed %.2f at %.2f, armed %.2f)%n", early.distance(), early.tick(), armed.distance());
        }

        // порядок: снаряды равного уровня - кто раньше долетит; ракета уровнем выше - первой
        TrackTable table = new TrackTable();
        Vec pivot = new Vec(0, 64, 0);
        Track near = table.observe("N", Track.Kind.PROJECTILE, "N", new Vec(30, 70, 0), 0.4, 1, false, null, TrackTable.Settings.DEFAULT);
        Track soon = table.observe("S", Track.Kind.PROJECTILE, "S", new Vec(80, 70, 0), 0.4, 1, false, null, TrackTable.Settings.DEFAULT);
        Track mob = table.observe("M", Track.Kind.MOB, "M", new Vec(5, 64, 0), 0.4, 1, false, null, TrackTable.Settings.DEFAULT);
        near.munition = ProjectileType.SHELL; soon.munition = ProjectileType.SHELL;
        near.threatTicks = 30; soon.threatTicks = 8;
        EngageSettings s = new EngageSettings();
        List<Track> all = List.of(near, soon, mob);
        expect("intercept: sooner impact first", first(all, pivot, s, new TargetPicker.State(), 100), "S");
        s.impactFirst = false;
        expect("intercept: nearest without impact order", first(all, pivot, s, new TargetPicker.State(), 100), "N");
        s.impactFirst = true;
        near.munition = ProjectileType.ROCKET;
        s.level[EngageSettings.Category.ROCKETS.ordinal()] = 5;
        expect("intercept: rocket level first", first(all, pivot, s, new TargetPicker.State(), 100), "N");
        s.level[EngageSettings.Category.ROCKETS.ordinal()] = 4;
        s.level[EngageSettings.Category.MOBS.ordinal()] = 5;
        expect("intercept: mob level above shells", first(all, pivot, s, new TargetPicker.State(), 100), "M");
        System.out.println("interception: " + (failures == before ? "all checks passed" : (failures - before) + " failed"));
    }

    /** Свои цели пушки: типы и уровни вместо общих; сенсор берёт объединение типов. */
    static void mountTargets() {
        EngageSettings s = new EngageSettings();
        int mobs = EngageSettings.Category.MOBS.ordinal(), ent = EngageSettings.Category.ENTITIES.ordinal();
        s.enabled[ent] = false;
        MountTargets mt = MountTargets.from(s);
        mt.enabled[mobs] = false;
        mt.enabled[ent] = true;
        mt.level[ent] = 5;
        s.mountTargets.put(42L, mt);
        EngageSettings a = s.forMount(42L), b = s.forMount(7L), u = s.sensorUnion();
        boolean ok = !a.enabled[mobs] && a.enabled[ent] && a.level[ent] == 5 && b == s && b.enabled[mobs] && !b.enabled[ent]
                && u.enabled[mobs] && u.enabled[ent] && s.copy().mountTargets.get(42L) != mt && s.copy().mountTargets.get(42L).level[ent] == 5;
        mt.level[mobs] = 99;
        s.sanitize();
        ok &= mt.level[mobs] == EngageSettings.MAX_LEVEL;
        if (!ok) failures++;
        System.out.println("mount targets: " + (ok ? "all checks passed" : "failed"));
    }

    /** Шаг колеса мыши: до 1 по 0.1, дальше по 1, Shift - x10; 0 - «нет». */
    static void wheelSteps() {
        double[][] cases = {
                // v, dir, shift(0/1), min, allowZero(0/1), ожидание
                {0, 1, 0, 0.1, 1, 0.1}, {0.1, 1, 0, 0.1, 1, 0.2}, {0.9, 1, 0, 0.1, 1, 1.0}, {1.0, 1, 0, 0.1, 1, 2.0},
                {2.0, -1, 0, 0.1, 1, 1.0}, {1.0, -1, 0, 0.1, 1, 0.9}, {0.1, -1, 0, 0.1, 1, 0}, {0.1, -1, 0, 0.1, 0, 0.1},
                {0.3, 1, 1, 0.1, 1, 1.3}, {5, 1, 1, 0.1, 1, 15}, {5, -1, 1, 0.1, 1, 0.1}, {0, 1, 1, 0.1, 1, 1.0},
                {999, 1, 1, 0.1, 1, 1000}};
        boolean ok = true;
        for (double[] c : cases) {
            double got = WheelStep.fractional(c[0], (int) c[1], c[2] > 0, c[3], 1000, c[4] > 0);
            if (Math.abs(got - c[5]) > 1e-9) {
                ok = false;
                System.out.printf(Locale.ROOT, "  FAIL: wheel %.1f dir %d shift %s -> %.2f, expected %.2f%n", c[0], (int) c[1], c[2] > 0, got, c[5]);
            }
        }
        ok &= WheelStep.integer(4, 1, false, 1, 5) == 5 && WheelStep.integer(4, 1, true, 1, 5) == 5 && WheelStep.integer(30, -1, true, 1, 100) == 20;
        if (!ok) failures++;
        System.out.println("wheel steps: " + (ok ? "all checks passed" : "failed"));
    }

    /** Пределы поворота: длинной дорогой в обход запретного сектора, у предела - ближайший к цели предел. */
    static void angleLimits() {
        boolean ok = true;
        int F = AngleLimit.FREE;
        // без пределов - как есть
        ok &= AngleLimit.plan(0, 170, F, F).delta() == 170 && AngleLimit.plan(0, 170, F, F).reachable();
        // вправо можно 270, влево 10: из 0 к -170 (влево 170) - запрещено коротко, идём вправо на 190
        AngleLimit.Plan p = AngleLimit.plan(0, -170, 10, 270);
        ok &= Math.abs(p.delta() - 190) < 1e-9 && p.reachable();
        // в пределах - коротким путём
        p = AngleLimit.plan(0, 45, 90, 90);
        ok &= Math.abs(p.delta() - 45) < 1e-9 && p.reachable();
        // сектор ±90: цель сзади-слева (-135) недостижима - к ближнему пределу (-90)
        p = AngleLimit.plan(0, -135, 90, 90);
        ok &= Math.abs(p.delta() + 90) < 1e-9 && !p.reachable();
        // цель сзади-справа (+150) - к +90
        p = AngleLimit.plan(30, 120, 90, 90);
        ok &= Math.abs(p.delta() - 60) < 1e-9 && !p.reachable();
        // уже повернули на 350 вправо (предел 360): к цели «на 20 правее» нельзя - обратно на 340
        p = AngleLimit.plan(350, 20, 360, 360);
        ok &= Math.abs(p.delta() + 340) < 1e-9 && p.reachable();
        // предел только в одну сторону: влево 30, вправо без предела; цель -60 - обход вправо на 300
        p = AngleLimit.plan(0, -60, 30, F);
        ok &= Math.abs(p.delta() - 300) < 1e-9 && p.reachable();
        // накопленный угол: переход через 180 не теряет оборот
        double t = AngleLimit.unwrap(170, 190);
        ok &= Math.abs(t - 190) < 1e-9 && Math.abs(AngleLimit.unwrap(190, -165) - 195) < 1e-9;
        // наклон: вверх 30, вниз 5
        p = AngleLimit.planLinear(0, 45, 5, 30);
        ok &= Math.abs(p.delta() - 30) < 1e-9 && !p.reachable();
        p = AngleLimit.planLinear(10, -12, 5, 30);
        ok &= Math.abs(p.delta() + 12) < 1e-9 && p.reachable();
        p = AngleLimit.planLinear(0, -20, 5, F);
        ok &= Math.abs(p.delta() + 5) < 1e-9 && !p.reachable();
        ok &= AngleLimit.clean(0) == 1 && AngleLimit.clean(400) == F && AngleLimit.free(F) && !AngleLimit.free(360);
        if (!ok) failures++;
        System.out.println("angle limits: " + (ok ? "all checks passed" : "failed"));
    }

    /** Поворот вектора v вокруг единичной оси a на угол ang (Родриг). */
    static Vec rot(Vec v, Vec a, double ang) {
        double c = Math.cos(ang), sn = Math.sin(ang);
        return v.scale(c).add(a.cross(v).scale(sn)).add(a.scale(a.dot(v) * (1 - c)));
    }

    /**
     * Мост через регуляторы Flight Control: регулятор угловой скорости догоняет desiredRates (запаздывание 0.2 с, предел
     * 0.4 рад/с по оси), регулятор скорости - desiredVelocity (0.5 с). Корабль носом на восток, цель уходит на север 4 бл/с:
     * нос на цели, дальность 30, высота над целью +12; с креном - в повороте крен, в конце ровно.
     */
    static boolean couplerFlight(boolean roll, boolean pitch) {
        Vec f = new Vec(1, 0, 0), u = Vec.UP, w = Vec.ZERO, ship = new Vec(0, 80, 0), vel = Vec.ZERO;
        Vec target = new Vec(60, 60, -40), tv = new Vec(0, 0, -4);
        double dt = 0.02, maxBank = 0, minBank = 0;
        for (int i = 0; i < 50 * 60; i++) {
            target = target.addScaled(tv, dt);
            Vec wd = Autopilot.desiredRates(f, u, target.sub(ship), true, pitch, roll, 45, 30);
            Vec wc = new Vec(Autopilot.clamp(wd.x(), -0.4, 0.4), Autopilot.clamp(wd.y(), -0.4, 0.4), Autopilot.clamp(wd.z(), -0.4, 0.4));
            w = w.add(wc.sub(w).scale(dt / 0.2));
            double wl = w.length();
            if (wl > 1e-12) {
                Vec ax = w.scale(1 / wl);
                f = rot(f, ax, wl * dt).normalize();
                u = rot(u, ax, wl * dt).normalize();
            }
            Vec vd = Autopilot.desiredVelocity(Autopilot.Mode.DISTANCE_ALTITUDE, 30, 12, 15, ship, target, tv, Double.NaN);
            vel = vel.add(vd.sub(vel).scale(dt / 0.5));
            ship = ship.addScaled(vel, dt);
            Vec side = f.cross(Vec.UP).normalize();
            double bank = Math.toDegrees(Math.atan2(u.dot(side), u.dot(Vec.UP.sub(f.scale(f.y())).normalize())));
            maxBank = Math.max(maxBank, bank);
            minBank = Math.min(minBank, bank);
        }
        double yawErr = Math.toDegrees(Math.abs(Autopilot.yawError(f, target.sub(ship))));
        double dist = target.sub(ship).horizontalLength(), height = ship.y() - target.y();
        Vec side = f.cross(Vec.UP).normalize();
        double bank = Math.toDegrees(Math.atan2(u.dot(side), u.dot(Vec.UP.sub(f.scale(f.y())).normalize())));
        boolean ok = yawErr < 2 && Math.abs(dist - 30) < 1.5 && Math.abs(height - 12) < 0.5 && Math.abs(bank) < 2
                && (roll ? maxBank > 5 || minBank < -5 : Math.abs(maxBank) < 3 && Math.abs(minBank) < 3);
        System.out.printf(java.util.Locale.ROOT, "autopilot couplers roll=%s pitch=%s: heading err %.2f, distance %.2f, height %.2f, bank %.2f (turn %.1f..%.1f)%n",
                roll, pitch, yawErr, dist, height, bank, minBank, maxBank);
        return ok;
    }

    /**
     * Ориентация моста: тело с единичной инерцией, нос на восток, цель слева-сверху (на северо-востоке, угол места 20°).
     * Только рыскание - курс на цель, нос и крыло мост держит ровно; + тангаж - нос на угол места цели;
     * + крен - в повороте влево крен влево (до предела 30°), когда нос на цели - крыло ровно. Предел тангажа 10° -
     * нос поднимается только на 10°.
     */
    static boolean attitude(boolean yaw, boolean pitch, boolean roll, double maxPitch) {
        Vec f = new Vec(1, 0, 0), u = Vec.UP, w = Vec.ZERO;
        Vec dir = new Vec(Math.cos(Math.toRadians(20)) * Math.cos(Math.toRadians(60)), Math.sin(Math.toRadians(20)),
                -Math.cos(Math.toRadians(20)) * Math.sin(Math.toRadians(60)));
        double dt = 0.01, minBank = 0, maxBank = 0;
        for (int i = 0; i < 100 * 20; i++) {
            Vec a = Autopilot.attitudeAccel(f, u, w, dir, yaw, pitch, roll, maxPitch, 30);
            w = w.addScaled(a, dt);
            double wl = w.length();
            if (wl > 1e-12) {
                Vec ax = w.scale(1 / wl);
                f = rot(f, ax, wl * dt).normalize();
                u = rot(u, ax, wl * dt).normalize();
            }
            Vec side = f.cross(Vec.UP).normalize();
            Vec level = Vec.UP.sub(f.scale(f.y())).normalize();
            double bank = Math.toDegrees(Math.atan2(u.dot(side), u.dot(level)));
            minBank = Math.min(minBank, bank);
            maxBank = Math.max(maxBank, bank);
        }
        double yawErr = Math.toDegrees(Math.abs(Autopilot.yawError(f, dir)));
        double elev = Math.toDegrees(Math.atan2(f.y(), f.horizontalLength()));
        Vec side = f.cross(Vec.UP).normalize();
        double bank = Math.toDegrees(Math.atan2(u.dot(side), u.dot(Vec.UP.sub(f.scale(f.y())).normalize())));
        boolean ok = (!yaw || yawErr < 1)
                && (pitch ? Math.abs(elev - Math.min(20, maxPitch)) < 1 : Math.abs(elev) < 1)
                && (roll ? minBank < -10 && maxBank < 1 && Math.abs(bank) < 1 && minBank >= -33 : Math.abs(bank) < 1 && minBank > -10 && maxBank < 10);
        System.out.printf(java.util.Locale.ROOT, "autopilot attitude yaw=%s pitch=%s roll=%s maxPitch=%.0f: heading err %.2f, elevation %.2f, bank now %.2f (turn %.1f..%.1f)%n",
                yaw, pitch, roll, maxPitch, yawErr, elev, bank, minBank, maxBank);
        return ok;
    }

    /**
     * Автопилот моста Flight Control: знак ошибки курса; корабль (точка с инерцией, поворот вокруг +Y) гонится за целью,
     * идущей на 6 бл/с: нос к цели, дальность 48 по горизонтали, в режиме с высотой - высота +16 над целью.
     */
    static void autopilot() {
        boolean ok = true;
        // восток -> север: поворот против часовой (если смотреть сверху) = +90°
        ok &= Math.abs(Autopilot.yawError(new Vec(1, 0, 0), new Vec(0, 0, -1)) - Math.PI / 2) < 1e-9;
        ok &= Math.abs(Autopilot.yawError(new Vec(0, 0, -1), new Vec(1, 0, 0)) + Math.PI / 2) < 1e-9;
        ok &= Double.isNaN(Autopilot.yawError(new Vec(0, 1, 0), new Vec(1, 0, 0)));
        ok &= Autopilot.accel(Autopilot.Mode.OFF, 10, 0, 20, Vec.ZERO, Vec.ZERO, new Vec(100, 0, 0), Vec.ZERO).length() == 0;
        for (Autopilot.Mode mode : new Autopilot.Mode[]{Autopilot.Mode.DISTANCE, Autopilot.Mode.DISTANCE_ALTITUDE}) {
            Vec ship = new Vec(0, 70, 0), vel = Vec.ZERO, target = new Vec(150, 60, 90), tv = new Vec(6, 0, -2);
            double yaw = 0, rate = 0, dt = 0.05;                     // нос: (cos yaw, 0, -sin yaw), сначала на восток
            double maxErr = 0, dist = 0, alt = 0;
            for (int i = 0; i < 20 * 60; i++) {
                target = target.addScaled(tv, dt);
                Vec fwd = new Vec(Math.cos(yaw), 0, -Math.sin(yaw));
                double err = Autopilot.yawError(fwd, target.sub(ship));
                rate += Autopilot.yawAccel(err, rate) * dt;
                yaw += rate * dt;
                Vec a = Autopilot.accel(mode, 48, 16, 20, ship, vel, target, tv, 70);
                if (i == 0) vel = new Vec(0, 3, 0);                      // начальная вертикальная скорость - высота 70 держится
                vel = vel.addScaled(a, dt);
                ship = ship.addScaled(vel, dt);
                if (i > 20 * 40) {
                    maxErr = Math.max(maxErr, Math.abs(Math.toDegrees(err)));
                    dist = Math.max(dist, Math.abs(target.sub(ship).horizontalLength() - 48));
                    alt = Math.max(alt, Math.abs(ship.y() - target.y() - 16));
                }
            }
            boolean m = maxErr < 2 && dist < 1.5 && (mode == Autopilot.Mode.DISTANCE ? Math.abs(ship.y() - 70) < 0.5 : alt < 0.5);
            System.out.printf(java.util.Locale.ROOT, "autopilot %s: heading error %.2f deg, distance error %.2f, altitude error %.2f%n",
                    mode, maxErr, dist, mode == Autopilot.Mode.DISTANCE ? Math.abs(ship.y() - 70) : alt);
            ok &= m;
        }
        ok &= attitude(true, false, false, 45);
        ok &= attitude(true, true, false, 45);
        ok &= attitude(true, true, true, 45);
        ok &= attitude(true, true, true, 10);
        ok &= attitude(false, true, false, 45);
        ok &= couplerFlight(false, false);
        ok &= couplerFlight(true, false);
        ok &= couplerFlight(true, true);
        ok &= Autopilot.attitudeAccel(new Vec(1, 0, 0), Vec.UP, new Vec(0.1, 0.2, 0.3), new Vec(0, 1, 1), false, false, false, 45, 30).length() == 0;
        if (!ok) failures++;
        System.out.println("autopilot: " + (ok ? "all checks passed" : "failed"));
    }


    /**
     * Подлёт к стыковочному порту через регуляторы Flight Control (как couplerFlight: запаздывание угловой скорости
     * 0.2 с, предел 0.4 рад/с, скорости 0.5 с). Корабль - точка с осями: нос f, верх u; штекеры в осях корабля
     * (вперёд, вверх, вправо). Итог печатается: фаза, промах самого дальнего штекера от своего места (конец порта +
     * GAP по его оси), ошибка разворота, ровность, промах расстановки.
     */
    static Docking.Command dockFlight(String name, Vec ship, Vec f, Vec u, List<Vec> plugOffs, Vec plugDirBody, Docking.Port port,
                                     boolean mayEnter, int seconds) {
        Vec w = Vec.ZERO, vel = Vec.ZERO;
        double dt = 0.05;
        Docking.Phase ph = Docking.Phase.TRANSIT;
        Docking.Command c = null;
        double cruise = Double.NaN;
        minClear = Double.POSITIVE_INFINITY;
        for (int i = 0; i < seconds * 20; i++) {
            List<Vec> tips = tips(ship, f, u, plugOffs);
            Vec r = f.cross(u).normalize();
            Vec pd = f.scale(plugDirBody.x()).add(u.scale(plugDirBody.y())).add(r.scale(plugDirBody.z()));
            if (Double.isNaN(cruise)) cruise = Docking.centroid(tips).y();
            c = Docking.steer(ph, port, tips, pd, u, 15, cruise, mayEnter);
            ph = c.phase();
            if (ph != Docking.Phase.FINAL) minClear = Math.min(minClear, Docking.centroid(tips).distance(port.tip()));
            Vec wc = new Vec(Autopilot.clamp(c.rates().x(), -0.4, 0.4), Autopilot.clamp(c.rates().y(), -0.4, 0.4),
                    Autopilot.clamp(c.rates().z(), -0.4, 0.4));
            w = w.add(wc.sub(w).scale(dt / 0.2));
            double wl = w.length();
            if (wl > 1e-12) {
                Vec ax = w.scale(1 / wl);
                f = rot(f, ax, wl * dt).normalize();
                u = rot(u, ax, wl * dt).normalize();
            }
            vel = vel.add(c.velocity().sub(vel).scale(dt / 0.5));
            ship = ship.addScaled(vel, dt);
        }
        double worst = 0;
        for (Vec t : tips(ship, f, u, plugOffs)) {
            double best = 1e9;
            for (Vec q : port.tips()) best = Math.min(best, t.distance(q.addScaled(port.facing(), Docking.GAP)));
            worst = Math.max(worst, best);
        }
        lastWorst = worst;
        System.out.printf(Locale.ROOT, "  dock %-30s phase %-8s worst plug %.2f bl, err %.1f deg, level %.3f, layout %.2f bl %s, compatible %s%n",
                name, c.phase(), worst, c.attitudeErrorDeg(), u.dot(Vec.UP), c.layoutError(), c.layoutOk() ? "ok" : "MISMATCH", c.compatible());
        return c;
    }

    static Docking.Command dockFlight(String name, Vec ship, Vec f, Vec u, Vec plugOff, Vec plugDirBody, Docking.Port port,
                                     boolean mayEnter, int seconds) {
        return dockFlight(name, ship, f, u, List.of(plugOff), plugDirBody, port, mayEnter, seconds);
    }

    static List<Vec> tips(Vec ship, Vec f, Vec u, List<Vec> offs) {
        Vec r = f.cross(u).normalize();
        List<Vec> out = new ArrayList<>();
        for (Vec o : offs) out.add(ship.add(f.scale(o.x())).add(u.scale(o.y())).add(r.scale(o.z())));
        return out;
    }

    static double lastWorst, minClear;

    /** Порт из нескольких портов: середина, направление, концы = середина + смещения. */
    static Docking.Port multiPort(Vec center, Vec facing, Vec... offs) {
        List<Vec> t = new ArrayList<>();
        for (Vec o : offs) t.add(center.add(o));
        return new Docking.Port(Docking.centroid(t), facing, Vec.ZERO, t);
    }

    static Vec rotYTest(Vec v, double a) {
        double c = Math.cos(a), sn = Math.sin(a);
        return new Vec(v.x() * c + v.z() * sn, v.y(), -v.x() * sn + v.z() * c);
    }

    static void docking() {
        boolean ok = true;
        // площадка из двух портов вверх по X; у корабля два порта снизу вдоль носа - доворот на 90°
        Docking.Port pad2 = multiPort(new Vec(20.5, 64.5, -10.5), Vec.UP, new Vec(-4, 0, 0), new Vec(4, 0, 0));
        Docking.Command c2 = dockFlight("two pad ports, yaw 90", new Vec(-20, 100, 20), new Vec(0, 0, -1), Vec.UP,
                List.of(new Vec(4, -3, 0), new Vec(-4, -3, 0)), new Vec(0, -1, 0), pad2, true, 90);
        ok &= c2.phase() == Docking.Phase.FINAL && lastWorst < 0.3 && c2.layoutOk();
        // три порта вверх «уголком» (несимметрично); у корабля такой же уголок, повёрнутый на 130° - доворот на 130°,
        // каждый порт к своему
        Vec[] corner = {new Vec(0, 0, 0), new Vec(5, 0, 0), new Vec(0, 0, 3)};
        Docking.Port pad3 = multiPort(new Vec(-30.5, 70.5, 40.5), Vec.UP, corner);
        Vec cc = Docking.centroid(List.of(corner));
        List<Vec> plug3 = new ArrayList<>();
        for (Vec o : corner) {
            Vec wv = rotYTest(o.sub(cc), Math.toRadians(-130));               // мир при носе на север: вправо = +X, вперёд = -Z
            plug3.add(new Vec(-wv.z(), -3, wv.x()));
        }
        Docking.Command c3 = dockFlight("three pad ports, yaw 130", new Vec(30, 105, -20), new Vec(0, 0, -1), Vec.UP, plug3,
                new Vec(0, -1, 0), pad3, true, 120);
        ok &= c3.phase() == Docking.Phase.FINAL && lastWorst < 0.3 && c3.layoutOk();
        // два порта в стене (смотрят на восток) один над другим; у корабля два порта справа один над другим
        Docking.Port wall2 = multiPort(new Vec(0.5, 66.5, 0.5), new Vec(1, 0, 0), new Vec(0, -2, 0), new Vec(0, 2, 0));
        Docking.Command cw = dockFlight("two wall ports, stacked", new Vec(-60, 95, 50), new Vec(0, 0, -1), Vec.UP,
                List.of(new Vec(0, 2, 3), new Vec(0, -2, 3)), new Vec(0, 0, 1), wall2, true, 90);
        ok &= cw.phase() == Docking.Phase.FINAL && lastWorst < 0.3 && cw.layoutOk();
        // корабль за стеной порта (порт смотрит на восток, корабль с запада на той же высоте): облетает, не подходя
        // ближе дистанции обхода, пока не выйдет на ось
        for (double clear : new double[]{8, 16}) {
            Docking.Port wallPort = new Docking.Port(new Vec(0.5, 64.5, 0.5), new Vec(1, 0, 0), Vec.ZERO).withClearance(clear);
            Docking.Command cb = dockFlight("behind the wall, clearance " + (int) clear, new Vec(-25, 64, 3), new Vec(0, 0, -1), Vec.UP,
                    List.of(new Vec(0, 0, 3)), new Vec(0, 0, 1), wallPort, true, 120);
            System.out.printf(Locale.ROOT, "    min distance before final approach %.1f (clearance %.0f)%n", minClear, clear);
            ok &= cb.phase() == Docking.Phase.FINAL && lastWorst < 0.3 && minClear >= clear * 0.85;
        }
        // отход после расстыковки: по оси порта до дистанции обхода, дальше - свободен
        Vec dep = Docking.depart(new Vec(1, 64.5, 0.5), new Vec(0.5, 64.5, 0.5), new Vec(1, 0, 0), 12, 20);
        ok &= dep != null && dep.x() >= Docking.DEPART_SPEED - 1e-9 && Math.abs(dep.y()) < 1e-9;
        ok &= Docking.depart(new Vec(13, 64.5, 0.5), new Vec(0.5, 64.5, 0.5), new Vec(1, 0, 0), 12, 20) == null;
        System.out.println("  depart: " + dep);
        // расстановки разные (6 бл у корабля, 8 у порта) - предупреждение
        Docking.Port pad8 = multiPort(new Vec(60.5, 64.5, 60.5), Vec.UP, new Vec(-4, 0, 0), new Vec(4, 0, 0));
        Docking.Command cx = dockFlight("layout mismatch", new Vec(40, 95, 40), new Vec(0, 0, -1), Vec.UP,
                List.of(new Vec(3, -3, 0), new Vec(-3, -3, 0)), new Vec(0, -1, 0), pad8, true, 60);
        ok &= !cx.layoutOk();
        // порт в стене смотрит на восток; штекер справа от корабля, корабль носом на север далеко и высоко
        Docking.Port east = new Docking.Port(new Vec(0.5, 64.5, 0.5), new Vec(1, 0, 0), Vec.ZERO);
        Docking.Command c = dockFlight("side port, ship turns", new Vec(-90, 100, 70), new Vec(0, 0, -1), Vec.UP,
                new Vec(0, 0, 3), new Vec(0, 0, 1), east, true, 90);
        ok &= c.phase() == Docking.Phase.FINAL && c.distance() < Docking.GAP + 0.6 && c.attitudeErrorDeg() < 3;
        // порт на площадке смотрит вверх; штекер снизу корабля, корабль с креном
        Docking.Port pad = new Docking.Port(new Vec(30.5, 64.5, 30.5), Vec.UP, Vec.ZERO);
        Vec u0 = new Vec(0.2, 1, 0).normalize();
        c = dockFlight("pad port, ship levels", new Vec(0, 110, 0), new Vec(0, 0, -1), u0, new Vec(0, -3, 0), new Vec(0, -1, 0), pad, true, 90);
        ok &= c.phase() == Docking.Phase.FINAL && c.distance() < Docking.GAP + 0.6 && c.attitudeErrorDeg() < 3;
        // порт на подвижной платформе (2 бл/с на юг)
        Docking.Port moving = new Docking.Port(new Vec(10.5, 70.5, 0.5), Vec.UP, new Vec(0, 0, 2));
        Vec shipM = new Vec(-30, 95, -20);
        Vec wM = Vec.ZERO, vM = Vec.ZERO;
        Docking.Phase phM = Docking.Phase.TRANSIT;
        Docking.Command cm = null;
        Vec portTip = moving.tip();
        for (int i = 0; i < 90 * 20; i++) {
            portTip = portTip.addScaled(moving.vel(), 0.05);
            Docking.Port pm = new Docking.Port(portTip, moving.facing(), moving.vel());
            Vec tip = shipM.add(new Vec(0, -3, 0));
            cm = Docking.steer(phM, pm, tip, new Vec(0, -1, 0), Vec.UP, 15, 92, true);
            phM = cm.phase();
            vM = vM.add(cm.velocity().sub(vM).scale(0.05 / 0.5));
            shipM = shipM.addScaled(vM, 0.05);
        }
        System.out.printf(Locale.ROOT, "  dock %-28s phase %-8s dist %.2f%n", "moving platform", cm.phase(), cm.distance());
        ok &= cm.phase() == Docking.Phase.FINAL && cm.distance() < Docking.GAP + 0.8;
        // порт занят: ждать у точки подхода
        c = dockFlight("busy port, wait", new Vec(-40, 90, 30), new Vec(0, 0, -1), Vec.UP, new Vec(0, 0, 3), new Vec(0, 0, 1), east, false, 60);
        ok &= c.phase() == Docking.Phase.ALIGN && Math.abs(c.distance() - Docking.APPROACH_DIST) < 1.6;
        // порт смотрит вверх, а штекер сбоку корабля - так не встать
        c = dockFlight("incompatible tilt", new Vec(10, 90, 10), new Vec(0, 0, -1), Vec.UP, new Vec(0, 0, 3), new Vec(0, 0, 1), pad, true, 40);
        ok &= !c.compatible() && c.phase() != Docking.Phase.FINAL;
        if (!ok) failures++;
        System.out.println("docking: " + (ok ? "all checks passed" : "failed"));
    }

    /** Орбита моста без цели: из точки в 100 бл выходит на круг радиуса 30 на высоте +20, обходит в нужную сторону. */
    static void orbit() {
        boolean ok = true;
        for (boolean cw : new boolean[]{false, true}) {
            Vec center = new Vec(0, 64, 0), ship = new Vec(100, 90, 40), vel = Vec.ZERO;
            double minR = 1e9, maxR = 0, turn = 0;
            Vec prev = null;
            for (int i = 0; i < 20 * 120; i++) {
                Vec vd = Autopilot.orbitVelocity(ship, center, Vec.ZERO, 30, 20, 12, cw);
                vel = vel.add(vd.sub(vel).scale(0.05 / 0.5));
                ship = ship.addScaled(vel, 0.05);
                if (i > 20 * 60) {
                    double r = ship.sub(center).horizontalLength();
                    minR = Math.min(minR, r);
                    maxR = Math.max(maxR, r);
                    Vec rel = ship.sub(center).horizontal();
                    if (prev != null) turn += prev.cross(rel).y();
                    prev = rel;
                }
            }
            boolean m = minR > 27 && maxR < 33 && Math.abs(ship.y() - 84) < 0.5 && (cw ? turn < 0 : turn > 0);
            System.out.printf(Locale.ROOT, "  orbit cw=%s: radius %.1f..%.1f, height %.2f, turn %s%n", cw, minR, maxR, ship.y() - 64, turn > 0 ? "ccw" : "cw");
            ok &= m;
        }
        // своя скорость орбиты (8 бл/с) при пределе моста 20: на круге по касательной 8
        Vec center = new Vec(0, 64, 0), ship = new Vec(100, 90, 40), vel = Vec.ZERO;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 20 * 120; i++) {
            Vec vd = Autopilot.orbitVelocity(ship, center, Vec.ZERO, 30, 20, 8, 20, false);
            vel = vel.add(vd.sub(vel).scale(0.05 / 0.5));
            ship = ship.addScaled(vel, 0.05);
            if (i > 20 * 90) {
                sum += vel.horizontalLength();
                n++;
            }
        }
        double r = ship.sub(center).horizontalLength();
        System.out.printf(Locale.ROOT, "  orbit speed 8 (limit 20): mean %.2f bl/s, radius %.1f%n", sum / n, r);
        ok &= Math.abs(sum / n - 8) < 0.3 && Math.abs(r - 30) < 1.5;
        // нос на орбите: к центру, наружу, по ходу (против часовой - касательная), не поворачивать
        Vec s0 = new Vec(10, 0, 0), c0 = Vec.ZERO;
        ok &= Autopilot.OrbitFacing.CENTER.dir(s0, c0, false).distance(new Vec(-1, 0, 0)) < 1e-9;
        ok &= Autopilot.OrbitFacing.OUTWARD.dir(s0, c0, false).distance(new Vec(1, 0, 0)) < 1e-9;
        ok &= Autopilot.OrbitFacing.ALONG.dir(s0, c0, false).distance(new Vec(0, 0, -1)) < 1e-9;
        ok &= Autopilot.OrbitFacing.BACKWARD.dir(s0, c0, true).distance(new Vec(0, 0, -1)) < 1e-9;
        ok &= Autopilot.OrbitFacing.FIXED.dir(s0, c0, false) == null;
        if (!ok) failures++;
        System.out.println("orbit: " + (ok ? "all checks passed" : "failed"));
    }

    /** Прицел - главный: курс с прицела, угол места со ствола; прицел, наклонённый со стволом, - он же. */
    static void sightAim() {
        boolean ok = true;
        Vec east = new Vec(1, 0, 0), barrel20 = new Vec(Math.cos(Math.toRadians(20)), Math.sin(Math.toRadians(20)), 0);
        Vec r = Aim.sightAim(east, barrel20);
        ok &= r.distance(barrel20) < 1e-9;                                 // прицел без наклона, ствол поднят на 20°
        Vec north = new Vec(0, 0, -1), barrelEast = new Vec(0.9, 0.3, 0.1).normalize();
        r = Aim.sightAim(north, barrelEast);                                // курс - прицела (север), угол места - ствола
        ok &= Math.abs(r.horizontal().normalize().dot(north) - 1) < 1e-9 && Math.abs(r.pitchDeg() - barrelEast.pitchDeg()) < 1e-9;
        ok &= Aim.sightAim(barrel20, barrel20).distance(barrel20) < 1e-9;   // прицел наклоняется со стволом
        ok &= Aim.sightAim(east, null).distance(east) < 1e-9;               // станок не собран - прицел
        if (!ok) failures++;
        System.out.println("sight aim: " + (ok ? "all checks passed" : "failed"));
    }

    public static void main(String[] a) {
        ballisticCase("big cannon 4 charges", new Truth(8, -0.05, 0.01, false, 0, 0, 1, 1, 600), 300, false, 97);
        ballisticCase("big cannon 2 charges", new Truth(4, -0.05, 0.01, false, 0, 0, 1, 1, 600), 200, false, 97);
        ballisticCase("big cannon, accelerating target", new Truth(8, -0.05, 0.01, false, 0, 0, 1, 1, 600), 250, true, 95);
        ballisticCase("autocannon (lifetime 40)", new Truth(7.5, -0.025, 0.01, false, 0, 0, 1, 1, 40), 200, false, 97);
        ballisticCase("quadratic drag", new Truth(6, -0.05, 0.002, true, 0, 0, 1, 1, 600), 200, false, 97);
        fastCase("fast targets, big cannon 8 b/t", new Truth(8, -0.05, 0.01, false, 0, 0, 1, 1, 600), 200, 97);
        fastCase("fast targets, autocannon 6 b/t", new Truth(6, -0.025, 0.01, false, 0, 0, 1, 1, 60), 150, 97);
        boxMiss();
        rocketLearning();
        penetrationLearning();
        trackFilter();
        maneuver();
        targetPicker();
        interception();
        wheelSteps();
        mountTargets();
        angleLimits();
        autopilot();
        docking();
        orbit();
        sightAim();
        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("ALL CORE TESTS PASSED");
    }
}
