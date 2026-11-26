package com.radar.bathhouse.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/** Water you can throw on the stones: a ladle portion, a bottle or a bucket. */
public final class WaterPortions {
    public static final int LADLE_PORTION = 500;
    public static final int BOTTLE_PORTION = 250;
    public static final int BUCKET_PORTION = 1000;

    private WaterPortions() {
    }

    /** Grams of water in one throw from this stack, or 0. */
    public static int grams(ItemStack stack) {
        if (stack.getItem() instanceof LadleItem) {
            return LadleItem.water(stack) > 0 ? LADLE_PORTION : 0;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            return BUCKET_PORTION;
        }
        return isWaterBottle(stack) ? BOTTLE_PORTION : 0;
    }

    /** Hot water from the stove's tank makes lighter steam and chills the stones less. */
    public static boolean hot(ItemStack stack) {
        return stack.getItem() instanceof LadleItem && LadleItem.isHot(stack);
    }

    public static boolean isWaterBottle(ItemStack stack) {
        if (!stack.is(Items.POTION)) {
            return false;
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(Potions.WATER);
    }

    public static void consume(ItemStack stack, Player player, InteractionHand hand) {
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
}
