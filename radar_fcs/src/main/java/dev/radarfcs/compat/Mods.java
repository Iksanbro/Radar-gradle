package dev.radarfcs.compat;

import net.neoforged.fml.ModList;

/** Какие необязательные моды загружены (без ссылок на их классы). */
public final class Mods {
    public static final boolean CREATE_RADARS = ModList.get().isLoaded("create_radar");
    /** CBC Interception: снаряды CBC сбивают друг друга в полёте (без него перехват снарядов бесполезен). */
    public static final boolean INTERCEPTION = ModList.get().isLoaded("cbcintercept");

    /** Sable (физические под-уровни): постройки кораблей и поворотных частей. */
    public static final boolean SABLE = ModList.get().isLoaded("sable");
    /** Create Simulated (в составе Create Aeronautics): вид подшипника орудия - их модель и текстура (не копируются). */
    public static final boolean SIMULATED = ModList.get().isLoaded("simulated");

    /** CBC: Firepower Components: компактные станки (обычный и для автопушек). */
    public static final boolean FIREPOWER = ModList.get().isLoaded("cbc_firepower_components");

    /** CBC Enchanced Shells: ракеты со стартовой площадки и ракетного станка - носы ракет этого мода есть только с ним. */
    public static final boolean ENHANCED_SHELLS = ModList.get().isLoaded("cbc_enhanced_shells");

    /** Create: Flight Control: мост автопилота (блок есть только с ним). */
    public static final boolean FLIGHT_CONTROL = ModList.get().isLoaded("create_flight_control");

    private Mods() {}
}
