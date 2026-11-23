package dev.radarfcs.compat;

import net.neoforged.fml.ModList;

/** Какие необязательные моды загружены (без ссылок на их классы). */
public final class Mods {
    public static final boolean CREATE_RADARS = ModList.get().isLoaded("create_radar");
    /** CBC Interception: снаряды CBC сбивают друг друга в полёте (без него перехват снарядов бесполезен). */
    public static final boolean INTERCEPTION = ModList.get().isLoaded("cbcintercept");

    /** Sable (физические под-уровни): постройки кораблей и поворотных частей. */
    public static final boolean SABLE = ModList.get().isLoaded("sable");

    /** CBC: Firepower Components: компактные станки (обычный и для автопушек). */
    public static final boolean FIREPOWER = ModList.get().isLoaded("cbc_firepower_components");

    /** Create: Flight Control: мост автопилота (блок есть только с ним). */
    public static final boolean FLIGHT_CONTROL = ModList.get().isLoaded("create_flight_control");

    private Mods() {}
}
