package dev.radarfcs.block;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.radarfcs.FcsMod;
import dev.radarfcs.compat.Mods;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.compat.sable.SableRotor;
import dev.radarfcs.core.Aim;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Подшипник орудия: собранная постройка - под-уровень Sable на шарнире. Угол сервопривода target идёт за валом, как у
 * подшипника Create (convertToAngular(об/мин) градусов за тик); сам шарнир держит угол жёстко и без раскачки:
 * момент инерции - вокруг оси вместе со всем, что стоит на поворотной части (в том числе на вложенных подшипниках),
 * критическое демпфирование, касания основания и поворотной части выключены на каждом шаге физики, цель сервопривода
 * плавно меняется между тиками (см. {@link SableRotor#servo}).
 */
public class GunBearingBlockEntity extends KineticBlockEntity {
    /** Собранные подшипники загруженного мира - для сервопривода на каждом шаге физики. */
    private static final Set<GunBearingBlockEntity> ACTIVE = Collections.newSetFromMap(new WeakHashMap<>());

    @Nullable private UUID subLevel;
    @Nullable private BlockPos plotFront;
    /** Угол сервопривода сейчас и тиком раньше (градусы от положения при сборке). */
    private double target, lastTarget;
    private long stepTick = Long.MIN_VALUE;
    @Nullable private Object handle;
    @Nullable private Component lastError;

    public GunBearingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public Direction facing() {
        return getBlockState().getValue(DirectionalKineticBlock.FACING);
    }

    public boolean assembled() {
        return subLevel != null;
    }

    @Nullable
    public UUID subLevel() {
        return subLevel;
    }

    /**
     * Скорость кольца-шестерни: от шестерни Create рядом - малой вплотную сбоку (в зацеплении крутится навстречу) или
     * большой по диагонали (кольцо вдвое быстрее, навстречу); ось шестерни - как у подшипника. 0 - шестерни нет.
     */
    public float ringSpeed() {
        if (level == null) return 0;
        Direction.Axis axis = facing().getAxis();
        for (Direction d : Direction.values()) {
            if (d.getAxis() == axis) continue;
            BlockPos p = worldPosition.relative(d);
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof KineticBlockEntity k && cogOnAxis(k, axis, false)) return -k.getSpeed();
        }
        for (Direction a : Direction.values()) {
            for (Direction b : Direction.values()) {
                if (a.getAxis() == axis || b.getAxis() == axis || a.getAxis() == b.getAxis() || a.ordinal() > b.ordinal()) continue;
                BlockPos p = worldPosition.relative(a).relative(b);
                if (level.isLoaded(p) && level.getBlockEntity(p) instanceof KineticBlockEntity k && cogOnAxis(k, axis, true)) return -2 * k.getSpeed();
            }
        }
        return 0;
    }

    /** Ближняя шестерня, с которой кольцо в зацеплении (для отрисовки), или null. */
    @Nullable
    public KineticBlockEntity meshedCog() {
        if (level == null) return null;
        Direction.Axis axis = facing().getAxis();
        for (Direction d : Direction.values()) {
            if (d.getAxis() == axis) continue;
            BlockPos p = worldPosition.relative(d);
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof KineticBlockEntity k && cogOnAxis(k, axis, false)) return k;
        }
        return null;
    }

    private static boolean cogOnAxis(KineticBlockEntity k, Direction.Axis axis, boolean large) {
        BlockState st = k.getBlockState();
        if (!(st.getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate r)) return false;
        boolean cog = large ? com.simibubi.create.content.kinetics.simpleRelays.ICogWheel.isLargeCog(st)
                : com.simibubi.create.content.kinetics.simpleRelays.ICogWheel.isSmallCog(st);
        return cog && r.getRotationAxis(st) == axis;
    }

    /** Поворот за тик, градусов вокруг FACING: от кольца-шестерни, как у подшипника Create (0 - шестерня стоит). */
    public double degPerTick() {
        double d = convertToAngular(ringSpeed());
        return facing().getAxisDirection() == Direction.AxisDirection.POSITIVE ? d : -d;
    }

    /** Первый блок поворотной части (в плоте), куда идёт вал насквозь; null - не собран. */
    @Nullable
    public BlockPos plotFront() {
        return plotFront;
    }

    // вал насквозь: подшипник передаёт вращение своего вала первому блоку поворотной части (он в плоте Sable, не сосед)
    @Override
    public List<BlockPos> addPropagationLocations(com.simibubi.create.content.kinetics.base.IRotate block, BlockState state, List<BlockPos> neighbours) {
        if (plotFront != null && subLevel != null) neighbours.add(plotFront);
        return super.addPropagationLocations(block, state, neighbours);
    }

    @Override
    public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom, BlockState stateTo, BlockPos diff,
                                     boolean connectedViaAxes, boolean connectedViaCogs) {
        if (plotFront != null && subLevel != null && target.getBlockPos().equals(plotFront) && level != null
                && stateTo.getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate r
                && r.hasShaftTowards(level, plotFront, stateTo, facing().getOpposite())) return 1;
        return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    @Override
    public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
        return plotFront != null && subLevel != null && other.getBlockPos().equals(plotFront) || super.isCustomConnection(other, state, otherState);
    }

    /** Угол сервопривода сейчас (градусы от положения при сборке). */
    public double target() {
        return target;
    }

    /** Фактический угол поворотной части относительно основания (NaN - не собран или под-уровня нет). */
    public double angle() {
        if (!(level instanceof ServerLevel sl) || subLevel == null || !Mods.SABLE) return Double.NaN;
        return SableRotor.angleDeg(sl, worldPosition, facing(), subLevel);
    }

    public void toggleAssembly(Player p) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!Mods.SABLE) {
            p.displayClientMessage(Component.translatable("radar_fcs.bearing.no_sable").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (assembled()) {
            p.displayClientMessage(Component.translatable("radar_fcs.bearing.assembled_info",
                    String.format(Locale.ROOT, "%.1f", angle())).withStyle(ChatFormatting.AQUA), true);
            return;
        }
        try {
            SableRotor.Assembled a = SableRotor.assemble(sl, worldPosition, facing());
            if (a == null) {
                lastError = Component.translatable("radar_fcs.bearing.nothing");
                p.displayClientMessage(lastError.copy().withStyle(ChatFormatting.GOLD), true);
                return;
            }
            subLevel = a.subLevel();
            plotFront = a.plotFront();
            target = lastTarget = 0;
            lastError = null;
            reattach(sl);
            // вал насквозь: переподключить кинетику - теперь соседом стал первый блок поворотной части в плоте
            detachKinetics();
            attachKinetics();
            // подшипники, уехавшие в собранную часть (наклон на площадке курса), - сразу на новое основание: иначе их
            // поворотная часть пару тиков ничем не держится, падает на площадку и её отбрасывает
            for (BlockPos mp : a.moved()) {
                if (sl.isLoaded(mp) && sl.getBlockEntity(mp) instanceof GunBearingBlockEntity r && r != this && r.subLevel != null
                        && !SableRotor.valid(r.handle) && SableRotor.exists(sl, r.subLevel)) r.reattach(sl);
            }
            setChanged();
            sendData();
            p.displayClientMessage(Component.translatable("radar_fcs.bearing.assembled").withStyle(ChatFormatting.GREEN), true);
        } catch (AssemblyException e) {
            lastError = e.component;
            p.displayClientMessage(e.component.copy().withStyle(ChatFormatting.RED), true);
        }
    }

    private void reattach(ServerLevel sl) {
        if (subLevel == null || plotFront == null) return;
        SableRotor.remove(handle);
        handle = SableRotor.attach(sl, worldPosition, facing(), subLevel, plotFront);
        if (handle != null) ACTIVE.add(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel sl) || subLevel == null || !Mods.SABLE) return;
        if (!SableRotor.valid(handle)) {
            // после загрузки мира шарнир не сохранён: подключить снова, как только под-уровень загружен
            if (SableRotor.exists(sl, subLevel)) reattach(sl);
            return;
        }
        // физика Sable спит, пока постройки стоят, - шага физики нет, и поворот не считался бы: шестерня крутится -
        // разбудить поворотную часть; если шаг физики в прошлом тике всё же не прошёл - посчитать поворот здесь
        double turn = degPerTick();
        if (turn != 0) SableRotor.wake(sl, subLevel);
        if (sl.getGameTime() - stepTick > 1) integrate(sl.getGameTime());
        // вал насквозь: блок поворотной части сам подшипник не видит (он не сосед) - если он без вращения, а вал
        // крутится, переподключить кинетику (поставили новый блок, загрузился плот)
        if (sl.getGameTime() % 20 == 7 && plotFront != null && getSpeed() != 0 && sl.isLoaded(plotFront)
                && sl.getBlockEntity(plotFront) instanceof KineticBlockEntity k && k.getSpeed() == 0
                && k.getBlockState().getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate r
                && r.hasShaftTowards(sl, plotFront, k.getBlockState(), facing().getOpposite())) {
            detachKinetics();
            attachKinetics();
        }
    }

    /** Поворот за тик now (один раз за тик). */
    private void integrate(long now) {
        if (now == stepTick) return;
        stepTick = now;
        lastTarget = target;
        double step = degPerTick();
        if (step != 0) {
            target = Aim.wrap180(target + Math.max(-90, Math.min(90, step)));
            setChanged();
        }
    }

    /** Шаг физики: цель сервопривода - между углом прошлого и этого тика. */
    private void physicsStep() {
        if (!(level instanceof ServerLevel sl) || subLevel == null || handle == null || plotFront == null) return;
        // угол за тик - здесь, на первом шаге физики тика: после команд вычислителя и приводов в этом же тике
        // (в тике блока поворот запаздывал бы на тик, и привод учился бы не на той команде)
        integrate(sl.getGameTime());
        double pt = SableRotor.partialPhysicsTick(sl);
        double goal = Aim.wrap180(lastTarget + Aim.wrap180(target - lastTarget) * Math.max(0, Math.min(1, pt)));
        SableRotor.drive(sl, handle, worldPosition, facing(), subLevel, plotFront, Math.toRadians(goal));
    }

    /** Сколько подшипников под этим (0 - стоит на земле или корабле). */
    private int depth(List<GunBearingBlockEntity> all) {
        if (!(level instanceof ServerLevel sl)) return 0;
        int d = 0;
        BlockPos at = worldPosition;
        java.util.Set<GunBearingBlockEntity> seen = new java.util.HashSet<>();
        while (d < 8) {
            UUID on = SableCompat.containingId(sl, at);
            if (on == null) break;
            GunBearingBlockEntity parent = null;
            for (GunBearingBlockEntity o : all) if (on.equals(o.subLevel) && seen.add(o)) parent = o;
            if (parent == null) break;
            d++;
            at = parent.worldPosition;
        }
        return d;
    }

    /** Под-уровни подшипников, стоящих на под-уровне parent (их поворотные части держатся на нём). */
    private static List<UUID> childSubs(ServerLevel sl, UUID parent) {
        List<UUID> out = new java.util.ArrayList<>();
        for (GunBearingBlockEntity be : List.copyOf(ACTIVE)) {
            if (be.isRemoved() || be.subLevel == null || be.level != sl) continue;
            if (parent.equals(SableCompat.containingId(sl, be.worldPosition))) out.add(be.subLevel);
        }
        return out;
    }

    /** Собранные подшипники (для тестов). */
    public static List<GunBearingBlockEntity> active() {
        return List.copyOf(ACTIVE);
    }

    public static void physicsStepAll() {
        // сначала внешние подшипники (на земле или корабле), потом стоящие на их поворотных частях: поза вложенной
        // части считается от уже поставленной позы основания
        List<GunBearingBlockEntity> order = new java.util.ArrayList<>(ACTIVE);
        java.util.Map<GunBearingBlockEntity, Integer> depth = new java.util.HashMap<>();
        for (GunBearingBlockEntity be : order) depth.put(be, be.depth(order));
        order.sort(java.util.Comparator.comparingInt(depth::get));
        for (GunBearingBlockEntity be : order) {
            if (be.isRemoved() || be.subLevel == null) {
                ACTIVE.remove(be);
                continue;
            }
            try {
                be.physicsStep();
            } catch (RuntimeException e) {
                FcsMod.LOG.warn("[radar_fcs] gun bearing servo failed at {}: {}", be.getBlockPos(), e.toString());
                ACTIVE.remove(be);
            }
        }
    }

    /** Блок снят, перенесён в под-уровень или чанк выгружен: шарнир снимается (новый BE подключит его снова). */
    @Override
    public void invalidate() {
        super.invalidate();
        if (Mods.SABLE && level != null && !level.isClientSide) SableRotor.remove(handle);
        handle = null;
        ACTIVE.remove(this);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.gun_bearing").withStyle(ChatFormatting.GRAY)));
        if (!assembled()) {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.bearing.not_assembled").withStyle(ChatFormatting.GOLD)));
            if (lastError != null) tooltip.add(Component.literal("    ").append(lastError.copy().withStyle(ChatFormatting.RED)));
        } else {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.bearing.angle",
                    String.format(Locale.ROOT, "%.1f", target)).withStyle(ChatFormatting.AQUA)));
        }
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (subLevel != null) tag.putUUID("SubLevel", subLevel);
        if (plotFront != null) tag.put("PlotFront", NbtUtils.writeBlockPos(plotFront));
        tag.putDouble("Target", target);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        subLevel = tag.hasUUID("SubLevel") ? tag.getUUID("SubLevel") : null;
        plotFront = NbtUtils.readBlockPos(tag, "PlotFront").orElse(null);
        target = lastTarget = tag.getDouble("Target");
    }
}
