package dev.radarfcs.client;

import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.net.TransponderPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Вызовы клиента из общих классов (загружается только на клиенте). */
public final class FcsClientHooks {
    private FcsClientHooks() {}

    /** Экран вычислителя с настройками от сервера (коды «свой-чужой» - если iffAccess). */
    public static void openFcsSettings(BlockPos pos, EngageSettings settings, boolean iffAccess) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof FireControlBlockEntity) {
            mc.setScreen(new FcsSettingsScreen(pos, settings, iffAccess));
        }
    }

    public static void openTransponder(TransponderPayloads.Open p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.setScreen(new TransponderScreen(p));
    }

    public static void openMonitor(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof RadarMonitorBlockEntity be) {
            mc.setScreen(new RadarMonitorScreen(be.master().getBlockPos()));
        }
    }

    /** Открыть список камер монитора (скриншоты для документации): запрос идёт через сервер, как ПКМ по монитору. */
    public static void openCameraList(BlockPos monitor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null || mc.player == null) return;
        var sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        if (sp != null) mc.getSingleplayerServer().execute(() -> dev.radarfcs.net.CameraPayloads.openList(sp, monitor));
    }
}
