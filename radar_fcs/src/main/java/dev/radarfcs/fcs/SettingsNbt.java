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
        t.put("SpeedMins", doubles(s.speedMin));
        t.put("SpeedMaxs", doubles(s.speedMax));
        t.putBoolean("DropStatic", s.dropStatic);
        t.putInt("StaticTicks", s.staticTicks);
        t.putBoolean("HostileOnly", s.hostileOnly);
        t.put("Whitelist", list(s.whitelist));
        t.put("Blacklist", list(s.blacklist));
        t.putBoolean("IffWhitelist", s.iffWhitelist);
        t.put("IffCodes", list(s.iffCodes));
        t.putBoolean("DropMoving", s.dropMoving);
        t.putInt("DropShots", s.dropAfterShots);
        t.putString("Priority", s.priority.name());
        t.putBoolean("SwitchByPriority", s.switchByPriority);
        t.putString("Switch", s.switchMode.name());
        t.putInt("SwitchShots", s.switchShots);
        t.putInt("SwitchTicks", s.switchTicks);
        t.putString("Threat", s.threat.name());
        t.putInt("ThreatRadius", s.threatRadius);
        t.putBoolean("ImpactFirst", s.impactFirst);
        net.minecraft.nbt.ListTag mts = new net.minecraft.nbt.ListTag();
        s.mountTargets.forEach((pos, mt) -> {
            CompoundTag m = new CompoundTag();
            m.putLong("Pos", pos);
            byte[] e = new byte[EngageSettings.CATEGORIES];
            for (int i = 0; i < e.length; i++) e[i] = (byte) (mt.enabled[i] ? 1 : 0);
            m.putByteArray("Types", e);
            m.putIntArray("Levels", mt.level.clone());
            mts.add(m);
        });
        if (!mts.isEmpty()) t.put("MountTargets", mts);
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
        if (t.contains("SpeedMins")) {
            readDoubles(t.getList("SpeedMins", net.minecraft.nbt.Tag.TAG_DOUBLE), s.speedMin);
            readDoubles(t.getList("SpeedMaxs", net.minecraft.nbt.Tag.TAG_DOUBLE), s.speedMax);
        } else {
            // до 0.13 окно скорости было общим для всех типов целей
            java.util.Arrays.fill(s.speedMin, t.getDouble("SpeedMin"));
            java.util.Arrays.fill(s.speedMax, t.getDouble("SpeedMax"));
        }
        if (t.contains("DropStatic")) s.dropStatic = t.getBoolean("DropStatic");
        if (t.contains("StaticTicks")) s.staticTicks = t.getInt("StaticTicks");
        if (t.contains("HostileOnly")) s.hostileOnly = t.getBoolean("HostileOnly");
        read(t.getList("Whitelist", net.minecraft.nbt.Tag.TAG_STRING), s.whitelist);
        read(t.getList("Blacklist", net.minecraft.nbt.Tag.TAG_STRING), s.blacklist);
        s.iffWhitelist = t.getBoolean("IffWhitelist");
        read(t.getList("IffCodes", net.minecraft.nbt.Tag.TAG_STRING), s.iffCodes);
        s.dropMoving = t.getBoolean("DropMoving");
        s.dropAfterShots = t.getInt("DropShots");
        s.priority = enumOr(EngageSettings.Priority.class, t.getString("Priority"), s.priority);
        if (t.contains("SwitchByPriority")) s.switchByPriority = t.getBoolean("SwitchByPriority");
        s.switchMode = enumOr(EngageSettings.SwitchMode.class, t.getString("Switch"), s.switchMode);
        if (t.contains("SwitchShots")) s.switchShots = t.getInt("SwitchShots");
        if (t.contains("SwitchTicks")) s.switchTicks = t.getInt("SwitchTicks");
        else if (t.contains("SwitchSeconds")) s.switchTicks = t.getInt("SwitchSeconds") * 20;
        // перехват (0.12); у старых блоков типов было 4 - снаряды и ракеты берутся по умолчанию
        s.threat = enumOr(EngageSettings.Threat.class, t.getString("Threat"), s.threat);
        if (t.contains("ThreatRadius")) s.threatRadius = t.getInt("ThreatRadius");
        if (t.contains("ImpactFirst")) s.impactFirst = t.getBoolean("ImpactFirst");
        net.minecraft.nbt.ListTag mts = t.getList("MountTargets", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int k = 0; k < mts.size(); k++) {
            CompoundTag m = mts.getCompound(k);
            dev.radarfcs.core.MountTargets mt = dev.radarfcs.core.MountTargets.from(s);
            byte[] e = m.getByteArray("Types");
            for (int i = 0; i < Math.min(e.length, EngageSettings.CATEGORIES); i++) mt.enabled[i] = e[i] != 0;
            int[] l = m.getIntArray("Levels");
            for (int i = 0; i < Math.min(l.length, EngageSettings.CATEGORIES); i++) mt.level[i] = l[i];
            s.mountTargets.put(m.getLong("Pos"), mt);
        }
        return s.sanitize();
    }

    private static net.minecraft.nbt.ListTag list(java.util.List<String> l) {
        net.minecraft.nbt.ListTag out = new net.minecraft.nbt.ListTag();
        for (String e : l) out.add(net.minecraft.nbt.StringTag.valueOf(e));
        return out;
    }

    private static net.minecraft.nbt.ListTag doubles(double[] a) {
        net.minecraft.nbt.ListTag out = new net.minecraft.nbt.ListTag();
        for (double v : a) out.add(net.minecraft.nbt.DoubleTag.valueOf(v));
        return out;
    }

    private static void readDoubles(net.minecraft.nbt.ListTag tag, double[] into) {
        for (int i = 0; i < Math.min(tag.size(), into.length); i++) into[i] = tag.getDouble(i);
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
