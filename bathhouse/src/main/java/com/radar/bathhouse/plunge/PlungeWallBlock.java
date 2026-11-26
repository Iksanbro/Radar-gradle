package com.radar.bathhouse.plunge;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** A wall or floor block of a plunge basin. */
public class PlungeWallBlock extends Block {
    private final PlungeMaterial material;

    public PlungeWallBlock(Properties properties, PlungeMaterial material) {
        super(properties);
        this.material = material;
    }

    public PlungeMaterial material() {
        return material;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(p -> new PlungeWallBlock(p, material));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("block.bathhouse." + material.blockId() + ".tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("bathhouse.tooltip.plunge_part").withStyle(ChatFormatting.DARK_GRAY));
    }
}
