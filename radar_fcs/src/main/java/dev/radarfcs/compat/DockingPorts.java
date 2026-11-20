package dev.radarfcs.compat;

import dev.radarfcs.FcsMod;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Стыковочный порт Create Simulated (идёт в составе Create Aeronautics): блок simulated:docking_connector. Порт
 * выдвигается по сигналу редстоуна (свойство POWERED), выдвинутые порты ближе 4 блоков друг к другу и навстречу
 * притягиваются магнитом и сцепляются (isLocked). Код Simulated не копируется: блок узнаётся по имени, сцепка
 * читается его публичным методом через рефлексию.
 */
public final class DockingPorts {
    public static final ResourceLocation CONNECTOR = ResourceLocation.fromNamespaceAndPath("simulated", "docking_connector");
    /** Конец порта (выдвинутая пластина) - в стольких блоках от центра блока по направлению порта. */
    public static final double TIP = 1.5;
    private static Method isLocked;
    private static boolean broken;
    /** Порты, выдвинутые по команде контроллеров (измерение -> позиции). */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<Level>, java.util.Set<BlockPos>> FORCED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private DockingPorts() {}

    public static boolean isConnector(BlockState st) {
        return CONNECTOR.equals(BuiltInRegistries.BLOCK.getKey(st.getBlock())) && st.hasProperty(BlockStateProperties.FACING);
    }

    public static boolean isConnector(Level level, BlockPos pos) {
        return level.isLoaded(pos) && isConnector(level.getBlockState(pos));
    }

    /** Направление порта «наружу» (в осях блока; на корабле - в его плот-координатах). */
    public static Direction facing(BlockState st) {
        return st.getValue(BlockStateProperties.FACING);
    }

    /** Конец порта в координатах блока (на корабле - плот). */
    public static Vec3 localTip(BlockPos pos, Direction facing) {
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(TIP));
    }

    /** Порт сцеплен с другим. */
    public static boolean isLocked(Level level, BlockPos pos) {
        if (!isConnector(level, pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || broken) return false;
        try {
            if (isLocked == null) isLocked = be.getClass().getMethod("isLocked");
            return (boolean) isLocked.invoke(be);
        } catch (Throwable t) {
            broken = true;
            FcsMod.LOG.warn("[radar_fcs] Simulated docking connector API changed: {}", t.toString());
            return false;
        }
    }

    /** Порт выдвинут (сигнал есть). */
    public static boolean isPowered(Level level, BlockPos pos) {
        return isConnector(level, pos) && level.getBlockState(pos).getValue(BlockStateProperties.POWERED);
    }

    /**
     * Выдвинуть порт (как сигнал редстоуна) или вернуть ему его собственный сигнал. Состояние меняется без обновления
     * соседей: порт сам перечитывает сигнал только при изменении соседей, контроллер повторяет команду каждый тик.
     *
     * @return порт выдвигается по команде (нужно вернуть его сигнал, когда команда кончится)
     */
    public static boolean setPowered(Level level, BlockPos pos, boolean on) {
        java.util.Set<BlockPos> f = FORCED.computeIfAbsent(level.dimension(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (on) f.add(pos.immutable());
        else f.remove(pos);
        if (!isConnector(level, pos)) return false;
        BlockState st = level.getBlockState(pos);
        boolean want = on || level.hasNeighborSignal(pos);
        if (st.getValue(BlockStateProperties.POWERED) != want) level.setBlock(pos, st.setValue(BlockStateProperties.POWERED, want), 2);
        return on;
    }

    /**
     * Начало тика мира (до тиков блоков): выдвинутые по команде порты снова с сигналом. Порт сверяет POWERED с сигналом
     * соседей при любом их изменении (и когда сам ставит пластину) - без этого он задвинулся бы на тик и расстыковался.
     */
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre e) {
        if (e.getLevel().isClientSide) return;
        java.util.Set<BlockPos> f = FORCED.get(e.getLevel().dimension());
        if (f == null || f.isEmpty()) return;
        Level level = e.getLevel();
        for (BlockPos p : f) {
            if (!level.isLoaded(p)) continue;
            BlockState st = level.getBlockState(p);
            if (isConnector(st) && !st.getValue(BlockStateProperties.POWERED)) level.setBlock(p, st.setValue(BlockStateProperties.POWERED, true), 2);
        }
    }

    public static void clear() {
        FORCED.clear();
    }

    /** Забыть команду выдвижения (чанк контроллера выгружен), состояние порта не трогать. */
    public static void unforce(Level level, BlockPos pos) {
        java.util.Set<BlockPos> f = FORCED.get(level.dimension());
        if (f != null) f.remove(pos);
    }

    /** Блок выдаётся за подходящий: тот же корабль (или оба на земле) и не дальше radius. */
    public static boolean linkable(Level level, BlockPos controller, BlockPos connector, double radius) {
        if (!isConnector(level, connector)) return false;
        if (controller.distSqr(connector) > radius * radius) return false;
        java.util.UUID a = SableCompat.containingId(level, controller), b = SableCompat.containingId(level, connector);
        return java.util.Objects.equals(a, b);
    }

    /** Порт пригоден для работы: блок на месте. */
    @Nullable
    public static Direction facingAt(Level level, BlockPos pos) {
        return isConnector(level, pos) ? facing(level.getBlockState(pos)) : null;
    }
}
