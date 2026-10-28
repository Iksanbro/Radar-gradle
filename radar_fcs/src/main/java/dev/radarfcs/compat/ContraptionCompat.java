package dev.radarfcs.compat;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.block.IffTransponderBlock;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.IffRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Контрапшены Create: опознание и поиск транспондера «свой» среди их блоков. */
public final class ContraptionCompat {
    private static final long CACHE_TICKS = 100;
    private record Cached(List<String> codes, long tick) {}

    /** Сущность -> коды её транспондеров и тик замера. */
    private static final Map<Entity, Cached> IFF_CACHE = new WeakHashMap<>();

    private ContraptionCompat() {}

    public static boolean isContraption(Entity e) {
        return e instanceof AbstractContraptionEntity;
    }

    /** Пушка CBC на станке - не цель сама по себе (станок неподвижен, а свои пушки не стреляют друг в друга). */
    public static boolean isMountedCannon(Entity e) {
        return e instanceof PitchOrientedContraptionEntity;
    }

    /** Не цель: пушка на станке, поезда и т.п. (ignoredContraptionHints). */
    public static boolean isIgnored(Entity e) {
        return isMountedCannon(e) || Reflect.nameMatches(e.getClass(), FcsConfig.IGNORED_CONTRAPTION_HINTS.get());
    }

    /** Есть ли в контрапшене свой (по правилу вычислителя) транспондер. Коды кэшируются на 5 секунд. */
    public static boolean hasTransponder(Entity e, IffRule rule, long now) {
        for (String code : codes(e, now)) if (rule.accepts(code)) return true;
        return false;
    }

    private static List<String> codes(Entity e, long now) {
        synchronized (IFF_CACHE) {
            Cached c = IFF_CACHE.get(e);
            if (c != null && now - c.tick < CACHE_TICKS && now >= c.tick) return c.codes;
        }
        List<String> codes = scan(e);
        synchronized (IFF_CACHE) {
            IFF_CACHE.put(e, new Cached(codes, now));
        }
        return codes;
    }

    /** Коды транспондеров среди блоков контрапшена (данные блока - как у поставленного транспондера). */
    private static List<String> scan(Entity e) {
        if (!(e instanceof AbstractContraptionEntity ace)) return List.of();
        Contraption c = ace.getContraption();
        if (c == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<BlockPos, StructureBlockInfo> en : c.getBlocks().entrySet()) {
            if (!(en.getValue().state().getBlock() instanceof IffTransponderBlock)) continue;
            CompoundTag nbt = en.getValue().nbt();
            out.add(nbt == null ? "" : EngageSettings.cleanCode(nbt.getString("Code")));
        }
        return out;
    }
}
