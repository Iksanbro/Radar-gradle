package dev.radarfcs.block;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.radarfcs.FcsMod;
import dev.radarfcs.compat.Mods;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.compat.sable.SableRotor;
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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Вращатель орудия: собранная постройка - под-уровень Sable на шарнире; сервопривод держит угол target, который
 * идёт к заданному вычислителем want не быстрее, чем позволяет вал (как контроллеры наводки: 0.0375°/тик на об/мин).
 */
public class GunRotatorBlockEntity extends KineticBlockEntity {
    /** Собранные вращатели загруженного мира - для сервопривода на каждом шаге физики. */
    private static final Set<GunRotatorBlockEntity> ACTIVE = Collections.newSetFromMap(new WeakHashMap<>());

    @Nullable private UUID subLevel;
    @Nullable private BlockPos plotFront;
    /** Угол, заданный вычислителем; угол сервопривода сейчас и тиком раньше (градусы от положения при сборке). */
    private double want, target, lastTarget;
    @Nullable private Object handle;
    @Nullable private Component lastError;

    public GunRotatorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
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

    /** Наибольшая скорость поворота, градусов/тик (0 - нет вращения или перегрузка). */
    public double degPerTick() {
        if (isOverStressed()) return 0;
        return Math.abs(convertToAngular(getSpeed())) * 0.125;
    }

    /** Задать угол (градусы от положения при сборке); поворот к нему - со скоростью вала. */
    public void setWant(double deg) {
        want = deg;
    }

    public double want() {
        return want;
    }

    /** Угол сервопривода сейчас (градусы от положения при сборке). */
    public double target() {
        return target;
    }

    /** Фактический угол под-уровня относительно основания (NaN - не собран или под-уровня нет). */
    public double angle() {
        if (!(level instanceof ServerLevel sl) || subLevel == null || !Mods.SABLE) return Double.NaN;
        return SableRotor.angleDeg(sl, worldPosition, facing(), subLevel);
    }

    /** Ось вращения в мире (у вращателя на поворотной части - с учётом её ориентации). */
    public Vec3 worldAxis() {
        Vec3 n = Vec3.atLowerCornerOf(facing().getNormal());
        return level == null ? n : SableCompat.dirToWorld(SableCompat.containing(level, worldPosition), n);
    }

    public void toggleAssembly(Player p) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!Mods.SABLE) {
            p.displayClientMessage(Component.translatable("radar_fcs.rotator.no_sable").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (assembled()) {
            p.displayClientMessage(Component.translatable("radar_fcs.rotator.assembled_info",
                    String.format(Locale.ROOT, "%.1f", angle())).withStyle(ChatFormatting.AQUA), true);
            return;
        }
        try {
            SableRotor.Assembled a = SableRotor.assemble(sl, worldPosition, facing());
            if (a == null) {
                lastError = Component.translatable("radar_fcs.rotator.nothing");
                p.displayClientMessage(lastError.copy().withStyle(ChatFormatting.GOLD), true);
                return;
            }
            subLevel = a.subLevel();
            plotFront = a.plotFront();
            want = target = lastTarget = 0;
            lastError = null;
            reattach(sl);
            setChanged();
            sendData();
            p.displayClientMessage(Component.translatable("radar_fcs.rotator.assembled").withStyle(ChatFormatting.GREEN), true);
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
        lastTarget = target;
        double step = degPerTick(), diff = want - target;
        target += Math.max(-step, Math.min(step, diff));
    }

    /** Шаг физики: цель сервопривода - между углом прошлого и этого тика. */
    private void physicsStep() {
        if (!(level instanceof ServerLevel sl) || subLevel == null || handle == null) return;
        double pt = SableRotor.partialPhysicsTick(sl);
        double goal = lastTarget + (target - lastTarget) * Math.max(0, Math.min(1, pt));
        SableRotor.servo(sl, handle, worldPosition, facing(), subLevel, Math.toRadians(goal));
    }

    public static void physicsStepAll() {
        for (GunRotatorBlockEntity be : List.copyOf(ACTIVE)) {
            if (be.isRemoved() || be.subLevel == null) {
                ACTIVE.remove(be);
                continue;
            }
            try {
                be.physicsStep();
            } catch (RuntimeException e) {
                FcsMod.LOG.warn("[radar_fcs] gun rotator servo failed at {}: {}", be.getBlockPos(), e.toString());
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
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.gun_rotator").withStyle(ChatFormatting.GRAY)));
        if (!assembled()) {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.rotator.not_assembled").withStyle(ChatFormatting.GOLD)));
            if (lastError != null) tooltip.add(Component.literal("    ").append(lastError.copy().withStyle(ChatFormatting.RED)));
        } else {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.rotator.angle",
                    String.format(Locale.ROOT, "%.1f", target), String.format(Locale.ROOT, "%.1f", want)).withStyle(ChatFormatting.AQUA)));
            if (degPerTick() <= 0) tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.controller.no_rotation")
                    .withStyle(ChatFormatting.GOLD)));
        }
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (subLevel != null) tag.putUUID("SubLevel", subLevel);
        if (plotFront != null) tag.put("PlotFront", NbtUtils.writeBlockPos(plotFront));
        tag.putDouble("Want", want);
        tag.putDouble("Target", target);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        subLevel = tag.hasUUID("SubLevel") ? tag.getUUID("SubLevel") : null;
        plotFront = NbtUtils.readBlockPos(tag, "PlotFront").orElse(null);
        want = tag.getDouble("Want");
        target = lastTarget = tag.getDouble("Target");
    }
}
