package dev.radarfcs.compat;

import dev.radarfcs.FcsMod;
import dev.radarfcs.core.ProjectileType;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Какие летящие снаряды отслеживаются и перехватываются. Только крупные: снаряды больших пушек CBC и аддонов
 * (наследники AbstractBigCannonProjectile) и ракеты (средние ракеты CBC Advanced Technologies и cbcatfix, ракеты
 * CBC Enchanced Shells). Мелкие - патроны автопушек и пулемётов (в том числе тяжёлых и средних пушек аддонов),
 * ракеты ракетных блоков, стрелы и прочее - не отслеживаются вовсе: их бывает очень много.
 * Теги типов сущностей меняют выбор: radar_fcs:intercept_shells, radar_fcs:intercept_rockets - добавить,
 * radar_fcs:intercept_ignored - исключить.
 */
public final class Munitions {
    public static final TagKey<EntityType<?>> SHELLS = tag("intercept_shells");
    public static final TagKey<EntityType<?>> ROCKETS = tag("intercept_rockets");
    public static final TagKey<EntityType<?>> IGNORED = tag("intercept_ignored");

    private static final String BIG_CANNON = "rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile";
    private static final String AUTOCANNON = "rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile";
    private static final String CBCAT_MEDIUM_ROCKET = "com.dsvv.cbcat.cannon.medium_rocketpod.munitions.AbstractMediumRocket";
    private static final String CBCAT_POD_ROCKET = "com.dsvv.cbcat.cannon.rocketpod.munitions.AbstractRocket";
    private static final String CBCAT_HEAVY_AUTOCANNON = "com.dsvv.cbcat.cannon.heavy_autocannon.munitions.AbstractHeavyAutocannonProjectile";
    private static final String NEO_MEDIUM_CANNON = "riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile";

    private static final Map<Class<?>, Optional<ProjectileType>> BY_CLASS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> EXACT = new ConcurrentHashMap<>();

    private Munitions() {}

    private static TagKey<EntityType<?>> tag(String path) {
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, path));
    }

    /** Снаряд большой пушки, ракета или null - не отслеживается. */
    @Nullable
    public static ProjectileType classify(Entity e) {
        EntityType<?> type = e.getType();
        if (type.is(IGNORED)) return null;
        if (type.is(ROCKETS)) return ProjectileType.ROCKET;
        if (type.is(SHELLS)) return ProjectileType.SHELL;
        return BY_CLASS.computeIfAbsent(e.getClass(), Munitions::byClass).orElse(null);
    }

    /** По иерархии классов (без зависимостей от аддонов: по именам классов). */
    static Optional<ProjectileType> byClass(Class<?> c) {
        boolean rocketName = false;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals(CBCAT_MEDIUM_ROCKET)) return Optional.of(ProjectileType.ROCKET);
            if (n.equals(CBCAT_POD_ROCKET) || n.equals(AUTOCANNON) || n.equals(CBCAT_HEAVY_AUTOCANNON) || n.equals(NEO_MEDIUM_CANNON)) {
                return Optional.empty();
            }
            if (n.equals(BIG_CANNON)) return Optional.of(rocketName ? ProjectileType.ROCKET : ProjectileType.SHELL);
            String simple = k.getSimpleName().toLowerCase(Locale.ROOT);
            if (simple.contains("rocket") || simple.contains("missile")) rocketName = true;
        }
        return Optional.empty();
    }

    /** Летит (не застрял в земле и не уничтожен). */
    public static boolean flying(Entity e) {
        return !e.isRemoved() && !(e instanceof AbstractCannonProjectile p && p.isInGround());
    }

    /**
     * Полёт снаряда точно описывается физикой CBC (сопротивление и гравитация из его свойств): класс не меняет
     * силы. Ракеты (тяга) и снаряды с особыми силами - нет: их полёт прогнозируется по замерам.
     */
    public static boolean exactBallistics(Entity e, ProjectileType type) {
        if (type != ProjectileType.SHELL || !(e instanceof AbstractCannonProjectile)) return false;
        return EXACT.computeIfAbsent(e.getClass(), c -> declaredIn(c, "getForces", net.minecraft.world.phys.Vec3.class,
                net.minecraft.world.phys.Vec3.class) == AbstractCannonProjectile.class
                && declaredIn(c, "getDragForce") == AbstractCannonProjectile.class);
    }

    /** Класс, в котором объявлена действующая реализация метода (от класса вверх), или null. */
    @Nullable
    private static Class<?> declaredIn(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                return m.getDeclaringClass();
            } catch (NoSuchMethodException ignored) {
                // выше по иерархии
            } catch (LinkageError | RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
