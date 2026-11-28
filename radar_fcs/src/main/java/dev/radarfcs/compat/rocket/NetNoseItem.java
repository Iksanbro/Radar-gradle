package dev.radarfcs.compat.rocket;

import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.item.CameraItem;
import dev.radarfcs.item.RadarLinkItem;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Программируемый нос в руке - как камера: ПКМ по передатчику радарной сети (или по камере, носу этой сети) настраивает
 * предмет на сеть, поставленный нос сразу в ней - без своего передатчика. Shift+ПКМ в воздух - сброс.
 */
public class NetNoseItem extends BlockItem {
    public NetNoseItem(Block block, Properties props) {
        super(block, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        UUID net = CameraItem.tunableNetwork(level, ctx.getClickedPos());
        if (net != null) {
            RadarLinkItem.tune(ctx.getItemInHand(), net);
            if (ctx.getPlayer() != null && !level.isClientSide) ctx.getPlayer().displayClientMessage(Component.translatable(
                    "radar_fcs.rocket_nose.tuned", RadarLinkBlockEntity.shortName(net)).withStyle(ChatFormatting.AQUA), true);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        InteractionResult r = super.useOn(ctx);
        UUID mine = RadarLinkItem.network(ctx.getItemInHand());
        if (r.consumesAction() && mine != null && ctx.getPlayer() != null && !level.isClientSide) {
            ctx.getPlayer().displayClientMessage(Component.translatable("radar_fcs.rocket_nose.placed_in_network",
                    RadarLinkBlockEntity.shortName(mine)).withStyle(ChatFormatting.GREEN), true);
        }
        return r;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && RadarLinkItem.network(stack) != null) {
            RadarLinkItem.tune(stack, null);
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("radar_fcs.link.cleared").withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return super.use(level, player, hand);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return RadarLinkItem.network(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, tooltip, flag);
        UUID net = RadarLinkItem.network(stack);
        tooltip.add(net != null
                ? Component.translatable("radar_fcs.camera.tooltip_tuned", RadarLinkBlockEntity.shortName(net)).withStyle(ChatFormatting.AQUA)
                : Component.translatable("radar_fcs.rocket_nose.tooltip_untuned").withStyle(ChatFormatting.DARK_GRAY));
    }
}
