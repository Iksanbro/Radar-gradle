package dev.radarfcs.compat;

import dev.radarfcs.FcsMod;
import java.lang.reflect.Field;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Связь с модом CBC Interception (cbcintercept): снаряды CBC и аддонов, встретившиеся в полёте, сбивают друг друга.
 * Его правила попадания (своими словами, по описанию и настройкам мода): пути двух снарядов за тик - отрезки,
 * столкновение - если они сблизились меньше суммы половин размеров снарядов плюс extraHitRadius; снаряд, летящий
 * первые 2 тика, не участвует; снаряды с разницей скоростей меньше 1 блока/тик («один залп») не сталкиваются.
 * Настройки мода читаются через рефлексию, зависимости при сборке нет.
 */
public final class Interception {
    /** Столько первых тиков полёта снаряд не сталкивается с другими. */
    public static final int ARMING_TICKS = 2;
    /** Снаряды с меньшей разницей скоростей (блоков/тик) летят «одним залпом» и не сталкиваются. */
    public static final double SAME_VOLLEY_SPEED = 1.0;
    /** extraHitRadius по умолчанию. */
    public static final double DEFAULT_EXTRA = 0.5;

    private static final String CONFIG = "io.github.iksanbro.cbcintercept.Config";
    private static boolean warned;

    private Interception() {}

    /** Мод установлен и включён (projectilesCollide): летящие снаряды можно сбивать. */
    public static boolean available() {
        if (!Mods.INTERCEPTION) return false;
        Object v = value("ENABLED");
        return !(v instanceof Boolean b) || b;
    }

    /** Сколько блоков мод добавляет к размерам двух снарядов при проверке встречи. */
    public static double extraHitRadius() {
        Object v = Mods.INTERCEPTION ? value("EXTRA_HIT_RADIUS") : null;
        return v instanceof Number n ? Math.max(0, n.doubleValue()) : DEFAULT_EXTRA;
    }

    @Nullable
    private static Object value(String field) {
        try {
            Field f = Class.forName(CONFIG).getField(field);
            return f.get(null) instanceof Supplier<?> s ? s.get() : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // конфиг ещё не загружен или мод другой версии: значения по умолчанию
            if (!warned && !(e instanceof IllegalStateException)) {
                warned = true;
                FcsMod.LOG.warn("[radar_fcs] CBC Interception settings not readable, using defaults: {}", e.toString());
            }
            return null;
        }
    }
}
