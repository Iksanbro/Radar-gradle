package dev.aeroschematics.compat;

import net.neoforged.fml.ModList;

public enum Mods {
    DRIVE_BY_WIRE("drivebywire"),
    SIMULATED("simulated");

    private final String id;
    private Boolean loaded;

    Mods(String id) {
        this.id = id;
    }

    public boolean isLoaded() {
        if (this.loaded == null) {
            ModList list = ModList.get();
            this.loaded = list != null && list.isLoaded(this.id);
        }
        return this.loaded;
    }
}
