package dev.radarfcs.compat;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.block.AntennaBlock;
import dev.radarfcs.block.IffTransponderBlock;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Контрапшены Create: опознание и поиск транспондера «свой» среди их блоков. */
public final class ContraptionCompat {
    private static final long CACHE_TICKS = 100;
    /** Бит маски: в контрапшене есть антенна радара. */
    private static final long ANTENNA_BIT = 1L << 62;
    /** Сущность -> {битовая маска каналов транспондеров и антенны, тик}. */
    private static final Map<Entity, long[]> IFF_CACHE = new WeakHashMap<>();

    private ContraptionCompat() {}

    public static boolean isContraption(Entity e) {
        return e instanceof AbstractContraptionEntity;
    }

    /** Пушка CBC на станке - не цель сама по себе (станок неподвижен, а свои пушки не стреляют друг в друга). */
    public static boolean isMountedCannon(Entity e) {
        return e instanceof PitchOrientedContraptionEntity;
    }

    /** Не цель: пушка на станке, вращающаяся антенна радара, поезда и т.п. (ignoredContraptionHints). */
    public static boolean isIgnored(Entity e) {
        return isMountedCannon(e) || Reflect.nameMatches(e.getClass(), FcsConfig.IGNORED_CONTRAPTION_HINTS.get())
                || (mask(e, e.level().getGameTime()) & ANTENNA_BIT) != 0;
    }

    /** Есть ли в контрапшене транспондер этого канала. Результат кэшируется на 5 секунд. */
    public static boolean hasTransponder(Entity e, int channel, long now) {
        return (mask(e, now) & (1L << channel)) != 0;
    }

    private static long mask(Entity e, long now) {
        synchronized (IFF_CACHE) {
            long[] c = IFF_CACHE.get(e);
            if (c != null && now - c[1] < CACHE_TICKS && now >= c[1]) return c[0];
        }
        long mask = scan(e);
        synchronized (IFF_CACHE) {
            IFF_CACHE.put(e, new long[]{mask, now});
        }
        return mask;
    }

    private static long scan(Entity e) {
        if (!(e instanceof AbstractContraptionEntity ace)) return 0;
        Contraption c = ace.getContraption();
        if (c == null) return 0;
        long mask = 0;
        for (Map.Entry<BlockPos, StructureBlockInfo> en : c.getBlocks().entrySet()) {
            BlockState s = en.getValue().state();
            if (s.getBlock() instanceof IffTransponderBlock) mask |= 1L << IffTransponderBlock.channelOf(s);
            else if (s.getBlock() instanceof AntennaBlock) mask |= ANTENNA_BIT;
        }
        return mask;
    }
}
