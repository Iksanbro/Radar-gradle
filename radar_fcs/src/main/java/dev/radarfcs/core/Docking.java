package dev.radarfcs.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Подлёт корабля к стыковочному порту (без Minecraft): куда лететь и как повернуть корпус, чтобы конец порта корабля
 * (штекер) встал напротив конца порта стыковки. Всё в мировых координатах, скорости - бл/с, угловые скорости - рад/с.
 * <p>
 * Фазы: {@link Phase#TRANSIT} - дальний перелёт к точке подхода (на высоте не ниже той, с которой начали), {@link
 * Phase#APPROACH} - к точке подхода в {@link #APPROACH_DIST} блоках перед портом, {@link Phase#ALIGN} - висеть в ней,
 * пока корпус не развернётся, {@link Phase#FINAL} - медленно по оси порта до {@link #GAP} блоков; дальше порты
 * Create Simulated сами притягиваются магнитом (ближе 4 бл, навстречу друг другу) и сцепляются.
 */
public final class Docking {
    public enum Phase { TRANSIT, APPROACH, ALIGN, FINAL }

    /** Когда мост ведёт корабль на стыковку: никогда, когда нет цели, по сигналу редстоуна на мосту (вместо паузы). */
    public enum Trigger {
        OFF, NO_TARGET, REDSTONE;

        public String key() {
            return "radar_fcs.autopilot.dock." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Все порты с номером заняты: ждать у точки подхода ближайшего или не лететь. */
    public enum Busy {
        WAIT, STAY;

        public String key() {
            return "radar_fcs.autopilot.dock_busy." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Роль стыковочного контроллера: порт (куда садятся) или штекер (на корабле, который садится). */
    public enum Role {
        PORT, PLUG;

        public String key() {
            return "radar_fcs.docking.role." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Номер контроллера: 0..NUMBER_MAX. */
    public static final int NUMBER_MAX = 9999;
    /** Бронь порта держится столько тиков без продления. */
    public static final long RESERVE_TICKS = 40;
    /** После расстыковки корабль отходит от порта столько тиков со скоростью DEPART_SPEED (бл/с). */
    public static final long DEPART_TICKS = 60;
    public static final double DEPART_SPEED = 3;
    /** Без цели столько тиков - пора на стыковку (цель мигнула - не срываться). */
    public static final long NO_TARGET_DELAY = 40;

    /** Точка подхода - столько блоков перед концом порта. */
    public static final double APPROACH_DIST = 8;
    /** Цель ближнего подхода: конец штекера в стольких блоках перед концом порта (магниты Simulated берут с 4 бл). */
    public static final double GAP = 1.0;
    /** Дальше этого (по горизонтали) от точки подхода - перелёт на прежней высоте. */
    public static final double TRANSIT_RADIUS = 24;
    /** Скорость ближнего подхода, бл/с. */
    public static final double FINAL_SPEED = 1.5;
    /** Точка подхода достигнута. */
    public static final double AT_POINT = 1.5;
    /** Корпус развёрнут: штекер смотрит на порт точнее этого угла (градусы). */
    public static final double ALIGN_DEG = 5;
    /** Ближний подход прерывается (назад к точке подхода): разворот хуже этого угла или боковой увод больше LATERAL_ABORT. */
    public static final double ABORT_DEG = 12, LATERAL_ABORT = 1.5;
    /** Порт и штекер несовместимы: наклон штекера к «верху» корабля не совпадает с наклоном порта больше чем на это (градусы). */
    public static final double TILT_MISMATCH_DEG = 20;
    /** Конец штекера в этих блоках от конца порта - выдвигать порты. */
    public static final double EXTEND_DIST = APPROACH_DIST + 2;

    private Docking() {}

    /** Расстановки портов совпали (средний промах концов после доворота), бл. */
    public static final double LAYOUT_TOL = 0.6;

    /**
     * Порт стыковки: середина концов его портов (мир), направление «наружу» (единичное), скорость (бл/с; у порта на
     * земле - ноль) и концы всех его портов (мир; один порт - только tip).
     */
    public record Port(Vec tip, Vec facing, Vec vel, List<Vec> tips) {
        public Port(Vec tip, Vec facing, Vec vel) {
            this(tip, facing, vel, List.of(tip));
        }

        /** Точка подхода. */
        public Vec approach() {
            return tip.addScaled(facing, APPROACH_DIST);
        }

        /** Цель ближнего подхода. */
        public Vec contact() {
            return tip.addScaled(facing, GAP);
        }
    }

    /**
     * Команда регуляторам: скорость (мир), угловая скорость (мир), фаза, ошибка разворота (градусы, с доворотом по
     * расстановке), расстояние середины штекеров до середины портов, совместимы ли наклоны (false - корабль так не
     * встанет), средний промах концов портов после разворота (бл) и совпали ли расстановки (число портов и промах).
     */
    public record Command(Vec velocity, Vec rates, Phase phase, double attitudeErrorDeg, double distance, boolean compatible,
                          double layoutError, boolean layoutOk) {}

    /** Разворот по расстановке: доворот вокруг вертикали (рад, + - против часовой сверху), средний промах (бл). */
    public record Layout(double yaw, double error, boolean ok) {}

    /**
     * Шаг подлёта для одного порта.
     *
     * @param prev      фаза прошлого шага (TRANSIT в начале)
     * @param port      порт стыковки
     * @param plugTip   мировой конец штекера
     * @param plugDir   мировое направление штекера «наружу»
     * @param shipUp    мировой «верх» корабля
     * @param maxSpeed  предел скорости моста, бл/с
     * @param cruiseY   высота конца штекера в начале стыковки: дальний перелёт не ниже её
     * @param mayEnter  можно ли идти дальше точки подхода (false - порт занят, ждать у точки)
     */
    public static Command steer(Phase prev, Port port, Vec plugTip, Vec plugDir, Vec shipUp, double maxSpeed, double cruiseY,
                                boolean mayEnter) {
        return steer(prev, port, List.of(plugTip), plugDir, shipUp, maxSpeed, cruiseY, mayEnter);
    }

    /**
     * Шаг подлёта для нескольких портов: plugTips - мировые концы всех портов корабля. Середина штекеров идёт к середине
     * портов; корабль заранее (на всём подлёте) разворачивается так, чтобы каждый его порт встал напротив своего:
     * штекер навстречу порту, корпус ровно, а у портов вверх/вниз ещё и доворот по курсу, при котором расстановки
     * совпадают лучше всего (соответствие портов подбирается само). Ближний подход - только когда и разворот, и доворот
     * готовы.
     */
    public static Command steer(Phase prev, Port port, List<Vec> plugTips, Vec plugDir, Vec shipUp, double maxSpeed,
                                double cruiseY, boolean mayEnter) {
        Vec plugTip = centroid(plugTips);
        Vec fp = port.facing().normalize();
        Vec want = fp.scale(-1);
        Vec p = plugDir.normalize();
        Vec up = shipUp.normalize();
        boolean compatible = compatible(p, up, fp);
        Layout lay = layout(plugTips, plugTip, p, want, port);
        double err = Math.max(p.angleTo(want), Math.toDegrees(Math.abs(lay.yaw())));
        Vec rates = compatible ? rates(p, up, want) : levelRates(up);
        if (compatible && lay.yaw() != 0) rates = rates.add(Vec.UP.scale(rate(lay.yaw())));
        Vec a = port.approach();
        double dist = plugTip.distance(port.tip());
        Phase phase = prev;
        Vec rel = plugTip.sub(port.contact());
        double axial = rel.dot(fp);
        double lateral = rel.sub(fp.scale(axial)).length();
        // смена фазы
        if (phase == Phase.TRANSIT && plugTip.sub(a).horizontalLength() <= TRANSIT_RADIUS) phase = Phase.APPROACH;
        if (phase == Phase.APPROACH && plugTip.distance(a) <= AT_POINT) phase = Phase.ALIGN;
        if (phase == Phase.ALIGN && mayEnter && compatible && err <= ALIGN_DEG && plugTip.distance(a) <= AT_POINT) phase = Phase.FINAL;
        if (phase == Phase.FINAL && (!mayEnter || !compatible || err > ABORT_DEG || (axial > 2 && lateral > LATERAL_ABORT))) phase = Phase.ALIGN;
        if ((phase == Phase.APPROACH || phase == Phase.ALIGN) && plugTip.sub(a).horizontalLength() > TRANSIT_RADIUS * 1.5) {
            phase = Phase.TRANSIT;                                          // унесло далеко - снова перелёт
        }
        Vec v;
        switch (phase) {
            case TRANSIT -> {
                Vec goal = new Vec(a.x(), Math.max(cruiseY, a.y()), a.z());
                v = toward(plugTip, goal, maxSpeed);
            }
            case FINAL -> {
                Vec latV = rel.sub(fp.scale(axial)).scale(-Autopilot.POS_GAIN * 2);
                if (latV.length() > 2) latV = latV.scale(2 / latV.length());
                double in = Autopilot.clamp(Autopilot.POS_GAIN * axial, -FINAL_SPEED, Math.min(FINAL_SPEED, maxSpeed));
                v = latV.add(fp.scale(-in));
            }
            default -> v = toward(plugTip, a, maxSpeed);
        }
        return new Command(v.add(port.vel()), rates, phase, err, dist, compatible, lay.error(), lay.ok());
    }

    public static Vec centroid(List<Vec> pts) {
        if (pts.isEmpty()) return Vec.ZERO;
        Vec sum = Vec.ZERO;
        for (Vec v : pts) sum = sum.add(v);
        return sum.scale(1.0 / pts.size());
    }

    /**
     * Расстановка портов корабля против портов стыковки. Смещения концов штекеров от их середины поворачиваются так,
     * как повернётся корпус, когда штекер встанет навстречу порту, и сравниваются со смещениями концов портов в
     * плоскости поперёк оси порта. Порт вертикальный - подбирается доворот вокруг вертикали (свободный курс), иначе
     * курс задан направлением порта и доворота нет (крен не допускается) - только промах.
     */
    public static Layout layout(List<Vec> plugTips, Vec plugCenter, Vec plugDir, Vec want, Port port) {
        Vec fp = port.facing().normalize();
        List<Vec> ps = new ArrayList<>(), qs = new ArrayList<>();
        Vec axis = plugDir.cross(want);
        double s = axis.length(), angle = Math.atan2(s, plugDir.dot(want));
        for (Vec t : plugTips) {
            Vec o = t.sub(plugCenter);
            if (s > 1e-9) o = rot(o, axis.scale(1 / s), angle);
            ps.add(o.sub(fp.scale(o.dot(fp))));
        }
        for (Vec t : port.tips()) {
            Vec o = t.sub(port.tip());
            qs.add(o.sub(fp.scale(o.dot(fp))));
        }
        boolean vertical = want.horizontal().length() <= 0.2;
        double[] m = vertical ? matchYaw(ps, qs) : new double[]{0, rms(ps, qs, 0)};
        return new Layout(m[0], m[1], plugTips.size() == port.tips().size() && m[1] <= LAYOUT_TOL);
    }

    /**
     * Лучший доворот вокруг вертикали (рад), переводящий точки ps в точки qs (обе - смещения от своих середин), и
     * средний промах после него. Кандидаты - совместить самую далёкую от середины точку штекера с каждой точкой порта
     * (и без доворота); по найденному соответствию доворот уточняется (Прокруст); из равных - меньший по модулю.
     */
    static double[] matchYaw(List<Vec> ps, List<Vec> qs) {
        if (ps.isEmpty() || qs.isEmpty()) return new double[]{0, 0};
        Vec a = ps.get(0);
        for (Vec p : ps) if (p.lengthSqr() > a.lengthSqr()) a = p;
        List<Double> cands = new ArrayList<>(List.of(0.0));
        if (a.length() > 0.3) {
            for (Vec q : qs) if (q.length() > 0.3) cands.add(Math.atan2(a.cross(q).y(), a.dot(q)));
        }
        double bestYaw = 0, bestCost = Double.POSITIVE_INFINITY;
        for (double c : cands) {
            // соответствие при кандидате, затем доворот по нему
            double sx = 0, sd = 0;
            for (Vec p : ps) {
                Vec q = nearest(rotY(p, c), qs);
                sx += p.cross(q).y();
                sd += p.dot(q);
            }
            double yaw = Math.abs(sx) + Math.abs(sd) < 1e-12 ? c : Math.atan2(sx, sd);
            double cost = rms(ps, qs, yaw);
            if (cost < bestCost - 1e-6 || (Math.abs(cost - bestCost) <= 1e-6 && Math.abs(Autopilot.wrap(yaw)) < Math.abs(bestYaw))) {
                bestCost = cost;
                bestYaw = Autopilot.wrap(yaw);
            }
        }
        return new double[]{bestYaw, bestCost};
    }

    /** Средний промах: каждая точка ps после доворота yaw - до ближайшей точки qs. */
    static double rms(List<Vec> ps, List<Vec> qs, double yaw) {
        if (ps.isEmpty() || qs.isEmpty()) return 0;
        double sum = 0;
        for (Vec p : ps) sum += rotY(p, yaw).distanceSqr(nearest(rotY(p, yaw), qs));
        return Math.sqrt(sum / ps.size());
    }

    private static Vec nearest(Vec p, List<Vec> qs) {
        Vec best = qs.get(0);
        for (Vec q : qs) if (q.distanceSqr(p) < best.distanceSqr(p)) best = q;
        return best;
    }

    /** Поворот вокруг +Y (против часовой, если смотреть сверху). */
    static Vec rotY(Vec v, double a) {
        double c = Math.cos(a), sn = Math.sin(a);
        return new Vec(v.x() * c + v.z() * sn, v.y(), -v.x() * sn + v.z() * c);
    }

    /** Поворот v вокруг единичной оси k на угол a (Родриг). */
    static Vec rot(Vec v, Vec k, double a) {
        double c = Math.cos(a), sn = Math.sin(a);
        return v.scale(c).add(k.cross(v).scale(sn)).add(k.scale(k.dot(v) * (1 - c)));
    }

    /** Скорость к точке: ошибка x POS_GAIN, не больше maxSpeed. */
    public static Vec toward(Vec from, Vec to, double maxSpeed) {
        Vec d = to.sub(from);
        double l = d.length();
        if (l < 1e-9) return Vec.ZERO;
        return d.scale(Math.min(maxSpeed, Autopilot.POS_GAIN * l) / l);
    }

    /**
     * Штекер встанет навстречу порту, оставаясь ровным: угол штекера к «верху» корабля равен углу нужного направления
     * (-facing порта) к мировой вертикали.
     */
    public static boolean compatible(Vec plugDir, Vec shipUp, Vec portFacing) {
        double tiltPlug = plugDir.normalize().angleTo(shipUp.normalize());
        double tiltWant = portFacing.normalize().scale(-1).angleTo(Vec.UP);
        return Math.abs(tiltPlug - tiltWant) <= TILT_MISMATCH_DEG;
    }

    /**
     * Угловая скорость: корпус ровно (верх к мировой вертикали) и рыскание - горизонтальная проекция штекера на
     * горизонтальную проекцию нужного направления. Штекер вертикальный - рыскание не нужно.
     */
    static Vec rates(Vec plugDir, Vec shipUp, Vec want) {
        Vec out = levelRates(shipUp);
        Vec ph = plugDir.horizontal(), wh = want.horizontal();
        if (ph.length() > 0.2 && wh.length() > 0.2) {
            ph = ph.normalize();
            wh = wh.normalize();
            double yaw = Math.atan2(ph.cross(wh).y(), ph.dot(wh));        // + - против часовой (вокруг +Y)
            out = out.add(Vec.UP.scale(rate(yaw)));
        }
        return out;
    }

    /** Выровнять корпус: верх корабля к мировой вертикали. */
    public static Vec levelRates(Vec shipUp) {
        Vec axis = shipUp.cross(Vec.UP);
        double s = axis.length();
        if (s < 1e-9) return Vec.ZERO;
        double angle = Math.atan2(s, shipUp.dot(Vec.UP));
        return axis.scale(rate(angle) / s);
    }

    private static double rate(double error) {
        return Autopilot.clamp(Autopilot.YAW_GAIN * error, -Autopilot.YAW_RATE_MAX, Autopilot.YAW_RATE_MAX);
    }
}
