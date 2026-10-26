package dev.radarfcs.block;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Элемент антенны радарного подшипника (отражатель). Блоки антенны сцепляются друг с другом при сборке
 * контрапшена Create без суперклея; поршни их не тянут (это не липкие блоки).
 */
public class AntennaBlock extends Block {
    public AntennaBlock(Properties props) {
        super(props);
    }

    @Override
    public boolean canStickTo(BlockState state, BlockState other) {
        return other.getBlock() instanceof AntennaBlock || super.canStickTo(state, other);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.dish").withStyle(ChatFormatting.GRAY));
    }
}
