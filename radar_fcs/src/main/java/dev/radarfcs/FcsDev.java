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

    private FcsDev() {}
}
