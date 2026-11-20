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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

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

    /** Тик клиента: пистолет убран из рук - выбор забыт (как у Flight Control). */
    public static void onTick(ClientTickEvent.Post event) {
        Player pl = Minecraft.getInstance().player;
        if (pl != null && !holdsTool(pl)) DockingToolLink.clientForget(pl);
    }

    private static boolean holdsTool(Player pl) {
        return pl.getMainHandItem().getItem() instanceof FlightControlToolGunItem
                || pl.getOffhandItem().getItem() instanceof FlightControlToolGunItem;
    }

    /**
     * Каждый кадр (с долей тика): рамки - 12 рёбер по углам блока, переведённым через позу отрисовки корабля Sable, -
     * поворачиваются вместе с кораблём и не отстают от него; на земле - обычные рамки блоков.
     */
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        Player pl = mc.player;
        ClientLevel level = mc.level;
        if (pl == null || level == null || !holdsTool(pl)) return;
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        BlockPos hover = mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : null;
        BlockPos picked = DockingToolLink.clientPick(pl);
        DockingControllerBlockEntity src = null;
        if (picked != null && level.getBlockEntity(picked) instanceof DockingControllerBlockEntity d) src = d;
        boolean selected = src != null;
        if (src == null && hover != null && level.getBlockEntity(hover) instanceof DockingControllerBlockEntity d) src = d;
        if (src == null) return;
        Vec3 from = point(level, src.getBlockPos(), Vec3.atCenterOf(src.getBlockPos()), pt);
        box("radar_fcs:dock_src", level, src.getBlockPos(), pt, selected ? SOURCE : HIGHLIGHT, selected ? THICK : THIN);
        int i = 0;
        for (BlockPos c : src.connectorPositions()) {
            box("radar_fcs:dock_link_" + i, level, c, pt, LINK, THIN);
            Outliner.getInstance().showLine("radar_fcs:dock_line_" + i, from, point(level, c, Vec3.atCenterOf(c), pt))
                    .colored(LINK).lineWidth(THIN);
            i++;
        }
        // при выбранном контроллере - порт под прицелом (добавить или убрать)
        if (selected && hover != null && DockingPorts.isConnector(level, hover)) {
            box("radar_fcs:dock_hover", level, hover, pt, HIGHLIGHT, THICK);
            if (!src.connectorPositions().contains(hover)) {
                Outliner.getInstance().showLine("radar_fcs:dock_hover_line", from, point(level, hover, Vec3.atCenterOf(hover), pt))
                        .colored(HIGHLIGHT).lineWidth(THIN);
            }
        }
    }

    /** Точка блока pos в мире на этот кадр (на корабле Sable - через его позу отрисовки). */
    private static Vec3 point(ClientLevel level, BlockPos pos, Vec3 local, float pt) {
        return SableCompat.toWorldRender(level, pos, local, pt);
    }

    private static final int[][] EDGES = {{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    /** Рамка блока: 12 рёбер между его углами (чуть больше блока), повёрнутая вместе с кораблём. */
    private static void box(String slot, ClientLevel level, BlockPos pos, float pt, int color, float width) {
        double e = 1 / 64.0;
        Vec3[] c = new Vec3[8];
        for (int k = 0; k < 8; k++) {
            Vec3 local = new Vec3(pos.getX() + ((k & 1) != 0 ? 1 + e : -e), pos.getY() + ((k & 2) != 0 ? 1 + e : -e),
                    pos.getZ() + ((k & 4) != 0 ? 1 + e : -e));
            c[k] = point(level, pos, local, pt);
        }
        for (int k = 0; k < EDGES.length; k++) {
            Outliner.getInstance().showLine(slot + "_" + k, c[EDGES[k][0]], c[EDGES[k][1]]).colored(color).lineWidth(width);
        }
    }
}
