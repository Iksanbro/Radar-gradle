package com.radar.bathhouse.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.radar.bathhouse.item.LadleItem;
import com.radar.bathhouse.registry.ModBlockEntities;
import com.radar.bathhouse.registry.ModItems;
import com.radar.bathhouse.session.PlungeTracker;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** A plunge tub you climb into. Content level 2 reaches the chest of someone on the inner bench. */
public class PlungeBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 0, PlungeType.MAX_LEVEL);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    /** Warm enough to steam (drives client particles). */
    public static final BooleanProperty WARM = BooleanProperty.create("warm");

    public static final MapCodec<PlungeBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(),
            PlungeType.CODEC.fieldOf("plunge").forGetter(PlungeBlock::type)
    ).apply(instance, PlungeBlock::new));

    private static final VoxelShape INSIDE = Block.box(1, 3, 1, 15, 16, 15);
    private static final VoxelShape SHAPE = Shapes.join(Shapes.block(), INSIDE, BooleanOp.ONLY_FIRST);

    private final PlungeType type;

    public PlungeBlock(Properties properties, PlungeType type) {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LEVEL, 0)
                .setValue(LIT, false).setValue(WARM, false));
    }

    public PlungeType type() {
        return type;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEVEL, LIT, WARM);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return INSIDE;
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
        return new PlungeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide ? null
                : createTickerHelper(blockEntityType, ModBlockEntities.PLUNGE.get(), PlungeBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PlungeBlockEntity plunge)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        int contentLevel = state.getValue(LEVEL);
        boolean client = level.isClientSide;

        if (type.medium() == PlungeType.Medium.WATER) {
            if (stack.is(Items.WATER_BUCKET) && contentLevel < PlungeType.MAX_LEVEL) {
                if (!client) {
                    plunge.addContent(PlungeType.MAX_LEVEL - contentLevel);
                    player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(Items.BUCKET)));
                    level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(client);
            }
            if (stack.is(Items.BUCKET) && contentLevel == PlungeType.MAX_LEVEL) {
                if (!client) {
                    plunge.removeContent(PlungeType.MAX_LEVEL);
                    player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(Items.WATER_BUCKET)));
                    level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(client);
            }
            if (isWaterBottle(stack) && contentLevel < PlungeType.MAX_LEVEL) {
                if (!client) {
                    plunge.addContent(1);
                    player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
                    level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(client);
            }
            // Scoop with the ladle: the way to bring water down to chest level.
            if (stack.getItem() instanceof LadleItem && contentLevel > 0 && LadleItem.water(stack) < LadleItem.CAPACITY) {
                if (!client) {
                    plunge.removeContent(1);
                    LadleItem.setWater(stack, LadleItem.water(stack) + 1);
                    level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.7F, 1.4F);
                }
                return ItemInteractionResult.sidedSuccess(client);
            }
            double chill = chill(stack);
            if (chill > 0 && contentLevel > 0) {
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
        } else if (stack.is(ModItems.SAWDUST.get()) && contentLevel < PlungeType.MAX_LEVEL) {
            if (!client) {
                plunge.addContent(1);
                stack.consume(1, player);
                level.playSound(null, pos, SoundEvents.SAND_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
            }
            return ItemInteractionResult.sidedSuccess(client);
        }

        if (type.heated() && stack.is(ItemTags.SMALL_FLOWERS) && contentLevel > 0 && !plunge.hasHerbs()) {
            if (!client) {
                plunge.setHerbs(true);
                stack.consume(1, player);
                level.playSound(null, pos, SoundEvents.AZALEA_LEAVES_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                player.displayClientMessage(Component.translatable("bathhouse.plunge.herbs_added").withStyle(ChatFormatting.GREEN), true);
            }
            return ItemInteractionResult.sidedSuccess(client);
        }

        if (type.heated() && stack.getBurnTime(RecipeType.SMELTING) > 0) {
            if (!client) {
                plunge.addFuel(stack, player, hand);
            }
            return ItemInteractionResult.sidedSuccess(client);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof PlungeBlockEntity plunge) {
            // Sneak with an empty hand to rake out a handful of shavings.
            if (type.medium() == PlungeType.Medium.SAWDUST && player.isShiftKeyDown() && state.getValue(LEVEL) > 0) {
                if (!level.isClientSide) {
                    plunge.removeContent(1);
                    if (!player.addItem(new ItemStack(ModItems.SAWDUST.get()))) {
                        player.drop(new ItemStack(ModItems.SAWDUST.get()), false);
                    }
                    level.playSound(null, pos, SoundEvents.SAND_BREAK, SoundSource.BLOCKS, 0.8F, 1.2F);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide) {
                player.displayClientMessage(plunge.statusLine(), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean isWaterBottle(ItemStack stack) {
        if (!stack.is(Items.POTION)) {
            return false;
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(Potions.WATER);
    }

    /** Degrees of cooling from tossing the item into the water. */
    private static double chill(ItemStack stack) {
        if (stack.is(Items.BLUE_ICE)) {
            return 20.0;
        }
        if (stack.is(Items.PACKED_ICE)) {
            return 12.0;
        }
        if (stack.is(Items.ICE) || stack.is(Items.SNOW_BLOCK) || stack.is(Items.POWDER_SNOW_BUCKET)) {
            return 6.0;
        }
        if (stack.is(Items.SNOWBALL)) {
            return 1.5;
        }
        return 0.0;
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
        if (state.getValue(LEVEL) > 0) {
            // Water and shavings soften the landing, but jumping in is against the rules.
            if (fallDistance > 1.0F && entity instanceof ServerPlayer player) {
                PlungeTracker.markAbrupt(player);
            }
            if (fallDistance > 0.5F) {
                level.playSound(null, pos, type.medium() == PlungeType.Medium.WATER ? SoundEvents.GENERIC_SPLASH : SoundEvents.SAND_FALL,
                        SoundSource.BLOCKS, 0.8F, 1.0F);
            }
            entity.resetFallDistance();
            return;
        }
        super.fallOn(level, state, pos, entity, fallDistance);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        if (state.getValue(LIT)) {
            Direction front = state.getValue(FACING);
            double fx = x + front.getStepX() * 0.52;
            double fz = z + front.getStepZ() * 0.52;
            level.addParticle(ParticleTypes.SMALL_FLAME, fx, pos.getY() + 0.25, fz, 0.0, 0.0, 0.0);
            if (random.nextInt(3) == 0) {
                level.addParticle(ParticleTypes.SMOKE, fx, pos.getY() + 0.5, fz, 0.0, 0.02, 0.0);
            }
        }
        if (state.getValue(WARM) && state.getValue(LEVEL) > 0 && random.nextInt(2) == 0) {
            double surface = pos.getY() + 0.25 + state.getValue(LEVEL) * 0.25;
            level.addParticle(ParticleTypes.WHITE_SMOKE, x + (random.nextDouble() - 0.5) * 0.7, surface,
                    z + (random.nextDouble() - 0.5) * 0.7, 0.0, 0.03, 0.0);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(type.translationKey() + ".tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("bathhouse.tooltip.plunge_range", type.minTemperature(), type.maxTemperature())
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
