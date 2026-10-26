package dev.radarfcs.compat;

import net.neoforged.fml.ModList;

/** Какие необязательные моды загружены (без ссылок на их классы). */
public final class Mods {
    public static final boolean CREATE_RADARS = ModList.get().isLoaded("create_radar");

    private Mods() {}
}
