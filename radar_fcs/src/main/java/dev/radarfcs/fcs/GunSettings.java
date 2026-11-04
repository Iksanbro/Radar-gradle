package dev.radarfcs.fcs;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.core.CbcFlightModel;
import dev.radarfcs.core.FlightModel;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;

/**
 * Настройки орудия (орудийной сети): тип - баллистика и огонь орудия без станка CBC, участие в автоприцеливании и в
 * стрельбе, имя. Хранятся в орудийных линках сети (у всех одинаковые, новее - по {@link #stamp}).
 */
public final class GunSettings {
    public enum Type {
        /** Автопушка: баллистика автопушки CBC по умолчанию, сигнал огня - пока орудие на цели. */
        AUTOCANNON,
        /** Среднее орудие: между автопушкой и большой пушкой, огонь импульсом (по фронту сигнала). */
        MEDIUM,
        /** Большая пушка: баллистика большой пушки CBC по умолчанию, огонь импульсом. */
        BIG,
        /** Своё оружие: наводится прямо на цель, без упреждения и баллистики; сигнал - пока на цели. */
        CUSTOM;

        public String key() {
            return "radar_fcs.gun.type." + name().toLowerCase(Locale.ROOT);
        }

        /** Огонь импульсом (по фронту сигнала, как большая пушка CBC), иначе сигнал держится. */
        public boolean pulse() {
            return this == MEDIUM || this == BIG;
        }
    }

    public static final int NAME_MAX = 24;
    public static final GunSettings DEFAULT = new GunSettings(Type.CUSTOM, true, true, "", 0);

    public final Type type;
    public final boolean autoAim, fire;
    public final String name;
    public final long stamp;

    public GunSettings(Type type, boolean autoAim, boolean fire, String name, long stamp) {
        this.type = type == null ? Type.CUSTOM : type;
        this.autoAim = autoAim;
        this.fire = fire;
        this.name = cleanName(name);
        this.stamp = stamp;
    }

    public static String cleanName(String n) {
        String s = ChatFormatting.stripFormatting(n == null ? "" : n);
        s = s == null ? "" : s.strip();
        return s.length() > NAME_MAX ? s.substring(0, NAME_MAX) : s;
    }

    public GunSettings withStamp(long st) {
        return new GunSettings(type, autoAim, fire, name, st);
    }

    /** Модель полёта орудия без станка CBC (у кастома - почти мгновенный «луч»). */
    public FlightModel model() {
        return switch (type) {
            case AUTOCANNON -> FcsConfig.rapidFallback();
            case BIG -> FcsConfig.bigFallback();
            case MEDIUM -> CbcFlightModel.ballistic((FcsConfig.RAPID_SPEED.get() + FcsConfig.BIG_SPEED.get()) / 2,
                    (FcsConfig.RAPID_GRAVITY.get() + FcsConfig.BIG_GRAVITY.get()) / 2,
                    (FcsConfig.RAPID_DRAG.get() + FcsConfig.BIG_DRAG.get()) / 2, false,
                    Math.max(FcsConfig.RAPID_TICKS.get(), FcsConfig.BIG_TICKS.get() / 2));
            case CUSTOM -> CbcFlightModel.ballistic(64.0, 0.0, 0.0, false, 128);
        };
    }

    public CompoundTag write() {
        CompoundTag t = new CompoundTag();
        t.putString("Type", type.name());
        t.putBoolean("AutoAim", autoAim);
        t.putBoolean("Fire", fire);
        if (!name.isEmpty()) t.putString("Name", name);
        t.putLong("Stamp", stamp);
        return t;
    }

    public static GunSettings read(CompoundTag t) {
        if (t == null || !t.contains("Type")) return DEFAULT;
        Type type;
        try {
            type = Type.valueOf(t.getString("Type"));
        } catch (IllegalArgumentException e) {
            type = Type.CUSTOM;
        }
        return new GunSettings(type, !t.contains("AutoAim") || t.getBoolean("AutoAim"), !t.contains("Fire") || t.getBoolean("Fire"),
                t.getString("Name"), t.getLong("Stamp"));
    }
}
