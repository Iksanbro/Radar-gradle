package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
 * Вращатель орудия: FACING - сторона, к которой крепится поворачиваемая часть постройки; вращение подаётся валом
 * сзади. ПКМ пустой рукой - собрать постройку перед ним в физический под-уровень Sable на шарнире (по правилам
 * Create: суперклей, «липкие» блоки). Угол задаёт вычислитель через орудийный линк и прицел; скорость поворота - от вала.
 */
public class GunRotatorBlock extends DirectionalKineticBlock implements IBE<GunRotatorBlockEntity> {
    public GunRotatorBlock(Properties props) {
        super(props);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getClickedFace());
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /** Вал входа - только сзади. */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING).getOpposite();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof GunRotatorBlockEntity be) be.toggleAssembly(player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<GunRotatorBlockEntity> getBlockEntityClass() {
        return GunRotatorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GunRotatorBlockEntity> getBlockEntityType() {
        return FcsRegistry.GUN_ROTATOR_BE.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.gun_rotator").withStyle(ChatFormatting.GRAY));
    }
}
