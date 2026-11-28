package dev.radarfcs.compat.rocket;

import com.example.cbcenhancedshells.rocket.RocketNoseConeBlock;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Программируемый нос ракеты: в радарной сети (как камера), ракета - на выбранную цель сети; ПКМ - режим пуска.
 */
public class NetNoseBlock extends RocketNoseConeBlock implements EntityBlock {
    private static final MapCodec<NetNoseBlock> CODEC = simpleCodec(NetNoseBlock::new);

    public NetNoseBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetNoseBlockEntity(RocketNoses.NET_BE.get(), pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != RocketNoses.NET_BE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<NetNoseBlockEntity>) NetNoseBlockEntity::serverTick;
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof NetNoseBlockEntity be) {
            dev.radarfcs.net.RocketNosePayloads.openNet(sp, be);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos from, boolean moving) {
        super.neighborChanged(state, level, pos, block, from, moving);
        if (level instanceof ServerLevel sl && level.getBlockEntity(pos) instanceof NetNoseBlockEntity be) {
            be.redstone(sl, level.hasNeighborSignal(pos));
        }
    }

    /** Нос снят (пуском площадки или сломан): сеть и захваченная цель - ракете, которая сейчас появится. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof NetNoseBlockEntity be) {
            RocketGuidance.noteRemoved(level, pos, be.noseData());
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.network_nose").withStyle(ChatFormatting.GRAY));
    }
}
