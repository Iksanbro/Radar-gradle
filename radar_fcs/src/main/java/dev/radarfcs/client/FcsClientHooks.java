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
}
