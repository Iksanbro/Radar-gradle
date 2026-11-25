package dev.aeroschematics;

import com.mojang.logging.LogUtils;
import dev.aeroschematics.config.AeroConfig;
import dev.aeroschematics.nbt.AeroSanitizer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

@Mod(AeroSchematics.MOD_ID)
public final class AeroSchematics {
    public static final String MOD_ID = "aeroschematics";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeroSchematics(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, AeroConfig.SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, e -> AeroSanitizer.reload());
        modBus.addListener(ModConfigEvent.Reloading.class, e -> AeroSanitizer.reload());
    }
}
