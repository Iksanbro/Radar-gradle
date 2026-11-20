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
        modBus.addListener(FcsRegistry::registerCapabilities);
        modBus.addListener(dev.radarfcs.net.FcsSettingsPayload::register);
        modBus.addListener(dev.radarfcs.net.FcsCommandPayload::register);
        modBus.addListener(dev.radarfcs.net.MonitorCommandPayload::register);
        modBus.addListener(dev.radarfcs.net.FcsOpenPayload::register);
        modBus.addListener(dev.radarfcs.net.TransponderPayloads::register);
        modBus.addListener(dev.radarfcs.net.CameraPayloads::register);
        modBus.addListener(dev.radarfcs.net.GunPayloads::register);
        modBus.addListener(dev.radarfcs.net.AutopilotPayloads::register);
        modBus.addListener(dev.radarfcs.net.DockingPayloads::register);
        modBus.addListener(dev.radarfcs.net.BeaconPayloads::register);
        // обучающие сцены Ponder (как в Create) - только на клиенте
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) dev.radarfcs.client.ponder.FcsPonderPlugin.init();
        if (dev.radarfcs.compat.Mods.SABLE) dev.radarfcs.compat.sable.SableRotor.init(dev.radarfcs.block.GunRotatorBlockEntity::physicsStepAll);
        NeoForge.EVENT_BUS.addListener(ShotObserver::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(ShotObserver::onLevelTick);
        // после ShotObserver: снаряд своей пушки уже помечен вычислителем, здесь - только точка запуска чужих
        NeoForge.EVENT_BUS.addListener(dev.radarfcs.fcs.ProjectileTracking::onEntityJoin);
        // радарный линк в руке: ПКМ по аппаратуре - работа предмета (выбор, установка), а не блока (экран, назначение цели)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class, e -> {
            if (e.getItemStack().getItem() instanceof dev.radarfcs.item.RadarLinkItem
                    && (dev.radarfcs.link.LinkKind.at(e.getLevel(), e.getPos()) != null
                    || e.getLevel().getBlockState(e.getPos()).getBlock() instanceof dev.radarfcs.block.RadarLinkBlock)) {
                e.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            }
            // камера в руке по радарному линку или по камере в сети - настроить предмет на сеть
            if (e.getItemStack().getItem() instanceof dev.radarfcs.item.CameraItem
                    && dev.radarfcs.item.CameraItem.tunableNetwork(e.getLevel(), e.getPos()) != null) {
                e.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            }
            if (e.getItemStack().getItem() instanceof dev.radarfcs.item.GunLinkItem
                    && (dev.radarfcs.block.GunLinkBlockEntity.isGunPart(e.getLevel().getBlockEntity(e.getPos()))
                    || e.getLevel().getBlockState(e.getPos()).getBlock() instanceof dev.radarfcs.block.GunLinkBlock)) {
                e.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> {
            ShotObserver.clear();
            dev.radarfcs.fcs.ProjectileTracking.clear();
            IffRegistry.clear();
            dev.radarfcs.fcs.FcsControl.clearClaims();
            dev.radarfcs.link.LinkRegistry.clear();
            dev.radarfcs.link.GunLinks.clear();
            dev.radarfcs.dock.DockingRegistry.clear();
            dev.radarfcs.compat.DockingPorts.clear();
            dev.radarfcs.dock.DockHold.clear();
        });
        NeoForge.EVENT_BUS.addListener(dev.radarfcs.compat.DockingPorts::onLevelTick);
        NeoForge.EVENT_BUS.addListener(dev.radarfcs.compat.DockingPorts::onLevelTickEnd);
        // стыковочный контроллер: связь с портом Simulated инструментальным пистолетом Flight Control
        if (dev.radarfcs.compat.Mods.FLIGHT_CONTROL) {
            dev.radarfcs.compat.flight.DockingToolLink.init();
            // подсветка связей стыковочного контроллера с пистолетом Flight Control в руке - только на клиенте
            if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
                NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class,
                        dev.radarfcs.compat.flight.DockingToolClient::onTick);
                NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.class,
                        dev.radarfcs.compat.flight.DockingToolClient::onRender);
            }
        }
    }
}
