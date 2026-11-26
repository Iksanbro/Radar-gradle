package com.radar.bathhouse.stove;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.radar.bathhouse.item.LadleItem;
import com.radar.bathhouse.item.WaterPortions;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.fluids.FluidUtil;
import org.jetbrains.annotations.Nullable;

/** Every stove part except the firebox and the chimney. */
public class StovePartBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** Heaters: has stones. Ash pit: full. Boiler: boiling. Water tank: hot. Damper: open. */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    public static final Codec<StovePartType> TYPE_CODEC = StringRepresentable.fromEnum(StovePartType::values);
    public static final MapCodec<StovePartBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(),
            TYPE_CODEC.fieldOf("part").forGetter(StovePartBlock::type)
    ).apply(instance, StovePartBlock::new));
    private static final VoxelShape PILLAR = Block.box(4, 0, 4, 12, 16, 12);

    private final StovePartType type;

    public StovePartBlock(Properties properties, StovePartType type) {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, type == StovePartType.DAMPER));
    }

    public StovePartType type() {
        return type;
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
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return type == StovePartType.DAMPER ? PILLAR : Shapes.block();
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
        return type.hasBlockEntity() ? new StovePartBlockEntity(pos, state) : null;
    }

    @Nullable
    private static StoveBlockEntity core(Level level, BlockPos pos) {
        BlockPos core = StoveStructure.findCore(level, pos);
        return core != null && level.getBlockEntity(core) instanceof StoveBlockEntity stove ? stove : null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        boolean client = level.isClientSide;
        StovePartBlockEntity part = level.getBlockEntity(pos) instanceof StovePartBlockEntity be ? be : null;

        if (type.chamber().hasStones() && part != null) {
            if (StoneQuality.of(stack) > 0) {
                if (!client) {
                    int added = part.addStones(stack, player.isShiftKeyDown() ? stack.getCount() : 1);
                    if (added > 0) {
                        stack.consume(added, player);
                        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.1F);
                    }
                    player.displayClientMessage(stonesLine(part), true);
                }
                return ItemInteractionResult.sidedSuccess(client);
            }
            int grams = WaterPortions.grams(stack);
            if (grams > 0) {
                StoveBlockEntity stove = core(level, pos);
                if (level instanceof ServerLevel serverLevel) {
                    if (stove == null) {
                        player.displayClientMessage(Component.translatable("bathhouse.stove.no_firebox").withStyle(ChatFormatting.RED), true);
                    } else if (stove.throwWater(serverLevel, part, grams, WaterPortions.hot(stack), player)) {
                        WaterPortions.consume(stack, player, hand);
                    }
                }
                return ItemInteractionResult.sidedSuccess(client);
            }
        }

        if (type == StovePartType.WATER_TANK && part != null && stack.getItem() instanceof LadleItem && LadleItem.water(stack) < LadleItem.CAPACITY) {
            if (!client && part.water() != null) {
                int portions = Math.min(LadleItem.CAPACITY - LadleItem.water(stack), part.waterAmount() / LadleItem.MB_PER_PORTION);
                if (portions <= 0) {
                    player.displayClientMessage(Component.translatable("bathhouse.stove.tank_empty").withStyle(ChatFormatting.YELLOW), true);
                } else {
                    boolean hot = part.hotWater() && (LadleItem.water(stack) == 0 || LadleItem.isHot(stack));
                    part.water().drain(portions * LadleItem.MB_PER_PORTION, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    LadleItem.setWater(stack, LadleItem.water(stack) + portions);
                    LadleItem.setHot(stack, hot);
                    level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.7F, hot ? 0.8F : 1.3F);
                }
            }
            return ItemInteractionResult.sidedSuccess(client);
        }

        if ((type == StovePartType.BOILER || type == StovePartType.WATER_TANK) && FluidUtil.getFluidHandler(stack).isPresent()) {
            if (!client) {
                FluidUtil.interactWithFluidHandler(player, hand, level, pos, hit.getDirection());
            }
            return ItemInteractionResult.sidedSuccess(client);
        }

        if (type == StovePartType.FUEL_TUNNEL && stack.getBurnTime(RecipeType.SMELTING) > 0) {
            StoveBlockEntity stove = core(level, pos);
            if (stove != null && stove.firebox().burnsFuel()) {
                if (!client) {
                    stove.addFuel(stack, player, hand, true);
                }
                return ItemInteractionResult.sidedSuccess(client);
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        boolean client = level.isClientSide;
        if (type == StovePartType.DAMPER) {
            boolean open = !state.getValue(ACTIVE);
            level.setBlock(pos, state.setValue(ACTIVE, open), Block.UPDATE_ALL);
            level.playSound(player, pos, open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 1.2F);
            if (!client) {
                player.displayClientMessage(Component.translatable(open ? "bathhouse.stove.damper_open" : "bathhouse.stove.damper_closed")
                        .withStyle(open ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
            }
            return InteractionResult.sidedSuccess(client);
        }
        if (client) {
            return InteractionResult.SUCCESS;
        }
        StovePartBlockEntity part = level.getBlockEntity(pos) instanceof StovePartBlockEntity be ? be : null;
        if (type == StovePartType.ASH_PIT && part != null && part.ash() > 0) {
            int ash = part.takeAsh();
            ItemStack meal = new ItemStack(Items.BONE_MEAL, ash);
            if (!player.addItem(meal)) {
                player.drop(meal, false);
            }
            level.playSound(null, pos, SoundEvents.SAND_BREAK, SoundSource.BLOCKS, 0.8F, 0.8F);
            player.displayClientMessage(Component.translatable("bathhouse.stove.ash_taken", ash).withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.SUCCESS;
        }
        if (type.chamber().hasStones() && part != null && player.isShiftKeyDown()) {
            ItemStack stone = part.removeStone();
            if (!stone.isEmpty() && !player.addItem(stone)) {
                player.drop(stone, false);
            }
            player.displayClientMessage(stonesLine(part), true);
            return InteractionResult.SUCCESS;
        }
        StoveBlockEntity stove = core(level, pos);
        if (part != null && type.chamber().hasStones()) {
            player.displayClientMessage(stonesLine(part), true);
        } else if (stove != null) {
            player.displayClientMessage(stove.statusLine(), true);
        } else {
            player.displayClientMessage(Component.translatable("bathhouse.stove.no_firebox").withStyle(ChatFormatting.RED), true);
        }
        return InteractionResult.SUCCESS;
    }

    private Component stonesLine(StovePartBlockEntity part) {
        return Component.translatable(type.translationKey()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("bathhouse.stove.stones", part.stoneCount(), part.stoneCapacity(),
                        Component.translatable(StoneQuality.translationKey(part.stoneQuality()))).withStyle(ChatFormatting.WHITE));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof StovePartBlockEntity part) {
            part.dropContents(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE)) {
            return;
        }
        if (type == StovePartType.BOILER || (type == StovePartType.WATER_TANK && random.nextInt(4) == 0)) {
            level.addParticle(ParticleTypes.WHITE_SMOKE, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 1.02,
                    pos.getZ() + 0.3 + random.nextDouble() * 0.4, 0, 0.05, 0);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(type.translationKey() + ".tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("bathhouse.tooltip.slot." + type.slot().name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
