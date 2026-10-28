package dev.radarfcs.compat;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.block.IffTransponderBlock;
import dev.radarfcs.block.IffTransponderBlockEntity;
import dev.radarfcs.iff.TransponderMode;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.IffRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Контрапшены Create: опознание и поиск транспондера «свой» среди их блоков. */
public final class ContraptionCompat {
    private static final long CACHE_TICKS = 100;
    private record Cached(List<Beacon> beacons, long tick) {}

    private record LevelCached(List<Entity> entities, long tick) {}

    /** Сущность -> её транспондеры и тик замера. */
    private static final Map<Entity, Cached> IFF_CACHE = new WeakHashMap<>();
    /** Уровень -> контрапшены с транспондерами в этом тике. */
    private static final Map<Level, LevelCached> LEVEL_CACHE = new WeakHashMap<>();

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

    /** Транспондер в контрапшене: код, режим (null - по месту), радиус, позиция блока в контрапшене. */
    public record Beacon(String code, @Nullable TransponderMode mode, int radius, BlockPos local) {}

    /** Есть ли в контрапшене свой (по правилу вычислителя) транспондер. Транспондеры кэшируются на 5 секунд. */
    public static boolean hasTransponder(Entity e, IffRule rule, long now) {
        for (Beacon b : beacons(e, now)) if (rule.accepts(b.code())) return true;
        return false;
    }

    public static List<Beacon> beacons(Entity e, long now) {
        synchronized (IFF_CACHE) {
            Cached c = IFF_CACHE.get(e);
            if (c != null && now - c.tick < CACHE_TICKS && now >= c.tick) return c.beacons;
        }
        List<Beacon> list = scan(e);
        synchronized (IFF_CACHE) {
            IFF_CACHE.put(e, new Cached(list, now));
        }
        return list;
    }

    /** Мировая точка центра блока контрапшена. */
    public static Vec3 toWorld(Entity e, BlockPos local) {
        return e instanceof AbstractContraptionEntity ace ? ace.toGlobalVector(Vec3.atCenterOf(local), 1f) : e.position();
    }

    /** Контрапшены уровня с транспондерами (обновляется раз в тик). */
    public static List<Entity> withTransponders(ServerLevel level, long now) {
        synchronized (LEVEL_CACHE) {
            LevelCached c = LEVEL_CACHE.get(level);
            if (c != null && c.tick == now) return c.entities;
        }
        List<Entity> out = new ArrayList<>();
        for (AbstractContraptionEntity ace : level.getEntities(EntityTypeTest.forClass(AbstractContraptionEntity.class), x -> true)) {
            if (!beacons(ace, now).isEmpty()) out.add(ace);
        }
        synchronized (LEVEL_CACHE) {
            LEVEL_CACHE.put(level, new LevelCached(out, now));
        }
        return out;
    }

    /** Транспондеры среди блоков контрапшена (данные блока - как у поставленного транспондера). */
    private static List<Beacon> scan(Entity e) {
        if (!(e instanceof AbstractContraptionEntity ace)) return List.of();
        Contraption c = ace.getContraption();
        if (c == null) return List.of();
        List<Beacon> out = new ArrayList<>();
        for (Map.Entry<BlockPos, StructureBlockInfo> en : c.getBlocks().entrySet()) {
            if (!(en.getValue().state().getBlock() instanceof IffTransponderBlock)) continue;
            CompoundTag nbt = en.getValue().nbt();
            String code = nbt == null ? "" : EngageSettings.cleanCode(nbt.getString("Code"));
            TransponderMode mode = nbt == null ? null : IffTransponderBlockEntity.modeOf(nbt.getString("Mode"));
            int radius = nbt != null && nbt.contains("Radius") ? Math.max(1, nbt.getInt("Radius")) : 12;
            out.add(new Beacon(code, mode, radius, en.getKey()));
        }
        return out;
    }
}
