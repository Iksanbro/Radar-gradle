package dev.radarfcs.item;

import dev.radarfcs.block.CameraBlockEntity;
import dev.radarfcs.block.GunLinkBlock;
import dev.radarfcs.block.RadarLinkBlock;
import dev.radarfcs.block.RadarLinkBlockEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import org.jetbrains.annotations.Nullable;

/**
 * Камера в руке - как фабричный датчик Create: ПКМ по радарному линку (или по камере, уже стоящей в сети) настраивает
 * предмет на эту радарную сеть, поставленная камера сразу в сети - без своего линка. Shift+ПКМ в воздух - сброс.
 * Сеть хранится там же, где у радарного линка (`RadarNetwork` в данных предмета) и переходит в блок при установке.
 */
public class CameraItem extends BlockItem {
    public CameraItem(Block block, Properties props) {
        super(block, props);
    }

    /** Блок, по которому ПКМ камерой настраивает предмет, а не ставит камеру. */
    @Nullable
    public static UUID tunableNetwork(Level level, BlockPos pos) {
        Block b = level.getBlockState(pos).getBlock();
        if (b instanceof RadarLinkBlock && !(b instanceof GunLinkBlock) && level.getBlockEntity(pos) instanceof RadarLinkBlockEntity l) return l.network();
        if (level.getBlockEntity(pos) instanceof CameraBlockEntity c) return c.network();
        return null;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        UUID net = tunableNetwork(level, ctx.getClickedPos());
        if (net != null) {
            RadarLinkItem.tune(ctx.getItemInHand(), net);
            if (ctx.getPlayer() != null && !level.isClientSide) ctx.getPlayer().displayClientMessage(Component.translatable(
                    "radar_fcs.camera.tuned", RadarLinkBlockEntity.shortName(net)).withStyle(ChatFormatting.AQUA), true);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        InteractionResult r = super.useOn(ctx);
        UUID mine = RadarLinkItem.network(ctx.getItemInHand());
        if (r.consumesAction() && mine != null && ctx.getPlayer() != null && !level.isClientSide) {
            ctx.getPlayer().displayClientMessage(Component.translatable("radar_fcs.camera.placed_in_network",
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
                : Component.translatable("radar_fcs.camera.tooltip_untuned").withStyle(ChatFormatting.DARK_GRAY));
    }
}
