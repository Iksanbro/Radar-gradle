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
 * Подшипник орудия: собранная постройка - под-уровень Sable, физическое тело. Угол от шестерни target идёт за валом, как
 * у подшипника Create (convertToAngular(об/мин) градусов за тик); угол сервопривода prof догоняет его с ограниченным
 * ускорением (плавный разгон и торможение); тело ведётся к нему пружиной с демпфером на каждом шаге физики
 * ({@link SableRotor#drive}) - упирается в блоки (тогда угол от шестерни дальше не накручивается), толкает сущности.
 */
public class GunBearingBlockEntity extends KineticBlockEntity {
    /** Собранные подшипники загруженного мира - для сервопривода на каждом шаге физики. */
    private static final Set<GunBearingBlockEntity> ACTIVE = Collections.newSetFromMap(new WeakHashMap<>());

    @Nullable private UUID subLevel;
    @Nullable private BlockPos plotFront;
    /** Угол от шестерни (градусы от положения при сборке): куда поворотную часть ведёт вал. */
    private double target;
    /**
     * Угол сервопривода сейчас и тиком раньше и его скорость (градусов за тик): идёт за target с ограниченным ускорением -
     * плавный разгон и торможение, без рывков, раскачивающих постройку.
     */
    private double prof, lastProf, profVel;
    /** Ускорение сервопривода, градусов за тик² (до 19°/тик - за 5 тиков). */
    private static final double ACCEL = 4;
    /** Насколько угол от шестерни может уйти вперёд фактического, градусов: дальше - часть упёрлась, шестерня проскальзывает. */
    private static final double MAX_LEAD = 4;
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
            target = prof = lastProf = profVel = 0;
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
        lastProf = prof;
        double step = Math.max(-90, Math.min(90, degPerTick()));
        if (step != 0) {
            target = Aim.wrap180(target + step);
            setChanged();
        }
        // физичная сборка: поворотная часть упёрлась (в блок, в постройку) - угол от шестерни не накручивается дальше
        // MAX_LEAD от фактического (шестерня «проскальзывает»), помеху убрали - часть не рванёт на накопленный угол
        double actual = angle();
        if (!Double.isNaN(actual)) {
            double lead = Aim.wrap180(target - actual);
            if (Math.abs(lead) > MAX_LEAD) target = Aim.wrap180(actual + Math.copySign(MAX_LEAD, lead));
            double pl = Aim.wrap180(prof - actual);
            if (Math.abs(pl) > MAX_LEAD) {
                prof = Aim.wrap180(actual + Math.copySign(MAX_LEAD, pl));
                profVel = 0;
            }
        }
        // профиль: скорость - как у вала плюс догон остатка, но не быстрее, чем можно затормозить с ускорением ACCEL
        double err = Aim.wrap180(target - prof);
        double want = step + Math.copySign(Math.sqrt(2 * ACCEL * Math.abs(err)), err);
        profVel += Math.max(-ACCEL, Math.min(ACCEL, want - profVel));
        if (Math.abs(err - profVel) < 0.05 && Math.abs(profVel - step) <= ACCEL) {
            prof = target;                                                  // дошли - встать точно
            profVel = step;
        } else {
            prof = Aim.wrap180(prof + profVel);
        }
    }

    /** Шаг физики: цель сервопривода - между углом профиля прошлого и этого тика. */
    private void physicsStep(double dt) {
        if (!(level instanceof ServerLevel sl) || subLevel == null || handle == null || plotFront == null) return;
        // угол за тик - здесь, на первом шаге физики тика: после команд вычислителя и приводов в этом же тике
        // (в тике блока поворот запаздывал бы на тик, и привод учился бы не на той команде)
        integrate(sl.getGameTime());
        double pt = Math.max(0, Math.min(1, SableRotor.partialPhysicsTick(sl)));
        double goal = Aim.wrap180(lastProf + Aim.wrap180(prof - lastProf) * pt);
        SableRotor.drive(sl, handle, worldPosition, facing(), subLevel, plotFront, Math.toRadians(goal),
                Math.toRadians(profVel) * 20, dt);
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

    /** Отставание поворотной части от угла вала, при котором приводы не учатся, градусов (обычное на ходу - 0.3-4°). */
    private static final double SETTLE = 5;

    /** Отстаёт ли поворотная часть какого-то подшипника этого мира от угла вала больше SETTLE (только что собрана, упёрлась). */
    public static boolean settling(net.minecraft.world.level.Level level) {
        for (GunBearingBlockEntity be : List.copyOf(ACTIVE)) {
            if (be.level != level || be.isRemoved() || be.subLevel == null) continue;
            double a = be.angle();
            if (!Double.isNaN(a) && Math.abs(Aim.wrap180(be.prof - a)) > SETTLE) return true;
        }
        return false;
    }

    /** Собранные подшипники (для тестов). */
    public static List<GunBearingBlockEntity> active() {
        return List.copyOf(ACTIVE);
    }

    public static void physicsStepAll(ServerLevel stepping, double dt) {
        // сначала внешние подшипники (на земле или корабле), потом стоящие на их поворотных частях: поза вложенной
        // части считается от уже поставленной позы основания
        List<GunBearingBlockEntity> order = new java.util.ArrayList<>();
        for (GunBearingBlockEntity be : ACTIVE) if (be.level == stepping || be.isRemoved()) order.add(be);
        java.util.Map<GunBearingBlockEntity, Integer> depth = new java.util.HashMap<>();
        for (GunBearingBlockEntity be : order) depth.put(be, be.depth(order));
        order.sort(java.util.Comparator.comparingInt(depth::get));
        for (GunBearingBlockEntity be : order) {
            if (be.isRemoved() || be.subLevel == null) {
                ACTIVE.remove(be);
                continue;
            }
            try {
                be.physicsStep(dt);
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
        target = prof = lastProf = tag.getDouble("Target");
        profVel = 0;
    }
}
