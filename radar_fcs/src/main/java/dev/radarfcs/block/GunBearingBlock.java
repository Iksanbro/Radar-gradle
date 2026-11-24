package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
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
 * Подшипник орудия (свой, не копия Aeronautics): FACING - сторона, к которой крепится поворачиваемая часть постройки;
 * вращение подаётся валом сзади. Shift+ПКМ пустой рукой - собрать постройку перед ним в физический под-уровень Sable
 * на шарнире (по правилам Create: суперклей, «липкие» блоки). Как поворотный подшипник Aeronautics: поворачивает
 * шестерня рядом (малая вплотную сбоку или большая по диагонали, ось - как у подшипника; обычно её крутит привод
 * орудия), а вал проходит насквозь - сзади в первый блок поворотной части, независимо от поворота.
 */
public class GunBearingBlock extends DirectionalKineticBlock implements IBE<GunBearingBlockEntity> {
    public GunBearingBlock(Properties props) {
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
        // вал насквозь: сзади и спереди (после сборки передний конец - первый блок поворотной части)
        return face.getAxis() == state.getValue(FACING).getAxis();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        // сборка - Shift+ПКМ: простой ПКМ не должен ничего собирать (им пользуются и для другого - ключ, гоглы, клей)
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof GunBearingBlockEntity be) be.toggleAssembly(player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<GunBearingBlockEntity> getBlockEntityClass() {
        return GunBearingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GunBearingBlockEntity> getBlockEntityType() {
        return GunBearings.BE.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.gun_bearing").withStyle(ChatFormatting.GRAY));
    }
}
