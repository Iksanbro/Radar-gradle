package com.radar.bathhouse.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.radar.bathhouse.item.LadleItem;
import com.radar.bathhouse.registry.ModBlockEntities;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
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
import net.minecraft.core.component.DataComponents;
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
import net.neoforged.neoforge.fluids.FluidUtil;
import org.jetbrains.annotations.Nullable;

/** Heaters, stoves and the steam generator. Behaviour is driven by {@link ApplianceType}. */
public class ApplianceBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public static final MapCodec<ApplianceBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(),
            ApplianceType.CODEC.fieldOf("appliance").forGetter(ApplianceBlock::type)
    ).apply(instance, ApplianceBlock::new));

    /** Grams of water in one ladle portion, a water bottle and a water bucket. */
    public static final int LADLE_PORTION = 500;
    public static final int BOTTLE_PORTION = 250;
    public static final int BUCKET_PORTION = 1000;

    private final ApplianceType type;

    public ApplianceBlock(Properties properties, ApplianceType type) {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    public ApplianceType type() {
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
        return new ApplianceBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide ? null
                : createTickerHelper(blockEntityType, ModBlockEntities.APPLIANCE.get(), ApplianceBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ApplianceBlockEntity appliance)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Steam generator: fill the boiler with buckets (or anything else holding water).
        if (type == ApplianceType.STEAM_GENERATOR) {
            if (FluidUtil.getFluidHandler(stack).isPresent()) {
                if (!level.isClientSide) {
                    FluidUtil.interactWithFluidHandler(player, hand, level, pos, hit.getDirection());
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Water on the stones: the heart of the bath.
        if (type.hasStones()) {
            int grams = waterPortion(stack);
            if (grams > 0) {
                if (level instanceof ServerLevel serverLevel && appliance.throwWater(serverLevel, grams, player)) {
                    consumeWater(stack, player, hand);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }

        // Fuel into the firebox.
        if (type.isFueled() && stack.getBurnTime(RecipeType.SMELTING) > 0) {
            if (!level.isClientSide) {
                appliance.addFuel(stack, player, hand);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ApplianceBlockEntity appliance) {
            player.displayClientMessage(appliance.statusLine(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static int waterPortion(ItemStack stack) {
        if (stack.getItem() instanceof LadleItem) {
            return LadleItem.water(stack) > 0 ? LADLE_PORTION : 0;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            return BUCKET_PORTION;
        }
        if (stack.is(Items.POTION)) {
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.is(Potions.WATER)) {
                return BOTTLE_PORTION;
            }
        }
        return 0;
    }

    private static void consumeWater(ItemStack stack, Player player, InteractionHand hand) {
        if (stack.getItem() instanceof LadleItem) {
            if (!player.getAbilities().instabuild) {
                LadleItem.setWater(stack, LadleItem.water(stack) - 1);
            }
        } else if (stack.is(Items.WATER_BUCKET)) {
            player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(Items.BUCKET)));
        } else if (stack.is(Items.POTION)) {
            player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            return;
        }
        Direction front = state.getValue(FACING);
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;

        if (type == ApplianceType.STEAM_GENERATOR) {
            if (random.nextInt(3) == 0) {
                level.addParticle(ParticleTypes.WHITE_SMOKE, x + (random.nextDouble() - 0.5) * 0.3, y + 1.05,
                        z + (random.nextDouble() - 0.5) * 0.3, 0.0, 0.06, 0.0);
            }
            return;
        }
        if (type == ApplianceType.WARM_LOUNGER) {
            if (random.nextInt(10) == 0) {
                level.addParticle(ParticleTypes.WHITE_SMOKE, x + (random.nextDouble() - 0.5) * 0.8, y + 1.02,
                        z + (random.nextDouble() - 0.5) * 0.8, 0.0, 0.01, 0.0);
            }
            return;
        }
        if (type == ApplianceType.ELECTRIC_HEATER) {
            if (random.nextInt(8) == 0) {
                level.addParticle(ParticleTypes.SMALL_FLAME, x + (random.nextDouble() - 0.5) * 0.6, y + 1.02,
                        z + (random.nextDouble() - 0.5) * 0.6, 0.0, 0.01, 0.0);
            }
            return;
        }

        // Fire behind the firebox door.
        if (random.nextDouble() < 0.12) {
            level.playLocalSound(x, y, z, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 1.0F, 1.0F, false);
        }
        double side = random.nextDouble() * 0.6 - 0.3;
        double fx = x + front.getStepX() * 0.52 + (front.getAxis() == Direction.Axis.Z ? side : 0);
        double fz = z + front.getStepZ() * 0.52 + (front.getAxis() == Direction.Axis.X ? side : 0);
        double fy = y + 0.15 + random.nextDouble() * 0.3;
        level.addParticle(ParticleTypes.FLAME, fx, fy, fz, 0.0, 0.0, 0.0);

        // Smoke: up the chimney (drawn by the chimney itself), out of the outer firebox, or into the room.
        if (ChimneyBlock.isChimney(level.getBlockState(pos.above()))) {
            return;
        }
        if (type.outerFirebox()) {
            level.addParticle(ParticleTypes.SMOKE, fx, y + 0.7, fz, 0.0, 0.03, 0.0);
        } else if (random.nextInt(2) == 0) {
            level.addParticle(random.nextInt(3) == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    x + (random.nextDouble() - 0.5) * 0.6, y + 1.05, z + (random.nextDouble() - 0.5) * 0.6, 0.0, 0.04, 0.0);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String base = "block.bathhouse." + type.getSerializedName() + ".tooltip";
        tooltip.add(Component.translatable(base).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("bathhouse.tooltip.power", Math.round(type.power())).withStyle(ChatFormatting.DARK_GRAY));
    }
}
