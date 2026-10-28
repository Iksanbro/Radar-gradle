package dev.radarfcs.iff;

import dev.radarfcs.core.EngageSettings;
import net.minecraft.nbt.CompoundTag;

/** Настройки транспондера с экрана: код, имя, режим выделения, радиус, наследование защиты. */
public record TransponderSettings(String code, String name, TransponderMode mode, int radius, InheritPolicy inherit) {
    public static final int NAME_LENGTH = 32;

    public static String cleanName(String n) {
        if (n == null) return "";
        String s = n.replaceAll("[\\p{Cntrl}§]", "").trim();
        return s.length() > NAME_LENGTH ? s.substring(0, NAME_LENGTH) : s;
    }

    public CompoundTag write() {
        CompoundTag t = new CompoundTag();
        t.putString("Code", code);
        t.putString("Name", name);
        t.putString("Mode", mode.name());
        t.putInt("Radius", radius);
        t.put("Inherit", inherit.write());
        return t;
    }

    public static TransponderSettings read(CompoundTag t) {
        TransponderMode mode;
        try {
            mode = TransponderMode.valueOf(t.getString("Mode"));
        } catch (IllegalArgumentException e) {
            mode = TransponderMode.RADIUS;
        }
        return new TransponderSettings(EngageSettings.cleanCode(t.getString("Code")), cleanName(t.getString("Name")), mode,
                t.getInt("Radius"), InheritPolicy.read(t.getCompound("Inherit")));
    }
}
