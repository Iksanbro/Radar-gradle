package dev.radarfcs.client;

import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Вызовы клиента из общих классов (загружается только на клиенте). */
public final class FcsClientHooks {
    private FcsClientHooks() {}

    public static void openFcsSettings(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof FireControlBlockEntity be) {
            mc.setScreen(new FcsSettingsScreen(pos, be.settings()));
        }
    }

    public static void openMonitor(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof RadarMonitorBlockEntity be) {
            mc.setScreen(new RadarMonitorScreen(be.master().getBlockPos()));
        }
    }
}
