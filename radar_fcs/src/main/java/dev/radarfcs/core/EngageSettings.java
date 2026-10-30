package dev.radarfcs.core;

/**
 * Настройки вычислителя (у каждого блока свои): режим огня, какие цели брать, когда сбрасывать цель,
 * как выбирать и когда переключать. Без зависимостей от Minecraft - проверяется офлайн-тестом.
 */
public final class EngageSettings {

    /** Режим огня. */
    public enum FireMode {
        /** Один выстрел, затем пауза. */
        SINGLE,
        /** Непрерывно, пока выстрел попадёт. */
        AUTO,
        /** Очередь заданной длины, затем пауза. */
        BURST
    }

    /** Типы целей. */
    public enum Category {
        /** Корабли Sable / Create Aeronautics (сублевелы). */
        SHIPS,
        PLAYERS,
        MOBS,
        /** Прочие сущности: контрапшены Create, лодки, вагонетки... */
        ENTITIES,
        /** Перехват: снаряды больших пушек. */
        SHELLS,
        /** Перехват: ракеты. */
        ROCKETS;

        public static Category of(Track.Kind kind) {
            return switch (kind) {
                case SHIP -> SHIPS;
                case PLAYER -> PLAYERS;
                case MOB -> MOBS;
                case CONTRAPTION, EXTERNAL -> ENTITIES;
                case PROJECTILE -> SHELLS;
            };
        }

        public static Category of(Track t) {
            return t.kind == Track.Kind.PROJECTILE && t.munition == ProjectileType.ROCKET ? ROCKETS : of(t.kind);
        }

        public boolean intercept() { return this == SHELLS || this == ROCKETS; }
    }

    /** Какие снаряды и ракеты перехватывать. */
    public enum Threat {
        /** Только угрожающие: прогноз полёта проходит ближе threatRadius от батареи или своих зон. */
        THREATENING,
        /** Все, что приближается к вычислителю. */
        APPROACHING,
        /** Все чужие. */
        ALL
    }

    /** Какая цель главнее при равном уровне приоритета. */
    public enum Priority { NEAREST, EARLIEST, FARTHEST, LARGEST, SMALLEST, FASTEST, SLOWEST }

    /** Когда переключаться на другую цель. */
    public enum SwitchMode {
        /** Не переключаться: держать цель, пока она подходит. */
        NONE,
        /** После заданного числа выстрелов по ней. */
        AFTER_SHOTS,
        /** Через заданное время. */
        AFTER_TIME,
        /** Когда цель остановилась. */
        ON_STOP
    }

    public static final int CATEGORIES = Category.values().length;
    public static final int MAX_LEVEL = 5;

    public FireMode fireMode = FireMode.AUTO;
    /** Выстрелов в очереди (BURST). */
    public int burstLength = 5;
    /** Пауза после одиночного выстрела или очереди, тиков. */
    public int pauseTicks = 20;

    public final boolean[] enabled = {true, false, true, true, true, true};
    /** Уровень приоритета типа цели: 1..MAX_LEVEL, выше - главнее (летящие снаряды - угроза прямо сейчас). */
    public final int[] level = {2, 3, 1, 1, 4, 4};
    /** Перехват: какие снаряды и ракеты брать. */
    public Threat threat = Threat.THREATENING;
    /** Перехват: «угрожающий» - проходит ближе стольких блоков от батареи или своей зоны. */
    public int threatRadius = 16;
    public static final int MAX_THREAT_RADIUS = 128;
    /** Перехват: при равном уровне первым - снаряд, который раньше долетит до своих (иначе - по режиму приоритета). */
    public boolean impactFirst = true;
    /** Мобы: только враждебные (зомби, скелеты...), мирные и нейтральные - не цели. */
    public boolean hostileOnly = true;
    /**
     * Белый список: всегда цели, даже если их тип выключен или моб мирный. Чёрный список: никогда не цели.
     * Записи: ID типа (minecraft:zombie), тег типа (#minecraft:undead), мод целиком (minecraft:*), имя игрока (Steve).
     */
    public final java.util.List<String> whitelist = new java.util.ArrayList<>(), blacklist = new java.util.ArrayList<>();
    /** Наибольшее число хранимых записей списка; действуют первые столько, сколько позволяет память вычислителя. */
    public static final int MAX_LIST = 256, MAX_ENTRY = 64;
    /**
     * Свой-чужой по кодам транспондеров: true - белый список (свои только коды из списка), false - чёрный (свои все,
     * кроме кодов из списка). Код - от 1 до CODE_LENGTH цифр.
     */
    public boolean iffWhitelist = false;
    public final java.util.List<String> iffCodes = new java.util.ArrayList<>();
    public static final int MAX_IFF_CODES = 256, CODE_LENGTH = 8;
    /** Скорость цели от и до, м/с, у каждого типа цели своя; 0 - без ограничения. Шаг настройки - 0.1 м/с. */
    public final double[] speedMin = new double[CATEGORIES], speedMax = new double[CATEGORIES];
    public static final double MAX_SPEED = 1000;

    /** Сбрасывать (не брать) цели, которые стоят. */
    public boolean dropStatic = true;
    /**
     * Сколько цель, которая двигалась, должна простоять на месте, чтобы считаться статичной (сброс, переключение
     * «при остановке»), тиков. Короткая остановка (разворот) - ещё не статичная. Не двигавшаяся ни разу - статичная сразу.
     */
    public int staticTicks = 20;
    /** Сбрасывать цели, которые движутся. */
    public boolean dropMoving = false;
    /** Сбросить цель после стольких выстрелов по ней (0 - нет). */
    public int dropAfterShots = 0;

    public Priority priority = Priority.NEAREST;
    /** Переключаться на цель с более высоким приоритетом, как только она появилась. */
    public boolean switchByPriority = true;
    public SwitchMode switchMode = SwitchMode.NONE;
    public int switchShots = 10;
    /** Переключение через время, тиков (в настройках - секунды с шагом 0.1 с = 2 тика). */
    public int switchTicks = 100;

    public boolean enabled(Category c) { return enabled[c.ordinal()]; }

    /** Уровень приоритета цели по её типу (снаряд большой пушки и ракета - свои уровни). */
    public int level(Track t) { return level[Category.of(t).ordinal()]; }

    public int level(Category c) { return level[c.ordinal()]; }

    /** Скорость цели в окне «от-до» её типа (м/с). */
    public boolean speedAllowed(Category c, double metersPerSecond) {
        double lo = speedMin[c.ordinal()], hi = speedMax[c.ordinal()];
        if (lo > 0 && metersPerSecond < lo) return false;
        return !(hi > 0 && metersPerSecond > hi);
    }

    /** Цель статична: стоит на месте не меньше staticTicks (или не двигалась ни разу). */
    public boolean isStatic(Track t) {
        if (t.moving) return false;
        return t.lastMovingTick == Long.MIN_VALUE || t.lastTick - t.lastMovingTick >= staticTicks;
    }

    /** Подходит ли цель по движению: статичные и движущиеся можно сбрасывать отдельно. */
    public boolean motionAllowed(Track t) {
        return isStatic(t) ? !dropStatic : !dropMoving;
    }

    /** Выстрелов подряд до паузы: 1 у одиночного, длина очереди у очереди, без ограничения у автоматического. */
    public int roundsPerSalvo() {
        return switch (fireMode) {
            case SINGLE -> 1;
            case BURST -> burstLength;
            case AUTO -> Integer.MAX_VALUE;
        };
    }

    /** Привести значения в допустимые пределы (после приёма от клиента). */
    public EngageSettings sanitize() {
        burstLength = clamp(burstLength, 2, 100);
        pauseTicks = clamp(pauseTicks, 0, 1200);
        for (int i = 0; i < CATEGORIES; i++) level[i] = clamp(level[i], 1, MAX_LEVEL);
        for (int i = 0; i < CATEGORIES; i++) {
            speedMin[i] = cleanSpeed(speedMin[i]);
            speedMax[i] = cleanSpeed(speedMax[i]);
        }
        dropAfterShots = clamp(dropAfterShots, 0, 10000);
        staticTicks = clamp(staticTicks, 0, 1200);
        cleanList(whitelist);
        cleanList(blacklist);
        cleanCodes(iffCodes);
        switchShots = clamp(switchShots, 1, 10000);
        switchTicks = clamp(switchTicks, 2, 72000);
        if (fireMode == null) fireMode = FireMode.AUTO;
        if (priority == null) priority = Priority.NEAREST;
        if (switchMode == null) switchMode = SwitchMode.NONE;
        if (threat == null) threat = Threat.THREATENING;
        threatRadius = clamp(threatRadius, 1, MAX_THREAT_RADIUS);
        return this;
    }

    public EngageSettings copy() {
        EngageSettings s = new EngageSettings();
        s.fireMode = fireMode;
        s.burstLength = burstLength;
        s.pauseTicks = pauseTicks;
        System.arraycopy(enabled, 0, s.enabled, 0, CATEGORIES);
        System.arraycopy(level, 0, s.level, 0, CATEGORIES);
        System.arraycopy(speedMin, 0, s.speedMin, 0, CATEGORIES);
        System.arraycopy(speedMax, 0, s.speedMax, 0, CATEGORIES);
        s.dropStatic = dropStatic;
        s.staticTicks = staticTicks;
        s.hostileOnly = hostileOnly;
        s.whitelist.addAll(whitelist);
        s.blacklist.addAll(blacklist);
        s.iffWhitelist = iffWhitelist;
        s.iffCodes.addAll(iffCodes);
        s.dropMoving = dropMoving;
        s.dropAfterShots = dropAfterShots;
        s.priority = priority;
        s.switchByPriority = switchByPriority;
        s.switchMode = switchMode;
        s.switchShots = switchShots;
        s.switchTicks = switchTicks;
        s.threat = threat;
        s.threatRadius = threatRadius;
        s.impactFirst = impactFirst;
        return s;
    }

    /** Копия, в которой у каждого списка действуют только первые capacity записей (память вычислителя). */
    public EngageSettings limited(int capacity) {
        EngageSettings s = copy();
        truncate(s.whitelist, capacity);
        truncate(s.blacklist, capacity);
        truncate(s.iffCodes, capacity);
        return s;
    }

    private static void truncate(java.util.List<String> l, int n) {
        while (l.size() > Math.max(0, n)) l.remove(l.size() - 1);
    }

    /** Правило «свой-чужой» по кодам транспондеров. */
    public IffRule iffRule() {
        return new IffRule(iffWhitelist, new java.util.HashSet<>(iffCodes));
    }

    /** Код транспондера: только цифры, не длиннее CODE_LENGTH; иначе "" (без кода). */
    public static String cleanCode(String code) {
        if (code == null) return "";
        String t = code.trim();
        return t.length() <= CODE_LENGTH && t.chars().allMatch(ch -> ch >= '0' && ch <= '9') ? t : "";
    }

    /** Коды: только допустимые, без повторов, не больше MAX_IFF_CODES. */
    static void cleanCodes(java.util.List<String> list) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String e : list) {
            String c = cleanCode(e);
            if (!c.isEmpty() && out.size() < MAX_IFF_CODES) out.add(c);
        }
        list.clear();
        list.addAll(out);
    }

    /** Обрезать пробелы, убрать пустые и повторы, не больше MAX_LIST записей по MAX_ENTRY символов. */
    static void cleanList(java.util.List<String> list) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String e : list) {
            if (e == null) continue;
            String t = e.trim();
            if (t.isEmpty() || t.length() > MAX_ENTRY) continue;
            if (out.size() < MAX_LIST) out.add(t);
        }
        list.clear();
        list.addAll(out);
    }

    /** Скорость окна: 0..MAX_SPEED с шагом 0.1 м/с; меньше 0.1 - без ограничения. */
    static double cleanSpeed(double v) {
        if (!Double.isFinite(v) || v < 0.05) return 0;
        return Math.round(Math.min(MAX_SPEED, v) * 10) / 10.0;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
