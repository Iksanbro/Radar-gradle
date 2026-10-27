package dev.radarfcs.item;

import dev.radarfcs.block.RadarLinkBlock;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.link.LinkKind;
import dev.radarfcs.link.LinkRegistry;
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
 * Радарный линк в руке - как складской передатчик Create: поставленный без настройки начинает новую радарную сеть;
 * ПКМ по уже стоящему линку настраивает предмет на его сеть - следующие линки встают в неё. Ставится на аппаратуру
 * (вычислитель, станок, радар Create Radars, монитор). Shift+ПКМ в воздух или сетка крафта - сброс настройки.
 */
public class RadarLinkItem extends BlockItem {
    private static final String KEY = "RadarNetwork";

    public RadarLinkItem(Block block, Properties props) {
        super(block, props);
    }

    /** Сеть, на которую настроен предмет, или null. */
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
        // ПКМ по стоящему линку - настроиться на его сеть
        if (level.getBlockState(pos).getBlock() instanceof RadarLinkBlock) {
            if (level.getBlockEntity(pos) instanceof RadarLinkBlockEntity be && be.network() != null) {
                tune(stack, be.network());
                say(player, level, Component.translatable("radar_fcs.link.tuned", RadarLinkBlockEntity.shortName(be.network()))
                        .withStyle(ChatFormatting.AQUA));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (LinkKind.at(level, pos) == null) {
            say(player, level, Component.translatable("radar_fcs.link.place_on_equipment").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        UUID net = network(stack);
        BlockPlaceContext place = new BlockPlaceContext(ctx);
        BlockPos at = place.getClickedPos();
        InteractionResult r = place(place);
        if (!r.consumesAction()) return r;
        if (!level.isClientSide && level.getBlockEntity(at) instanceof RadarLinkBlockEntity be) {
            if (net == null) {
                UUID fresh = UUID.randomUUID();
                be.setNetwork(fresh);
                say(player, level, Component.translatable("radar_fcs.link.new_network", RadarLinkBlockEntity.shortName(fresh))
                        .withStyle(ChatFormatting.GREEN));
            } else {
                be.setNetwork(net);
                say(player, level, Component.translatable("radar_fcs.link.connected", RadarLinkBlockEntity.shortName(net),
                        LinkRegistry.members(level, net).size()).withStyle(ChatFormatting.GREEN));
            }
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
                ? Component.translatable("radar_fcs.link.tooltip_tuned", RadarLinkBlockEntity.shortName(net)).withStyle(ChatFormatting.AQUA)
                : Component.translatable("radar_fcs.link.tooltip_untuned").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void say(@Nullable Player p, Level level, Component c) {
        if (p != null && !level.isClientSide) p.displayClientMessage(c, true);
    }
}
