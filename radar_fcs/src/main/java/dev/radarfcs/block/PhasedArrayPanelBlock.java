package dev.radarfcs.block;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * Панель фазированной решётки. Панели, приставленные к контроллеру в его плоскости, увеличивают дальность.
 * Новая панель поворачивается как соседняя панель или контроллер.
 */
public class PhasedArrayPanelBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public PhasedArrayPanelBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, arrayFacing(ctx));
    }

    /** Направление по соседнему элементу решётки, иначе - куда смотрит игрок. */
    static Direction arrayFacing(BlockPlaceContext ctx) {
        BlockState against = ctx.getLevel().getBlockState(ctx.getClickedPos().relative(ctx.getClickedFace().getOpposite()));
        if (against.getBlock() instanceof PhasedArrayPanelBlock || against.getBlock() instanceof PhasedArrayControllerBlock) {
            Direction f = against.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (ctx.getClickedFace().getAxis() != f.getAxis()) return f;
        }
        Direction d = ctx.getHorizontalDirection();
        return ctx.getPlayer() != null && ctx.getPlayer().isShiftKeyDown() ? d.getOpposite() : d;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.array_panel").withStyle(ChatFormatting.GRAY));
    }
}
