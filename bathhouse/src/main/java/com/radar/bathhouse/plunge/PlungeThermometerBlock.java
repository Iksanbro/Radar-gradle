package com.radar.bathhouse.plunge;

import com.mojang.serialization.MapCodec;
import com.radar.bathhouse.registry.ModBlockEntities;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * A wall stave with a thermometer: the controller of a plunge. Place it in the basin wall at rim height; it finds the
 * basin, keeps its temperature and lets the stoves and chillers work. Toss ice or snow at it to chill the water,
 * flowers to steep herbs.
 */
public class PlungeThermometerBlock extends BaseEntityBlock {
    public static final MapCodec<PlungeThermometerBlock> CODEC = simpleCodec(PlungeThermometerBlock::new);
    /** Points into the basin. */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public PlungeThermometerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlungeControllerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.PLUNGE.get(), PlungeControllerBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PlungeControllerBlockEntity plunge)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        boolean client = level.isClientSide;
        double chill = chill(stack);
        if (chill > 0) {
            if (!client) {
                plunge.chill(chill);
                if (stack.is(Items.POWDER_SNOW_BUCKET)) {
                    player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(Items.BUCKET)));
                } else {
                    stack.consume(1, player);
                }
                level.playSound(null, pos, SoundEvents.GLASS_PLACE, SoundSource.BLOCKS, 0.6F, 1.6F);
                player.displayClientMessage(plunge.statusLine(), true);
            }
            return ItemInteractionResult.sidedSuccess(client);
        }
        if (stack.is(ItemTags.SMALL_FLOWERS) && !plunge.hasHerbs()) {
            if (!client) {
                plunge.setHerbs(true);
                stack.consume(1, player);
                level.playSound(null, pos, SoundEvents.AZALEA_LEAVES_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                player.displayClientMessage(Component.translatable("bathhouse.plunge.herbs_added").withStyle(ChatFormatting.GREEN), true);
            }
            return ItemInteractionResult.sidedSuccess(client);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof PlungeControllerBlockEntity plunge) {
            plunge.sendReport(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Cooling energy of what is tossed in, kJ. */
    private static double chill(ItemStack stack) {
        if (stack.is(Items.BLUE_ICE)) {
            return 480.0;
        }
        if (stack.is(Items.PACKED_ICE)) {
            return 240.0;
        }
        if (stack.is(Items.ICE) || stack.is(Items.SNOW_BLOCK) || stack.is(Items.POWDER_SNOW_BUCKET)) {
            return 120.0;
        }
        if (stack.is(Items.SNOWBALL)) {
            return 30.0;
        }
        return 0.0;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            PlungeRegistry.unregister(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("block.bathhouse.plunge_thermometer.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.bathhouse.plunge_thermometer.tooltip2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
