package dev.radarfcs.item;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.link.LinkKind;
import dev.radarfcs.link.LinkRegistry;
import java.util.List;
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
 * Радарный линк в руке: ПКМ по аппаратуре - выбрать первый конец; ПКМ по второй аппаратуре - поставить на неё линк
 * (связь появляется сразу). Shift+ПКМ - снять выбор. Как Display Link у Create.
 */
public class RadarLinkItem extends BlockItem {
    private static final String KEY = "RadarLinkSource", DIM = "RadarLinkDim";

    public RadarLinkItem(Block block, Properties props) {
        super(block, props);
    }

    /** Выбранный первый конец (в этом измерении) или null. */
    @Nullable
    public static BlockPos selection(ItemStack stack, Level level) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag t = data.copyTag();
        if (!t.contains(KEY) || !level.dimension().location().toString().equals(t.getString(DIM))) return null;
        return BlockPos.of(t.getLong(KEY));
    }

    private static void select(ItemStack stack, Level level, @Nullable BlockPos pos) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> {
            if (pos == null) {
                t.remove(KEY);
                t.remove(DIM);
            } else {
                t.putLong(KEY, pos.asLong());
                t.putString(DIM, level.dimension().location().toString());
            }
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
        if (player != null && player.isShiftKeyDown()) {
            select(stack, level, null);
            say(player, level, Component.translatable("radar_fcs.link.cleared").withStyle(ChatFormatting.GRAY));
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        LinkKind kind = LinkKind.at(level, pos);
        BlockPos sel = selection(stack, level);
        if (sel == null) {
            if (kind == null) {
                say(player, level, Component.translatable("radar_fcs.link.select_first").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            select(stack, level, pos);
            say(player, level, Component.translatable("radar_fcs.link.selected", kind.displayName()).withStyle(ChatFormatting.AQUA));
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (sel.equals(pos)) {
            select(stack, level, null);
            say(player, level, Component.translatable("radar_fcs.link.cleared").withStyle(ChatFormatting.GRAY));
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        LinkKind selKind = LinkKind.at(level, sel);
        Component problem = null;
        if (selKind == null) {
            select(stack, level, null);
            problem = Component.translatable("radar_fcs.link.selection_lost");
        } else if (kind == null) {
            problem = Component.translatable("radar_fcs.link.not_equipment");
        } else if (!LinkKind.canLink(selKind, kind)) {
            problem = Component.translatable("radar_fcs.link.cannot_link", selKind.displayName(), kind.displayName());
        } else if (sel.distSqr(pos) > sq(FcsConfig.LINK_RANGE.get())) {
            problem = Component.translatable("radar_fcs.link.too_far", FcsConfig.LINK_RANGE.get());
        } else if (!level.isClientSide && LinkRegistry.linked(level, sel, pos)) {
            problem = Component.translatable("radar_fcs.link.already_linked");
        }
        if (problem != null) {
            say(player, level, problem.copy().withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        // поставить линк на грань второй аппаратуры
        BlockPlaceContext place = new BlockPlaceContext(ctx);
        BlockPos at = place.getClickedPos();
        InteractionResult r = place(place);
        if (!r.consumesAction()) return r;
        if (!level.isClientSide && level.getBlockEntity(at) instanceof RadarLinkBlockEntity be) {
            be.setOther(sel);
            say(player, level, Component.translatable("radar_fcs.link.linked", selKind.displayName(), kind.displayName())
                    .withStyle(ChatFormatting.GREEN));
        }
        select(stack, level, null);
        return r;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // Shift+ПКМ в воздух - снять выбор
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && selection(stack, level) != null) {
            select(stack, level, null);
            say(player, level, Component.translatable("radar_fcs.link.cleared").withStyle(ChatFormatting.GRAY));
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return super.use(level, player, hand);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(KEY);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, tooltip, flag);
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.contains(KEY)) {
            BlockPos p = BlockPos.of(data.copyTag().getLong(KEY));
            tooltip.add(Component.translatable("radar_fcs.link.tooltip_selected", p.getX(), p.getY(), p.getZ())
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void say(@Nullable Player p, Level level, Component c) {
        if (p != null && !level.isClientSide) p.displayClientMessage(c, true);
    }
}
