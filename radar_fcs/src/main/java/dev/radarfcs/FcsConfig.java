package dev.radarfcs;

import dev.radarfcs.core.CbcFlightModel;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Серверный конфиг: world/serverconfig/radar_fcs-server.toml */
public final class FcsConfig {
    public enum SensorMode {
        /** Цели с радаров (своих и Create Radars); без радаров - собственный сенсор, если requireRadar = false. */
        AUTO,
        /** Только цели с радаров. */
        RADAR,
        /** Собственный сенсор в радиусе sensorRange (радар нужен только как разрешение работы). */
        DIRECT
    }

    public static final ModConfigSpec SPEC;

    // связи
    public static final ModConfigSpec.IntValue LINK_RADIUS, DISCOVER_INTERVAL;
    public static final ModConfigSpec.BooleanValue REQUIRE_RADAR, AUTO_ASSEMBLE, YIELD_TO_RADAR_CONTROLLERS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> RADAR_BLOCKS, RAPID_CLASS_HINTS;
    // сенсор и треки
    public static final ModConfigSpec.EnumValue<SensorMode> SENSOR_MODE;
    public static final ModConfigSpec.DoubleValue SENSOR_RANGE, MIN_RANGE, MIN_SPEED;
    public static final ModConfigSpec.IntValue SCAN_INTERVAL, MIN_SAMPLES, TRACK_WINDOW, TRACK_TIMEOUT;
    // цели
    public static final ModConfigSpec.BooleanValue TARGET_MOBS, HOSTILE_ONLY, TARGET_PLAYERS, TARGET_SHIPS,
            TARGET_CONTRAPTIONS, TARGET_PROJECTILES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_ENTITIES, IGNORED_CONTRAPTION_HINTS,
            FRIENDLY_PLAYERS, FRIENDLY_RADAR_IDS;
    // свой-чужой и безопасность
    public static final ModConfigSpec.DoubleValue IFF_RADIUS, SAFETY_CONE, SAFETY_MARGIN;
    public static final ModConfigSpec.BooleanValue REQUIRE_LOS;
    // наведение
    public static final ModConfigSpec.DoubleValue FIRE_TOLERANCE, DEADBAND, SLEW, SHAFT_SPEED, LATENCY, ACCEL_HORIZON, YAW_OFFSET,
            TARGET_SWITCH_RATIO, RAPID_SPREAD;
    public static final ModConfigSpec.IntValue LEAD_ITERATIONS, FIRE_MIN_TRACK;
    public static final ModConfigSpec.BooleanValue REQUIRE_ROTATION, USE_ACCELERATION, HIGH_ARC, INVERT_PITCH,
            INHERIT_PLATFORM_VELOCITY, DISCRETE_INTERCEPT;
    // огонь
    public static final ModConfigSpec.IntValue BIG_PULSE_INTERVAL, RAPID_FIRE_POWER;
    public static final ModConfigSpec.BooleanValue LEARN_FROM_SHOTS;
    public static final ModConfigSpec.DoubleValue LEARN_SMOOTHING, MAX_RANGE;
    // баллистика по умолчанию
    public static final ModConfigSpec.DoubleValue BIG_SPEED, BIG_GRAVITY, BIG_DRAG, RAPID_SPEED, RAPID_GRAVITY, RAPID_DRAG;
    public static final ModConfigSpec.IntValue BIG_TICKS, RAPID_TICKS;
    // отображение
    public static final ModConfigSpec.BooleanValue GLOW_TARGETS, MARK_TARGETS;
    public static final ModConfigSpec.DoubleValue MARKER_RANGE;
    public static final ModConfigSpec.BooleanValue LOG_SHOTS;
    // радары мода
    public static final ModConfigSpec.DoubleValue NETWORK_RANGE;
    public static final ModConfigSpec.BooleanValue RADAR_LOS, RADAR_SIZE_AFFECTS_RANGE;
    public static final ModConfigSpec.DoubleValue BEARING_BASE_RANGE, BEARING_RANGE_PER_DISH, BEARING_MAX_RANGE,
            BEARING_BEAM_WIDTH, BEARING_MIN_EL, BEARING_MAX_EL;
    public static final ModConfigSpec.DoubleValue ARRAY_BASE_RANGE, ARRAY_RANGE_PER_PANEL, ARRAY_MAX_RANGE, ARRAY_HALF_ANGLE,
            ARRAY_MIN_EL, ARRAY_MAX_EL, ARRAY_STRESS;
    public static final ModConfigSpec.IntValue ARRAY_MAX_PANELS;
    public static final ModConfigSpec.DoubleValue DOME_RANGE, DOME_MIN_EL, DOME_MAX_EL;
    public static final ModConfigSpec.IntValue DOME_INTERVAL, MONITOR_MAX_SIZE, DESIGNATION_TICKS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Связь вычислителя с пушками и радарами (plug & play)").push("links");
        LINK_RADIUS = b.comment("Радиус поиска станков CBC и радаров вокруг вычислителя, блоков")
                .defineInRange("linkRadius", 16, 2, 64);
        DISCOVER_INTERVAL = b.comment("Как часто пересканировать подключённые блоки, тиков")
                .defineInRange("discoverInterval", 40, 10, 1200);
        REQUIRE_RADAR = b.comment("Вычислитель работает только при работающем радаре: своём (канал, networkRange) или Create Radars (linkRadius)")
                .define("requireRadar", true);
        RADAR_BLOCKS = b.comment("Запасной список ID блоков-радаров Create Radars (если его API изменилось)")
                .defineListAllowEmpty("radarBlocks", List.of("create_radar:radar_bearing", "create_radar:plane_radar",
                        "create_radar:sky_radar"), () -> "", o -> o instanceof String);
        RAPID_CLASS_HINTS = b.comment("Фрагменты имени класса контрапшена скорострельной пушки неизвестного аддона")
                .defineListAllowEmpty("rapidClassHints", List.of("Autocannon", "Rotary", "Gatling", "MachineGun", "Rocket"),
                        () -> "", o -> o instanceof String);
        AUTO_ASSEMBLE = b.comment("Собирать пушку на станке автоматически").define("autoAssemble", true);
        YIELD_TO_RADAR_CONTROLLERS = b.comment("Не трогать станки, у которых стоят auto yaw/pitch контроллеры Create Radars")
                .define("yieldToRadarControllers", true);
        b.pop();

        b.comment("Обнаружение и сопровождение целей").push("sensor");
        SENSOR_MODE = b.comment("AUTO: цели с радаров; RADAR: только с радаров; DIRECT: собственный сенсор (радар - только разрешение)")
                .defineEnum("sensorMode", SensorMode.AUTO);
        SENSOR_RANGE = b.comment("Дальность собственного сенсора (режим DIRECT), блоков")
                .defineInRange("sensorRange", 192.0, 16.0, 1024.0);
        MIN_RANGE = b.comment("Ближе этой дистанции огонь не ведётся").defineInRange("minRange", 6.0, 0.0, 64.0);
        MIN_SPEED = b.comment("Минимальная скорость цели, блоков/сек. Медленнее - цель неактивна: нет огня и отметки")
                .defineInRange("minSpeed", 0.6, 0.0, 50.0);
        SCAN_INTERVAL = b.comment("Период замеров позиции целей, тиков (1 - каждый тик, нужно для быстрых целей)")
                .defineInRange("scanIntervalTicks", 1, 1, 20);
        MIN_SAMPLES = b.comment("Сколько замеров нужно, прежде чем цель станет активной").defineInRange("minSamples", 3, 2, 20);
        TRACK_WINDOW = b.comment("Окно оценки скорости и ускорения, тиков").defineInRange("trackWindowTicks", 16, 4, 80);
        TRACK_TIMEOUT = b.comment("Трек удаляется после стольких тиков без замеров").defineInRange("trackTimeoutTicks", 40, 5, 400);
        b.pop();

        b.comment("Какие цели атаковать").push("targets");
        TARGET_MOBS = b.define("targetMobs", true);
        HOSTILE_ONLY = b.comment("Только враждебные мобы").define("hostileOnly", true);
        TARGET_PLAYERS = b.comment("Атаковать игроков (кроме владельца, его команды и friendlyPlayers)").define("targetPlayers", false);
        TARGET_SHIPS = b.comment("Физические конструкции Sable / Create Aeronautics").define("targetShips", true);
        TARGET_CONTRAPTIONS = b.comment("Движущиеся контрапшены Create").define("targetContraptions", true);
        TARGET_PROJECTILES = b.comment("Снаряды и ракеты (противоракетная оборона)").define("targetProjectiles", false);
        IGNORED_ENTITIES = b.defineListAllowEmpty("ignoredEntities", List.of("minecraft:bat"), () -> "", o -> o instanceof String);
        IGNORED_CONTRAPTION_HINTS = b.comment("Контрапшены, чьё имя класса содержит эти фрагменты, не цели (поезда, гантри...)")
                .defineListAllowEmpty("ignoredContraptionHints", List.of("Carriage", "Gantry", "PitchOriented"),
                        () -> "", o -> o instanceof String);
        FRIENDLY_PLAYERS = b.defineListAllowEmpty("friendlyPlayers", List.of(), () -> "", o -> o instanceof String);
        FRIENDLY_RADAR_IDS = b.comment("Корабли с идентификационным транспондером Create Radars и одним из этих секретных ID - свои")
                .defineListAllowEmpty("friendlyRadarIds", List.of(), () -> "", o -> o instanceof String);
        b.pop();

        b.comment("Свой-чужой и безопасность стрельбы").push("iff");
        IFF_RADIUS = b.comment("Всё в этом радиусе от наземного транспондера «свой» защищено")
                .defineInRange("iffRadius", 12.0, 1.0, 64.0);
        SAFETY_CONE = b.comment("Не стрелять, если свой ближе этого угла к траектории, градусов")
                .defineInRange("safetyCone", 2.0, 0.0, 30.0);
        SAFETY_MARGIN = b.comment("Дополнительный запас от траектории до своих, блоков")
                .defineInRange("safetyMargin", 2.0, 0.0, 16.0);
        REQUIRE_LOS = b.comment("Не стрелять, если траекторию перекрывают блоки").define("requireLineOfSight", true);
        b.pop();

        b.comment("Наведение").push("aiming");
        FIRE_TOLERANCE = b.comment("Допуск выстрела, градусов. Огонь, когда снаряд из фактического положения ствола пройдёт от цели",
                        "не дальше её размера или этого угла на дальности до цели (что больше)")
                .defineInRange("fireToleranceDeg", 0.2, 0.0, 10.0);
        RAPID_SPREAD = b.comment("Автопушки: очередь начинается при расчётном промахе до размера цели + этого запаса, блоков,",
                        "и продолжается, пока промах меньше удвоенного порога")
                .defineInRange("rapidFireMarginBlocks", 0.5, 0.0, 8.0);
        DEADBAND = b.comment("Изменения угла меньше этого не отрабатываются: нет дрожания и бесконечного доводения")
                .defineInRange("deadband", 0.1, 0.0, 5.0);
        REQUIRE_ROTATION = b.comment("Станок поворачивается только при поданном вращении: на вал поворота (снизу) и вал наклона (сбоку).",
                        "Скорость поворота - как от валов CBC (256 об/мин = 9.6 град/тик) x shaftSpeedMultiplier.",
                        "false - поворот без кинетики со скоростью slewDegPerTick")
                .define("requireRotation", true);
        SHAFT_SPEED = b.comment("Множитель скорости поворота от валов (1 = как у CBC)").defineInRange("shaftSpeedMultiplier", 1.0, 0.05, 20.0);
        SLEW = b.comment("Скорость поворота станка без кинетики (requireRotation = false), градусов/тик")
                .defineInRange("slewDegPerTick", 6.0, 0.2, 90.0);
        LATENCY = b.comment("Дополнительная задержка выстрела для упреждения, тиков (обычно 0: задержки CBC учтены)")
                .defineInRange("extraLatencyTicks", 0.0, -5.0, 20.0);
        LEAD_ITERATIONS = b.comment("Итерации расчёта упреждения (метод секущих)").defineInRange("leadIterations", 16, 2, 40);
        DISCRETE_INTERCEPT = b.comment("Потиковое упреждение: целиться в положение цели на конец тика, в котором снаряд проходит точку",
                        "(так Minecraft проверяет попадание). Нужно для быстрых мелких целей")
                .define("discreteIntercept", true);
        FIRE_MIN_TRACK = b.comment("Огонь только по цели, которую сопровождаем не меньше стольких тиков (оценка скорости устоялась)")
                .defineInRange("fireMinTrackTicks", 6, 0, 100);
        USE_ACCELERATION = b.comment("Учитывать ускорение летящих целей (манёвр, разгон)").define("useAcceleration", true);
        ACCEL_HORIZON = b.comment("Сколько тиков вперёд доверять оценке ускорения").defineInRange("accelHorizonTicks", 20.0, 0.0, 200.0);
        TARGET_SWITCH_RATIO = b.comment("Переключаться на новую цель, только если она ближе текущей в это число раз")
                .defineInRange("targetSwitchRatio", 0.85, 0.1, 1.0);
        HIGH_ARC = b.comment("Навесная траектория вместо настильной").define("highArc", false);
        YAW_OFFSET = b.comment("Калибровка для нестандартных станков: yaw станка = мировой yaw + yawOffset")
                .defineInRange("yawOffset", 0.0, -360.0, 360.0);
        INVERT_PITCH = b.comment("Калибровка для нестандартных станков").define("invertPitch", false);
        INHERIT_PLATFORM_VELOCITY = b.comment("Снаряд пушки на корабле наследует скорость корабля")
                .define("inheritPlatformVelocity", true);
        b.pop();

        b.comment("Огонь").push("firing");
        BIG_PULSE_INTERVAL = b.comment("Минимальный интервал импульсов огня большой пушки, тиков")
                .defineInRange("bigPulseInterval", 10, 2, 2400);
        RAPID_FIRE_POWER = b.comment("Сила сигнала огня автопушек (1..15 = темп стрельбы CBC)")
                .defineInRange("rapidFirePower", 15, 1, 15);
        LEARN_FROM_SHOTS = b.comment("Уточнять баллистику по реальным выстрелам").define("learnFromShots", true);
        LEARN_SMOOTHING = b.defineInRange("learnSmoothing", 0.3, 0.01, 1.0);
        MAX_RANGE = b.comment("Предельная горизонтальная дальность стрельбы, блоков").defineInRange("maxRange", 800.0, 16.0, 4096.0);
        b.pop();

        b.comment("Баллистика, если её не удалось прочитать у пушки (до первого выстрела)").push("ballistics");
        b.push("big");
        BIG_SPEED = b.comment("Начальная скорость, блоков/тик").defineInRange("speed", 4.0, 0.1, 50.0);
        BIG_GRAVITY = b.comment("Гравитация, блоков/тик² (отрицательная)").defineInRange("gravity", -0.05, -1.0, 0.0);
        BIG_DRAG = b.defineInRange("drag", 0.01, 0.0, 0.5);
        BIG_TICKS = b.defineInRange("maxFlightTicks", 600, 20, 2400);
        b.pop();
        b.push("rapid");
        RAPID_SPEED = b.defineInRange("speed", 6.0, 0.1, 50.0);
        RAPID_GRAVITY = b.defineInRange("gravity", -0.025, -1.0, 0.0);
        RAPID_DRAG = b.defineInRange("drag", 0.01, 0.0, 0.5);
        RAPID_TICKS = b.defineInRange("maxFlightTicks", 60, 5, 2400);
        b.pop();
        b.pop();

        b.comment("Отображение целей").push("display");
        GLOW_TARGETS = b.comment("Подсвечивать активные цели свечением").define("glowTargets", true);
        MARK_TARGETS = b.comment("Отмечать активные цели частицами (видно и корабли)").define("markTargets", true);
        MARKER_RANGE = b.comment("Игроки дальше этого не видят отметки, блоков").defineInRange("markerRange", 192.0, 16.0, 1024.0);
        LOG_SHOTS = b.comment("Писать в лог каждый выстрел: цель, решение, ошибку ствола (отладка)").define("logShots", false);
        b.pop();

        b.comment("Радары мода: сеть по каналам (цвет красителя), без проводов и связок").push("radar");
        NETWORK_RANGE = b.comment("Вычислители и мониторы видят радары своего канала в этом радиусе, блоков")
                .defineInRange("networkRange", 256.0, 16.0, 4096.0);
        RADAR_LOS = b.comment("Радар не видит сквозь блоки").define("requireLineOfSight", true);
        RADAR_SIZE_AFFECTS_RANGE = b.comment("Крупные цели видны дальше, мелкие ближе (x0.6..x1.5)").define("sizeAffectsRange", true);
        DESIGNATION_TICKS = b.comment("Сколько держится назначение цели с монитора, тиков").defineInRange("designationTicks", 1200, 20, 72000);
        MONITOR_MAX_SIZE = b.comment("Наибольший размер монитора из блоков (ширина и высота)").defineInRange("monitorMaxSize", 6, 1, 16);
        b.push("bearing");
        BEARING_BASE_RANGE = b.comment("Дальность антенны с одним облучателем, блоков").defineInRange("baseRange", 64.0, 8.0, 2048.0);
        BEARING_RANGE_PER_DISH = b.comment("Прибавка дальности за каждый блок отражателя").defineInRange("rangePerDish", 12.0, 0.0, 256.0);
        BEARING_MAX_RANGE = b.defineInRange("maxRange", 384.0, 8.0, 4096.0);
        BEARING_BEAM_WIDTH = b.comment("Ширина луча по азимуту, градусов").defineInRange("beamWidth", 12.0, 1.0, 90.0);
        BEARING_MIN_EL = b.defineInRange("minElevation", -10.0, -90.0, 90.0);
        BEARING_MAX_EL = b.defineInRange("maxElevation", 80.0, -90.0, 90.0);
        b.pop();
        b.push("phasedArray");
        ARRAY_BASE_RANGE = b.comment("Дальность контроллера без панелей, блоков").defineInRange("baseRange", 48.0, 8.0, 2048.0);
        ARRAY_RANGE_PER_PANEL = b.defineInRange("rangePerPanel", 10.0, 0.0, 256.0);
        ARRAY_MAX_RANGE = b.defineInRange("maxRange", 320.0, 8.0, 4096.0);
        ARRAY_MAX_PANELS = b.defineInRange("maxPanels", 64, 1, 1024);
        ARRAY_HALF_ANGLE = b.comment("Половина сектора обзора по азимуту, градусов").defineInRange("halfAngle", 60.0, 5.0, 90.0);
        ARRAY_MIN_EL = b.defineInRange("minElevation", -15.0, -90.0, 90.0);
        ARRAY_MAX_EL = b.defineInRange("maxElevation", 75.0, -90.0, 90.0);
        ARRAY_STRESS = b.comment("Нагрузка на кинетику за элемент решётки, единиц стресса на об/мин").defineInRange("stressPerPanel", 0.5, 0.0, 64.0);
        b.pop();
        b.push("dome");
        DOME_RANGE = b.defineInRange("range", 48.0, 8.0, 1024.0);
        DOME_INTERVAL = b.comment("Период обзора, тиков").defineInRange("scanInterval", 10, 1, 200);
        DOME_MIN_EL = b.defineInRange("minElevation", -20.0, -90.0, 90.0);
        DOME_MAX_EL = b.defineInRange("maxElevation", 85.0, -90.0, 90.0);
        b.pop();
        b.pop();

        SPEC = b.build();
    }

    public static CbcFlightModel bigFallback() {
        return CbcFlightModel.ballistic(BIG_SPEED.get(), BIG_GRAVITY.get(), BIG_DRAG.get(), false, BIG_TICKS.get());
    }

    public static CbcFlightModel rapidFallback() {
        return CbcFlightModel.ballistic(RAPID_SPEED.get(), RAPID_GRAVITY.get(), RAPID_DRAG.get(), false, RAPID_TICKS.get());
    }

    /** Значение, даже если серверный конфиг ещё не загружен (главное меню, подсказки предметов). */
    public static double safe(ModConfigSpec.DoubleValue v) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return v.getDefault();
        }
    }

    private FcsConfig() {}
}
