package dev.radarfcs.core;

/**
 * Свои цели отдельной пушки: какие типы целей ей разрешены и их уровни приоритета (вместо общих настроек
 * вычислителя). Остальное (списки, скорость, режим огня, сброс, выбор) - общее.
 */
public final class MountTargets {
    public final boolean[] enabled = new boolean[EngageSettings.CATEGORIES];
    public final int[] level = new int[EngageSettings.CATEGORIES];

    /** Начальные значения - текущие общие настройки вычислителя. */
    public static MountTargets from(EngageSettings s) {
        MountTargets m = new MountTargets();
        System.arraycopy(s.enabled, 0, m.enabled, 0, EngageSettings.CATEGORIES);
        System.arraycopy(s.level, 0, m.level, 0, EngageSettings.CATEGORIES);
        return m;
    }

    public MountTargets copy() {
        MountTargets m = new MountTargets();
        System.arraycopy(enabled, 0, m.enabled, 0, EngageSettings.CATEGORIES);
        System.arraycopy(level, 0, m.level, 0, EngageSettings.CATEGORIES);
        return m;
    }
}
