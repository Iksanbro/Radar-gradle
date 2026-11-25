package com.radar.bathhouse.item;

import com.radar.bathhouse.registry.ModComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Wooden ladle. Scoop water from any water source or cauldron, then throw it on hot stones for steam. */
public class LadleItem extends Item {
    public static final int CAPACITY = 8;

    public LadleItem(Properties properties) {
        super(properties);
    }

    public static int water(ItemStack stack) {
        return stack.getOrDefault(ModComponents.LADLE_WATER.get(), 0);
    }

    public static void setWater(ItemStack stack, int portions) {
        stack.set(ModComponents.LADLE_WATER.get(), Mth.clamp(portions, 0, CAPACITY));
    }

    public static ItemStack filled(Item ladle) {
        ItemStack stack = new ItemStack(ladle);
        setWater(stack, CAPACITY);
        return stack;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (water(stack) >= CAPACITY) {
            return InteractionResultHolder.pass(stack);
        }
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(stack);
        }
        BlockPos pos = hit.getBlockPos();
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            setWater(stack, CAPACITY);
            level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 0.8F, 1.3F);
            level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        ItemStack stack = context.getItemInHand();
        if (state.is(Blocks.WATER_CAULDRON) && water(stack) < CAPACITY) {
            if (!level.isClientSide) {
                LayeredCauldronBlock.lowerFillLevel(state, level, pos);
                setWater(stack, water(stack) + 3);
                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 0.8F, 1.3F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return water(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * water(stack) / CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x3F76E4;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.bathhouse.ladle.water", water(stack), CAPACITY).withStyle(ChatFormatting.BLUE));
        tooltip.add(Component.translatable("item.bathhouse.ladle.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
