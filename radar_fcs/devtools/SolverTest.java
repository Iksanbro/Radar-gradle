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
        s.switchSeconds = 5;
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
        f.speedMin = 10; f.speedMax = 50;
        // стоящая с начала, движущаяся, на миг остановившаяся, давно стоящая
        Track still = table.observe("S1", Track.Kind.MOB, "S1", new Vec(5, 64, 0), 0.5, 50, false, null, TrackTable.Settings.DEFAULT);
        Track mov = table.observe("S2", Track.Kind.MOB, "S2", new Vec(6, 64, 0), 0.5, 50, false, null, TrackTable.Settings.DEFAULT);
        Track brief = table.observe("S3", Track.Kind.MOB, "S3", new Vec(7, 64, 0), 0.5, 50, false, null, TrackTable.Settings.DEFAULT);
        Track parked = table.observe("S4", Track.Kind.MOB, "S4", new Vec(8, 64, 0), 0.5, 50, false, null, TrackTable.Settings.DEFAULT);
        mov.moving = true; brief.lastMovingTick = 45; parked.lastMovingTick = 20;
        f.staticTicks = 20;
        boolean ok = !f.speedAllowed(5) && f.speedAllowed(30) && !f.speedAllowed(60)
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
        f.burstLength = 0; f.level[0] = 99; f.speedMin = Double.NaN; f.switchShots = -4;
        f.sanitize();
        ok &= f.burstLength == 2 && f.level[0] == EngageSettings.MAX_LEVEL && f.speedMin == 0 && f.switchShots == 1;
        ok &= EngageSettings.Category.of(Track.Kind.PROJECTILE) == EngageSettings.Category.ENTITIES
                && EngageSettings.Category.of(Track.Kind.SHIP) == EngageSettings.Category.SHIPS;
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
        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("ALL CORE TESTS PASSED");
    }
}
