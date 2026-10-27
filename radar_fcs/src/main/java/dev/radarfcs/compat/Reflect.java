package dev.radarfcs.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Безопасная рефлексия для закрытых полей и методов чужих модов.
 * Результаты поиска кэшируются; отсутствие поля или метода не роняет игру.
 */
public final class Reflect {
    private record Key(Class<?> owner, String name, int arity) {}

    private static final Map<Key, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Map<Key, Optional<Field>> FIELDS = new ConcurrentHashMap<>();

    private Reflect() {}

    /** Метод без параметров по имени, включая protected/private, по всей иерархии классов. */
    @Nullable
    public static Method method0(Class<?> c, String name) {
        return METHODS.computeIfAbsent(new Key(c, name, 0), k -> {
            for (Class<?> cl = c; cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                try {
                    Method m = cl.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return Optional.of(m);
                } catch (NoSuchMethodException ignored) {
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** Открытый метод с параметрами (включая унаследованные и методы интерфейсов). */
    @Nullable
    public static Method method(Class<?> c, String name, Class<?>... params) {
        return METHODS.computeIfAbsent(new Key(c, name + java.util.Arrays.toString(params), params.length), k -> {
            try {
                return Optional.of(c.getMethod(name, params));
            } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    @Nullable
    public static Field field(Class<?> c, String name) {
        return FIELDS.computeIfAbsent(new Key(c, name, -1), k -> {
            for (Class<?> cl = c; cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                try {
                    Field f = cl.getDeclaredField(name);
                    f.setAccessible(true);
                    return Optional.of(f);
                } catch (NoSuchFieldException ignored) {
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    @Nullable
    public static Object call0(@Nullable Object target, String name) {
        if (target == null) return null;
        Method m = method0(target.getClass(), name);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    @Nullable
    public static Object get(@Nullable Object target, String name) {
        if (target == null) return null;
        Field f = field(target.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    public static double number(@Nullable Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    /** Реализует ли класс (или его предки) интерфейс с таким простым именем. */
    public static boolean implementsNamed(Class<?> c, String simpleName) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                if (i.getSimpleName().equals(simpleName) || implementsNamed(i, simpleName)) return true;
            }
        }
        return false;
    }

    /** Есть ли в имени класса или его предков один из фрагментов. */
    public static boolean nameMatches(Class<?> c, Iterable<? extends String> hints) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            for (String h : hints) if (!h.isEmpty() && n.contains(h)) return true;
        }
        return false;
    }
}
