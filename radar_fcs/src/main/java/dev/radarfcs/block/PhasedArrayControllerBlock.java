package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.radarfcs.radar.RadarInfo;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Контроллер фазированной решётки: смотрит в сторону FACING, вал подключается сзади.
 * Электронное сканирование сектора без вращения антенны; скорость вала задаёт частоту обзора.
 */
public class PhasedArrayControllerBlock extends HorizontalKineticBlock implements IBE<PhasedArrayBlockEntity> {
    public PhasedArrayControllerBlock(Properties props) {
        super(props);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(HORIZONTAL_FACING, PhasedArrayPanelBlock.arrayFacing(ctx));
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(HORIZONTAL_FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(HORIZONTAL_FACING).getOpposite();
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        DyeColor dye = RadarInfo.dye(stack);
        if (dye == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) withBlockEntityDo(level, pos, be -> be.setChannel(dye, player));
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<PhasedArrayBlockEntity> getBlockEntityClass() {
        return PhasedArrayBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PhasedArrayBlockEntity> getBlockEntityType() {
        return FcsRegistry.PHASED_ARRAY_BE.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.array_controller").withStyle(ChatFormatting.GRAY));
    }
}
