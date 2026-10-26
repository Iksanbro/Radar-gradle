package dev.radarfcs;

import com.mojang.logging.LogUtils;
import dev.radarfcs.compat.ShotObserver;
import dev.radarfcs.iff.IffRegistry;
import dev.radarfcs.registry.FcsRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(FcsMod.MODID)
public final class FcsMod {
    public static final String MODID = "radar_fcs";
    public static final Logger LOG = LogUtils.getLogger();

    public FcsMod(IEventBus modBus, ModContainer container) {
        FcsRegistry.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, FcsConfig.SPEC);
        modBus.addListener(FMLCommonSetupEvent.class, e -> e.enqueueWork(FcsRegistry::bindCreate));
        NeoForge.EVENT_BUS.addListener(ShotObserver::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(ShotObserver::onLevelTick);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> {
            ShotObserver.clear();
            IffRegistry.clear();
            dev.radarfcs.fcs.FcsControl.clearClaims();
            dev.radarfcs.radar.RadarNetwork.clear();
        });
    }
}
