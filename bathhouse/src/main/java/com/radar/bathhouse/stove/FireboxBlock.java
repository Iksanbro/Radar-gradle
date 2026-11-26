package com.radar.bathhouse.stove;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.radar.bathhouse.block.ChimneyBlock;
import com.radar.bathhouse.registry.ModBlockEntities;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.RecipeType;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** The core of a multiblock stove. Feed it fuel (or redstone, for the heating element). */
public class FireboxBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final Codec<FireboxType> TYPE_CODEC = StringRepresentable.fromEnum(FireboxType::values);
    public static final MapCodec<FireboxBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(),
            TYPE_CODEC.fieldOf("firebox").forGetter(FireboxBlock::type)
    ).apply(instance, FireboxBlock::new));

    private final FireboxType type;

    public FireboxBlock(Properties properties, FireboxType type) {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    public FireboxType type() {
        return type;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
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
        return new StoveBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide ? null : createTickerHelper(blockEntityType, ModBlockEntities.STOVE.get(), StoveBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (type.burnsFuel() && stack.getBurnTime(RecipeType.SMELTING) > 0 && level.getBlockEntity(pos) instanceof StoveBlockEntity stove) {
            if (!level.isClientSide) {
                stove.addFuel(stack, player, hand, false);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof StoveBlockEntity stove) {
            player.displayClientMessage(stove.statusLine(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            return;
        }
        if (!type.burnsFuel()) {
            if (random.nextInt(8) == 0) {
                level.addParticle(ParticleTypes.SMALL_FLAME, pos.getX() + 0.2 + random.nextDouble() * 0.6, pos.getY() + 0.5,
                        pos.getZ() + 0.2 + random.nextDouble() * 0.6, 0, 0.01, 0);
            }
            return;
        }
        Direction front = state.getValue(FACING);
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        if (random.nextDouble() < 0.12) {
            level.playLocalSound(x, pos.getY(), z, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 1.0F, 1.0F, false);
        }
        double side = random.nextDouble() * 0.6 - 0.3;
        double fx = x + front.getStepX() * 0.52 + (front.getAxis() == Direction.Axis.Z ? side : 0);
        double fz = z + front.getStepZ() * 0.52 + (front.getAxis() == Direction.Axis.X ? side : 0);
        level.addParticle(ParticleTypes.FLAME, fx, pos.getY() + 0.15 + random.nextDouble() * 0.3, fz, 0, 0, 0);

        // No chimney in the stack: smoke pours out of the top of the stove into the room.
        BlockPos.MutableBlockPos cursor = pos.mutable();
        StovePartType above = StoveStructure.partType(level.getBlockState(pos.above()));
        if (above != null && above.slot() == StovePartType.Slot.CHAMBER) {
            cursor.move(Direction.UP);
        }
        for (int i = 0; i < StoveStructure.MAX_FLUE; i++) {
            BlockState next = level.getBlockState(cursor.above());
            if (next.getBlock() instanceof ChimneyBlock) {
                return;
            }
            if (!StoveStructure.isFlue(next)) {
                break;
            }
            cursor.move(Direction.UP);
        }
        if (random.nextInt(2) == 0) {
            level.addParticle(random.nextInt(3) == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    cursor.getX() + 0.2 + random.nextDouble() * 0.6, cursor.getY() + 1.05, cursor.getZ() + 0.2 + random.nextDouble() * 0.6,
                    0, 0.04, 0);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(type.translationKey() + ".tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("bathhouse.tooltip.power", Math.round(type.power())).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("bathhouse.tooltip.stove_core").withStyle(ChatFormatting.DARK_GRAY));
    }
}
