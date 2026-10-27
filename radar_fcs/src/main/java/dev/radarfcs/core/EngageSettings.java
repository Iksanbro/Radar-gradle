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
        /** Прочие сущности: контрапшены Create, снаряды и ракеты. */
        ENTITIES;

        public static Category of(Track.Kind kind) {
            return switch (kind) {
                case SHIP -> SHIPS;
                case PLAYER -> PLAYERS;
                case MOB -> MOBS;
                case CONTRAPTION, PROJECTILE, EXTERNAL -> ENTITIES;
            };
        }
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

    public final boolean[] enabled = {true, false, true, true};
    /** Уровень приоритета типа цели: 1..MAX_LEVEL, выше - главнее. */
    public final int[] level = {2, 3, 1, 1};
    /** Скорость цели от и до, м/с; 0 - без ограничения. */
    public double speedMin = 0, speedMax = 0;

    /** Сбрасывать (не брать) цели, которые стоят. */
    public boolean dropStatic = true;
    /** Сбрасывать цели, которые движутся. */
    public boolean dropMoving = false;
    /** Сбросить цель после стольких выстрелов по ней (0 - нет). */
    public int dropAfterShots = 0;

    public Priority priority = Priority.NEAREST;
    /** Переключаться на цель с более высоким приоритетом, как только она появилась. */
    public boolean switchByPriority = true;
    public SwitchMode switchMode = SwitchMode.NONE;
    public int switchShots = 10;
    public int switchSeconds = 5;

    public boolean enabled(Category c) { return enabled[c.ordinal()]; }

    public int level(Category c) { return level[c.ordinal()]; }

    /** Скорость цели в окне «от-до» (м/с). */
    public boolean speedAllowed(double metersPerSecond) {
        if (speedMin > 0 && metersPerSecond < speedMin) return false;
        return !(speedMax > 0 && metersPerSecond > speedMax);
    }

    /** Подходит ли цель по движению: стоящие и движущиеся можно сбрасывать отдельно. */
    public boolean motionAllowed(boolean moving) {
        return moving ? !dropMoving : !dropStatic;
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
        speedMin = Double.isFinite(speedMin) ? Math.max(0, Math.min(1000, speedMin)) : 0;
        speedMax = Double.isFinite(speedMax) ? Math.max(0, Math.min(1000, speedMax)) : 0;
        dropAfterShots = clamp(dropAfterShots, 0, 10000);
        switchShots = clamp(switchShots, 1, 10000);
        switchSeconds = clamp(switchSeconds, 1, 3600);
        if (fireMode == null) fireMode = FireMode.AUTO;
        if (priority == null) priority = Priority.NEAREST;
        if (switchMode == null) switchMode = SwitchMode.NONE;
        return this;
    }

    public EngageSettings copy() {
        EngageSettings s = new EngageSettings();
        s.fireMode = fireMode;
        s.burstLength = burstLength;
        s.pauseTicks = pauseTicks;
        System.arraycopy(enabled, 0, s.enabled, 0, CATEGORIES);
        System.arraycopy(level, 0, s.level, 0, CATEGORIES);
        s.speedMin = speedMin;
        s.speedMax = speedMax;
        s.dropStatic = dropStatic;
        s.dropMoving = dropMoving;
        s.dropAfterShots = dropAfterShots;
        s.priority = priority;
        s.switchByPriority = switchByPriority;
        s.switchMode = switchMode;
        s.switchShots = switchShots;
        s.switchSeconds = switchSeconds;
        return s;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
