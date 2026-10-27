package dev.radarfcs.fcs;

import dev.radarfcs.core.Track;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;

/**
 * Белый и чёрный списки целей вычислителя. Записи: ID типа сущности (minecraft:zombie), тег типа (#minecraft:undead),
 * все сущности мода (create:*), имя (без двоеточия): игрока, корабля или сущности с биркой. Регистр не важен.
 */
public final class TargetLists {
    private TargetLists() {}

    public static boolean matches(List<String> list, Track t) {
        for (String e : list) if (matches(e, t)) return true;
        return false;
    }

    public static boolean matches(String entry, Track t) {
        String e = entry.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty()) return false;
        if (e.startsWith("#")) {
            if (!(t.handle instanceof Entity ent)) return false;
            ResourceLocation rl = ResourceLocation.tryParse(e.substring(1));
            return rl != null && ent.getType().is(TagKey.create(Registries.ENTITY_TYPE, rl));
        }
        if (e.indexOf(':') >= 0) {
            if (t.typeId == null) return false;
            if (e.endsWith(":*")) return t.typeId.startsWith(e.substring(0, e.length() - 1));
            return t.typeId.equals(e);
        }
        if (t.playerName != null) return t.playerName.equalsIgnoreCase(e);
        return t.name != null && t.name.equalsIgnoreCase(e);
    }
}
