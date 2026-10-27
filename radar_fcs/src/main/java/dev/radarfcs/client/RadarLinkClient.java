package dev.radarfcs.client;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.item.RadarLinkItem;
import dev.radarfcs.link.LinkKind;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Подсветка радарного линка: с линком в руке - выбранная аппаратура (голубая) и аппаратура под прицелом
 * (зелёная - можно связать, красная - нельзя); взгляд на поставленный линк - оба конца и линия между ними.
 */
@EventBusSubscriber(modid = FcsMod.MODID, value = Dist.CLIENT)
public final class RadarLinkClient {
    private static final int SELECTED = 0x7FD4FF, VALID = 0x6CFF8C, INVALID = 0xFF5050, OTHER_END = 0xFFD24A;
    private static final float LINE = 1 / 16f;

    private RadarLinkClient() {}

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Level level = mc.level;
        BlockPos hover = mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : null;

        ItemStack held = mc.player.getMainHandItem().getItem() instanceof RadarLinkItem ? mc.player.getMainHandItem()
                : mc.player.getOffhandItem().getItem() instanceof RadarLinkItem ? mc.player.getOffhandItem() : null;
        if (held != null) {
            BlockPos sel = RadarLinkItem.selection(held, level);
            LinkKind selKind = sel == null ? null : LinkKind.at(level, sel);
            if (sel != null) outline("radar_fcs:link_selected", sel, SELECTED);
            if (hover != null && !hover.equals(sel)) {
                LinkKind k = LinkKind.at(level, hover);
                if (k != null) {
                    boolean ok = sel == null || (selKind != null && LinkKind.canLink(selKind, k));
                    outline("radar_fcs:link_hover", hover, ok ? VALID : INVALID);
                }
            }
        }
        if (hover != null && level.getBlockEntity(hover) instanceof RadarLinkBlockEntity link && link.hasTarget()) {
            BlockPos a = link.attached(), b = link.other();
            outline("radar_fcs:link_end_a", a, SELECTED);
            outline("radar_fcs:link_end_b", b, OTHER_END);
            Outliner.getInstance().showLine("radar_fcs:link_line", Vec3.atCenterOf(a), Vec3.atCenterOf(b))
                    .colored(OTHER_END).lineWidth(LINE / 2);
        }
    }

    private static void outline(String slot, BlockPos pos, int color) {
        Outliner.getInstance().showAABB(slot, new AABB(pos).inflate(1 / 64.0)).colored(color).lineWidth(LINE);
    }
}
