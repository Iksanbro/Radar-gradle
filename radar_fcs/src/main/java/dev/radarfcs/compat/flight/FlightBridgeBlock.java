package dev.radarfcs.compat.flight;

import ace.flight.block.FlightControlComputerBlockEntity;
import ace.flight.block.ForwardIndicatorLinkTarget;
import ace.flight.item.FlightControlToolGunItem;
import com.mojang.serialization.MapCodec;
import dev.radarfcs.block.AutopilotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Мост Flight Control. ПКМ - экран настроек; инструментальный пистолет Flight Control: выбрать индикатор направления
 * вперёд и нажать на мост - «вперёд» по индикатору; выбрать мост и нажать на компьютер управления полётом - связь.
 * Лицевая сторона (без индикатора - «вперёд» корабля) смотрит от игрока при установке.
 */
public class FlightBridgeBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<FlightBridgeBlock> CODEC = simpleCodec(FlightBridgeBlock::new);

    public FlightBridgeBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FlightBridgeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != FlightBridge.BE.get()) return null;
        return (l, p, s, be) -> AutopilotBlockEntity.serverTick(l, p, s, (AutopilotBlockEntity) be);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player p && level.getBlockEntity(pos) instanceof AutopilotBlockEntity be) be.setOwner(p);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof FlightControlToolGunItem) {
            // индикатор выбран - связать; иначе работа пистолета (выбор моста для связи с компьютером)
            return level.getBlockEntity(pos) instanceof ForwardIndicatorLinkTarget t
                    && ForwardIndicatorLinkTarget.tryHandleToolGunLink(stack, level, pos, t, player)
                    ? ItemInteractionResult.CONSUME : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof AutopilotBlockEntity be) {
            dev.radarfcs.net.AutopilotPayloads.open(sp, be);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof FlightBridgeBlockEntity be) {
            be.unlinkForwardIndicator();
            BlockPos c = be.getLinkedComputerPos();
            if (c != null && level.isLoaded(c) && level.getBlockEntity(c) instanceof FlightControlComputerBlockEntity cc) {
                cc.removeControllerAt(pos);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext ctx,
                                java.util.List<net.minecraft.network.chat.Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(net.minecraft.network.chat.Component.translatable("radar_fcs.tooltip.flight_bridge")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
