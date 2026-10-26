package dev.radarfcs.block;

import com.mojang.serialization.MapCodec;
import dev.radarfcs.iff.IffRegistry;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
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
 * Транспондер «свой».
 * - стоит в мире: всё в радиусе iffRadius своё (база);
 * - стоит на корабле Sable / Create Aeronautics: весь корабль свой;
 * - собран в контрапшен Create: весь контрапшен свой.
 * Канал задаётся красителем (ПКМ): вычислитель считает своими только транспондеры своего канала.
 */
public class IffTransponderBlock extends BaseEntityBlock {
    public static final MapCodec<IffTransponderBlock> CODEC = simpleCodec(IffTransponderBlock::new);
    public static final EnumProperty<DyeColor> CHANNEL = EnumProperty.create("channel", DyeColor.class);

    public IffTransponderBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(CHANNEL, DyeColor.WHITE));
    }

    public static int channelOf(BlockState s) {
        return s.hasProperty(CHANNEL) ? s.getValue(CHANNEL).getId() : DyeColor.WHITE.getId();
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

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof DyeItem dye)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(CHANNEL, dye.getDyeColor()), 3);
            IffRegistry.put(level, pos, dye.getDyeColor().getId());
            player.displayClientMessage(Component.translatable("radar_fcs.msg.channel",
                    Component.translatable("color.minecraft." + dye.getDyeColor().getName())), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            DyeColor c = state.getValue(CHANNEL);
            player.displayClientMessage(Component.translatable("radar_fcs.msg.transponder",
                    Component.translatable("color.minecraft." + c.getName())), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.transponder").withStyle(ChatFormatting.GRAY));
    }
}
