package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Контроллер наводки: ставится вплотную к станку CBC (FACING - на станок), вращение подаётся валом сзади.
 * Горизонтальная наводка - под станком (или над подвесным), вертикальная - сбоку. Вычислитель поворачивает станок
 * через контроллер ровно на нужный угол в нужную сторону; скорость поворота - от скорости вала.
 */
public class AimControllerBlock extends DirectionalKineticBlock implements IBE<AimControllerBlockEntity> {
    /** true - вертикальная наводка (наклон ствола), false - горизонтальная (поворот станка). */
    private final boolean pitch;

    public AimControllerBlock(Properties props, boolean pitch) {
        super(props);
        this.pitch = pitch;
    }

    public boolean isPitch() {
        return pitch;
    }

    /** Сторона, с которой контроллер может стоять у станка (от контроллера к станку). */
    public boolean validSide(Direction toMount) {
        return pitch ? toMount.getAxis().isHorizontal() : toMount.getAxis().isVertical();
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        // повернуть к станку рядом: сначала - к блоку, по которому кликнули
        Direction clicked = ctx.getClickedFace().getOpposite();
        if (validSide(clicked) && isMount(level, pos.relative(clicked))) return defaultBlockState().setValue(FACING, clicked);
        for (Direction d : Direction.values()) {
            if (validSide(d) && isMount(level, pos.relative(d))) return defaultBlockState().setValue(FACING, d);
        }
        return defaultBlockState().setValue(FACING, pitch ? ctx.getHorizontalDirection() : Direction.UP);
    }

    private static boolean isMount(LevelReader level, BlockPos p) {
        return CbcMount.of(level.getBlockEntity(p)) != null;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /** Вал входа - только сзади; к станку вала нет (станок поворачивает вычислитель, а не кинетическая сеть). */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING).getOpposite();
    }

    @Override
    public Class<AimControllerBlockEntity> getBlockEntityClass() {
        return AimControllerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AimControllerBlockEntity> getBlockEntityType() {
        return FcsRegistry.AIM_CONTROLLER_BE.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(pitch ? "radar_fcs.tooltip.pitch_controller" : "radar_fcs.tooltip.yaw_controller")
                .withStyle(ChatFormatting.GRAY));
    }
}
