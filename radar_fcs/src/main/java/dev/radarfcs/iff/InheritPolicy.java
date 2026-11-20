package dev.radarfcs.iff;

import dev.radarfcs.core.Track;
import dev.radarfcs.core.TargetCategory;
import dev.radarfcs.fcs.TargetLists;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Кто в зоне транспондера наследует его защиту: типы целей (маска TargetCategory), белый список (наследуют всегда)
 * и чёрный (никогда). Записи - как у списков целей вычислителя: ns:id, #тег, ns:*, имя.
 */
public record InheritPolicy(int mask, List<String> whitelist, List<String> blacklist) {
    public static final int MAX_ENTRIES = 16, MAX_ENTRY = 64;
    public static final InheritPolicy ALL = new InheritPolicy(TargetCategory.ALL, List.of(), List.of());

    public InheritPolicy {
        mask &= TargetCategory.ALL;
        whitelist = clean(whitelist);
        blacklist = clean(blacklist);
    }

    public boolean inherits(Track t) {
        if (TargetLists.matches(blacklist, t)) return false;
        if (TargetLists.matches(whitelist, t)) return true;
        return (mask & TargetCategory.of(t.kind, t.hostile).bit) != 0;
    }

    public static List<String> clean(List<String> in) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String e : in) {
            if (e == null) continue;
            String s = e.trim();
            if (!s.isEmpty() && s.length() <= MAX_ENTRY && out.size() < MAX_ENTRIES) out.add(s);
        }
        return List.copyOf(out);
    }

    public CompoundTag write() {
        CompoundTag t = new CompoundTag();
        t.putInt("Mask", mask);
        t.put("White", list(whitelist));
        t.put("Black", list(blacklist));
        return t;
    }

    public static InheritPolicy read(CompoundTag t) {
        if (t == null || !t.contains("Mask")) return ALL;
        return new InheritPolicy(t.getInt("Mask"), strings(t.getList("White", Tag.TAG_STRING)), strings(t.getList("Black", Tag.TAG_STRING)));
    }

    private static ListTag list(List<String> l) {
        ListTag out = new ListTag();
        for (String s : l) out.add(StringTag.valueOf(s));
        return out;
    }

    private static List<String> strings(ListTag tag) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tag.size(); i++) out.add(tag.getString(i));
        return out;
    }
}
