package dev.radarfcs.block;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Транспондер «свой». ПКМ - экран: код (цифры), режим выделения (сублевел, на котором стоит, или радиус), радиус.
 * Вычислитель считает транспондер своим, если его правило (белый или чёрный список кодов) принимает код.
 * - режим «сублевел»: свой - корабль Sable / Create Aeronautics или контрапшен Create, на котором стоит транспондер;
 * - режим «радиус»: своё - всё в радиусе вокруг транспондера (на корабле корабль-носитель свой и в этом режиме).
 * Краситель меняет только цвет.
 */
public class IffTransponderBlock extends BaseEntityBlock {
    public static final MapCodec<IffTransponderBlock> CODEC = simpleCodec(IffTransponderBlock::new);
    public static final EnumProperty<DyeColor> CHANNEL = EnumProperty.create("channel", DyeColor.class);

    public IffTransponderBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(CHANNEL, DyeColor.WHITE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CHANNEL);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new IffTransponderBlockEntity(pos, state);
    }

    @org.jetbrains.annotations.Nullable
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, dev.radarfcs.registry.FcsRegistry.IFF_BE.get(), IffTransponderBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof DyeItem dye)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) level.setBlock(pos, state.setValue(CHANNEL, dye.getDyeColor()), 3);       // только цвет
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof IffTransponderBlockEntity be) {
            if (be.canEdit(sp)) dev.radarfcs.net.TransponderPayloads.open(sp, be);
            else sp.displayClientMessage(Component.translatable("radar_fcs.msg.transponder_locked").withStyle(ChatFormatting.RED), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @org.jetbrains.annotations.Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player p && level.getBlockEntity(pos) instanceof IffTransponderBlockEntity be) {
            be.setOwner(p);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.transponder").withStyle(ChatFormatting.GRAY));
    }
}
