package dev.radarfcs;

/**
 * Переключатели для автоматических игровых тестов (не конфиг для игроков). В игре вычислитель видит цели только
 * через радары Create Radars на линках; тесты баллистики включают «безрадарный» сенсор, чтобы не зависеть
 * от обзора радара.
 */
public final class FcsDev {
    /** Цели - все подходящие сущности в радиусе radarlessRange, радар не нужен. */
    public static volatile boolean radarless;
    public static volatile double radarlessRange = 80;
    /** Стрелять по снарядам и без CBC Interception (тесты выбора целей и огня по снарядам). */
    public static volatile boolean interceptWithoutMod;
    /** Считать, что CBC Interception не установлен (тест поведения без мода). */
    public static volatile boolean hideInterceptMod;

    private FcsDev() {}
}
