package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.radarfcs.compat.CbcMount;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Контроллер наводки: потребитель вращения. Скорость вала задаёт, насколько быстро вычислитель может поворачивать
 * станок по своей оси - как от валов CBC: 0.0375 град/тик на об/мин (256 об/мин = 9.6 град/тик).
 */
public class AimControllerBlockEntity extends KineticBlockEntity {

    public AimControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public boolean isPitch() {
        return getBlockState().getBlock() instanceof AimControllerBlock b && b.isPitch();
    }

    public Direction toMount() {
        return getBlockState().getValue(DirectionalKineticBlock.FACING);
    }

    /** Станок, к которому приставлен контроллер (с правильной стороны), или null. */
    @Nullable
    public CbcMount mount() {
        if (level == null || !(getBlockState().getBlock() instanceof AimControllerBlock b) || !b.validSide(toMount())) return null;
        BlockPos p = worldPosition.relative(toMount());
        return level.isLoaded(p) ? CbcMount.of(level.getBlockEntity(p)) : null;
    }

    /** Наибольшая скорость поворота станка от этого контроллера, градусов/тик (0 - нет вращения или перегрузка). */
    public double degPerTick() {
        if (isOverStressed()) return 0;
        return Math.abs(convertToAngular(getSpeed())) * 0.125;
    }

    /** Контроллер нужной оси у станка в mountPos (или null). */
    @Nullable
    public static AimControllerBlockEntity find(Level level, BlockPos mountPos, boolean pitch) {
        for (Direction d : Direction.values()) {
            BlockPos p = mountPos.relative(d);
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof AimControllerBlockEntity c && c.isPitch() == pitch && c.toMount() == d.getOpposite()
                    && c.getBlockState().getBlock() instanceof AimControllerBlock b && b.validSide(c.toMount())) {
                return c;
            }
        }
        return null;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable(
                isPitch() ? "block.radar_fcs.pitch_controller" : "block.radar_fcs.yaw_controller").withStyle(ChatFormatting.GRAY)));
        if (mount() == null) {
            tooltip.add(Component.literal("    ").append(Component.translatable(isPitch()
                    ? "radar_fcs.controller.no_mount_pitch" : "radar_fcs.controller.no_mount_yaw").withStyle(ChatFormatting.RED)));
        } else if (degPerTick() <= 0) {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.controller.no_rotation")
                    .withStyle(ChatFormatting.GOLD)));
        } else {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.controller.rate",
                    String.format(Locale.ROOT, "%.1f", degPerTick() * 20)).withStyle(ChatFormatting.AQUA)));
        }
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }
}
