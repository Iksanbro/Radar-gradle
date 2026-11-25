package com.radar.bathhouse.event;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.registry.ModBlockEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = Bathhouse.MOD_ID)
public final class ModEvents {
    private ModEvents() {
    }

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Hoppers can feed fuel into fireboxes; pipes can fill the steam generator.
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.APPLIANCE.get(),
                (appliance, side) -> appliance.fuelHandler());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.APPLIANCE.get(),
                (appliance, side) -> appliance.waterHandler());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.PLUNGE.get(),
                (plunge, side) -> plunge.fuelHandler());
    }
}
