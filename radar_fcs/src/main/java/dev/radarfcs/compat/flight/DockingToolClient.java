package dev.radarfcs.compat.flight;

import ace.flight.item.FlightControlToolGunItem;
import dev.radarfcs.block.DockingControllerBlockEntity;
import dev.radarfcs.compat.DockingPorts;
import dev.radarfcs.compat.SableCompat;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Подсветка связей стыковочного контроллера, когда в руке инструментальный пистолет Flight Control - как подсветка
 * связей самого Flight Control и в его цветах: выбранный контроллер - оранжевый (толще), его порты - голубые с
 * линиями к контроллеру, порт под прицелом при выборе - белый (щелчок добавит или уберёт его). Взгляд на невыбранный
 * контроллер - его связи. Грузится только на клиенте и только при установленном Flight Control.
 */
public final class DockingToolClient {
    /** Цвета и толщины - как у Flight Control (ToolGunLinkVisualization). */
    private static final int SOURCE = 0xFFAA00, LINK = 0x66CCFF, HIGHLIGHT = 0xFFFFFF;
    private static final float THIN = 1 / 16f, THICK = 1 / 8f;

    private DockingToolClient() {}

    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player pl = mc.player;
        ClientLevel level = mc.level;
        if (pl == null || level == null) return;
        boolean tool = pl.getMainHandItem().getItem() instanceof FlightControlToolGunItem
                || pl.getOffhandItem().getItem() instanceof FlightControlToolGunItem;
        if (!tool) {
            DockingToolLink.clientForget(pl);
            return;
        }
        BlockPos hover = mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : null;
        BlockPos picked = DockingToolLink.clientPick(pl);
        DockingControllerBlockEntity src = null;
        if (picked != null && level.getBlockEntity(picked) instanceof DockingControllerBlockEntity d) src = d;
        boolean selected = src != null;
        if (src == null && hover != null && level.getBlockEntity(hover) instanceof DockingControllerBlockEntity d) src = d;
        if (src == null) return;
        Vec3 from = center(level, src.getBlockPos());
        box("radar_fcs:dock_src", from, selected ? SOURCE : HIGHLIGHT, selected ? THICK : THIN);
        int i = 0;
        for (BlockPos c : src.connectorPositions()) {
            Vec3 to = center(level, c);
            box("radar_fcs:dock_link_" + i, to, LINK, THIN);
            Outliner.getInstance().showLine("radar_fcs:dock_line_" + i, from, to).colored(LINK).lineWidth(THIN);
            i++;
        }
        // при выбранном контроллере - порт под прицелом (добавить или убрать)
        if (selected && hover != null && DockingPorts.isConnector(level, hover)) {
            Vec3 to = center(level, hover);
            box("radar_fcs:dock_hover", to, HIGHLIGHT, THICK);
            if (!src.connectorPositions().contains(hover)) {
                Outliner.getInstance().showLine("radar_fcs:dock_hover_line", from, to).colored(HIGHLIGHT).lineWidth(THIN);
            }
        }
    }

    /** Центр блока в мире (на корабле Sable - через его позу). */
    private static Vec3 center(ClientLevel level, BlockPos pos) {
        return SableCompat.toWorldRender(level, pos, Vec3.atCenterOf(pos), 1f);
    }

    private static void box(String slot, Vec3 c, int color, float width) {
        Outliner.getInstance().showAABB(slot, new AABB(c.subtract(0.5, 0.5, 0.5), c.add(0.5, 0.5, 0.5)).inflate(1 / 64.0))
                .colored(color).lineWidth(width);
    }
}
