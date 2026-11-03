package dev.radarfcs.item;

import dev.radarfcs.block.GunLinkBlock;
import dev.radarfcs.block.GunLinkBlockEntity;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.link.GunLinks;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * Орудийный линк в руке - как радарный: поставленный без настройки начинает новое орудие; ПКМ по стоящему орудийному
 * линку настраивает предмет на его сеть. Ставится на прицел, вращатель орудия или станок CBC. Shift+ПКМ в воздух -
 * сброс настройки.
 */
public class GunLinkItem extends BlockItem {
    private static final String KEY = "GunNetwork";

    public GunLinkItem(Block block, Properties props) {
        super(block, props);
    }

    @Nullable
    public static UUID network(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag t = data.copyTag();
        return t.hasUUID(KEY) ? t.getUUID(KEY) : null;
    }

    public static void tune(ItemStack stack, @Nullable UUID net) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> {
            if (net == null) t.remove(KEY);
            else t.putUUID(KEY, net);
        });
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos();
        if (level.getBlockState(pos).getBlock() instanceof GunLinkBlock) {
            if (level.getBlockEntity(pos) instanceof GunLinkBlockEntity be && be.network() != null) {
                tune(stack, be.network());
                say(player, level, Component.translatable("radar_fcs.gun_link.tuned", RadarLinkBlockEntity.shortName(be.network()))
                        .withStyle(ChatFormatting.GOLD));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!GunLinkBlockEntity.isGunPart(level.getBlockEntity(pos))) {
            say(player, level, Component.translatable("radar_fcs.gun_link.place_on_gun").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        UUID net = network(stack);
        BlockPlaceContext place = new BlockPlaceContext(ctx);
        BlockPos at = place.getClickedPos();
        InteractionResult r = place(place);
        if (!r.consumesAction()) return r;
        if (!level.isClientSide && level.getBlockEntity(at) instanceof GunLinkBlockEntity be) {
            UUID n = net != null ? net : UUID.randomUUID();
            be.setNetwork(n);
            say(player, level, Component.translatable(net == null ? "radar_fcs.gun_link.new_network" : "radar_fcs.gun_link.connected",
                    RadarLinkBlockEntity.shortName(n), GunLinks.members(level, n).size()).withStyle(ChatFormatting.GREEN));
        }
        return r;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && network(stack) != null) {
            tune(stack, null);
            say(player, level, Component.translatable("radar_fcs.link.cleared").withStyle(ChatFormatting.GRAY));
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return super.use(level, player, hand);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return network(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, tooltip, flag);
        UUID net = network(stack);
        tooltip.add(net != null
                ? Component.translatable("radar_fcs.link.tooltip_tuned", RadarLinkBlockEntity.shortName(net)).withStyle(ChatFormatting.GOLD)
                : Component.translatable("radar_fcs.link.tooltip_untuned").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void say(@Nullable Player p, Level level, Component c) {
        if (p != null && !level.isClientSide) p.displayClientMessage(c, true);
    }
}
