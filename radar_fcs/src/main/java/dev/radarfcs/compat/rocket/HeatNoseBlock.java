package dev.radarfcs.compat.rocket;

import com.example.cbcenhancedshells.rocket.RocketNoseConeBlock;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Нос ракеты с тепловой головкой самонаведения: ставится на стек ракеты CBC Enchanced Shells, ПКМ - типы целей. */
public class HeatNoseBlock extends RocketNoseConeBlock implements EntityBlock {
    private static final MapCodec<HeatNoseBlock> CODEC = simpleCodec(HeatNoseBlock::new);

    public HeatNoseBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HeatNoseBlockEntity(RocketNoses.HEAT_BE.get(), pos, state);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof HeatNoseBlockEntity be) {
            dev.radarfcs.net.RocketNosePayloads.openHeat(sp, be);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Нос снят (пуском площадки или сломан): его настройки - ракете, которая сейчас появится. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof HeatNoseBlockEntity be) {
            RocketGuidance.noteRemoved(level, pos, new NoseData(NoseData.Kind.HEAT, be.categories(), null, null,
                    dev.radarfcs.compat.SableCompat.containingId(level, pos)));
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.heat_seeker_nose").withStyle(ChatFormatting.GRAY));
    }
}
