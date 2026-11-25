package com.radar.bathhouse;

import com.radar.bathhouse.registry.ModBlockEntities;
import com.radar.bathhouse.registry.ModBlocks;
import com.radar.bathhouse.registry.ModComponents;
import com.radar.bathhouse.registry.ModCreativeTabs;
import com.radar.bathhouse.registry.ModEffects;
import com.radar.bathhouse.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Bathhouse mod: three kinds of bathhouses (mist / steam / ember), each assembled from
 * room climate (temperature, humidity, smoke), wall materials and heating appliances.
 */
@Mod(Bathhouse.MOD_ID)
public final class Bathhouse {
    public static final String MOD_ID = "bathhouse";

    public Bathhouse(IEventBus modBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModComponents.COMPONENTS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModEffects.EFFECTS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);

        container.registerConfig(ModConfig.Type.SERVER, BathConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
