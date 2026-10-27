package dev.radarfcs.fcs;

import dev.radarfcs.core.EngageSettings;
import net.minecraft.nbt.CompoundTag;

/** Настройки вычислителя в NBT (сохранение блока, синхронизация с клиентом, пакет с экрана настроек). */
public final class SettingsNbt {
    private SettingsNbt() {}

    public static CompoundTag write(EngageSettings s) {
        CompoundTag t = new CompoundTag();
        t.putString("Fire", s.fireMode.name());
        t.putInt("Burst", s.burstLength);
        t.putInt("Pause", s.pauseTicks);
        byte[] en = new byte[EngageSettings.CATEGORIES];
        for (int i = 0; i < en.length; i++) en[i] = (byte) (s.enabled[i] ? 1 : 0);
        t.putByteArray("Types", en);
        t.putIntArray("Levels", s.level.clone());
        t.putDouble("SpeedMin", s.speedMin);
        t.putDouble("SpeedMax", s.speedMax);
        t.putBoolean("DropStatic", s.dropStatic);
        t.putInt("StaticTicks", s.staticTicks);
        t.putBoolean("HostileOnly", s.hostileOnly);
        t.put("Whitelist", list(s.whitelist));
        t.put("Blacklist", list(s.blacklist));
        t.putBoolean("DropMoving", s.dropMoving);
        t.putInt("DropShots", s.dropAfterShots);
        t.putString("Priority", s.priority.name());
        t.putBoolean("SwitchByPriority", s.switchByPriority);
        t.putString("Switch", s.switchMode.name());
        t.putInt("SwitchShots", s.switchShots);
        t.putInt("SwitchSeconds", s.switchSeconds);
        return t;
    }

    /** Чтение; отсутствующие ключи - значения по умолчанию (старые блоки без настроек). */
    public static EngageSettings read(CompoundTag t) {
        EngageSettings s = new EngageSettings();
        s.fireMode = enumOr(EngageSettings.FireMode.class, t.getString("Fire"), s.fireMode);
        if (t.contains("Burst")) s.burstLength = t.getInt("Burst");
        if (t.contains("Pause")) s.pauseTicks = t.getInt("Pause");
        byte[] en = t.getByteArray("Types");
        for (int i = 0; i < Math.min(en.length, EngageSettings.CATEGORIES); i++) s.enabled[i] = en[i] != 0;
        int[] lv = t.getIntArray("Levels");
        for (int i = 0; i < Math.min(lv.length, EngageSettings.CATEGORIES); i++) s.level[i] = lv[i];
        s.speedMin = t.getDouble("SpeedMin");
        s.speedMax = t.getDouble("SpeedMax");
        if (t.contains("DropStatic")) s.dropStatic = t.getBoolean("DropStatic");
        if (t.contains("StaticTicks")) s.staticTicks = t.getInt("StaticTicks");
        if (t.contains("HostileOnly")) s.hostileOnly = t.getBoolean("HostileOnly");
        read(t.getList("Whitelist", net.minecraft.nbt.Tag.TAG_STRING), s.whitelist);
        read(t.getList("Blacklist", net.minecraft.nbt.Tag.TAG_STRING), s.blacklist);
        s.dropMoving = t.getBoolean("DropMoving");
        s.dropAfterShots = t.getInt("DropShots");
        s.priority = enumOr(EngageSettings.Priority.class, t.getString("Priority"), s.priority);
        if (t.contains("SwitchByPriority")) s.switchByPriority = t.getBoolean("SwitchByPriority");
        s.switchMode = enumOr(EngageSettings.SwitchMode.class, t.getString("Switch"), s.switchMode);
        if (t.contains("SwitchShots")) s.switchShots = t.getInt("SwitchShots");
        if (t.contains("SwitchSeconds")) s.switchSeconds = t.getInt("SwitchSeconds");
        return s.sanitize();
    }

    private static net.minecraft.nbt.ListTag list(java.util.List<String> l) {
        net.minecraft.nbt.ListTag out = new net.minecraft.nbt.ListTag();
        for (String e : l) out.add(net.minecraft.nbt.StringTag.valueOf(e));
        return out;
    }

    private static void read(net.minecraft.nbt.ListTag tag, java.util.List<String> into) {
        for (int i = 0; i < tag.size(); i++) into.add(tag.getString(i));
    }

    private static <E extends Enum<E>> E enumOr(Class<E> c, String name, E def) {
        try {
            return name == null || name.isEmpty() ? def : Enum.valueOf(c, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
