package com.radar.bathhouse.event;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.registry.ModBlockEntities;
import com.radar.bathhouse.registry.ModBlocks;
import com.radar.bathhouse.stove.StoveBlockEntity;
import com.radar.bathhouse.stove.StoveStructure;
import net.minecraft.core.BlockPos;
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
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.STOVE.get(),
                (stove, side) -> stove.fuelHandler());
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, be, side) -> {
            BlockPos core = StoveStructure.findCore(level, pos);
            return core != null && level.getBlockEntity(core) instanceof StoveBlockEntity stove ? stove.fuelHandler() : null;
        }, ModBlocks.FUEL_TUNNEL.get());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.STOVE_PART.get(),
                (part, side) -> part.water());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.APPLIANCE.get(),
                (appliance, side) -> appliance.waterHandler());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.PLUNGE_STOVE.get(),
                (stove, side) -> stove.fuelHandler());
    }
}
