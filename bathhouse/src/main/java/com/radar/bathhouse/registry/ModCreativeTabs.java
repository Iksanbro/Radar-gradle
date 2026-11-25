package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.item.LadleItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Bathhouse.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.bathhouse"))
                    .icon(() -> new ItemStack(ModItems.INNER_HEATER.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.BATH_GAUGE.get());
                        output.accept(ModItems.INNER_HEATER.get());
                        output.accept(ModItems.OUTER_HEATER.get());
                        output.accept(ModItems.BRICK_STOVE.get());
                        output.accept(ModItems.IRON_STOVE.get());
                        output.accept(ModItems.ELECTRIC_HEATER.get());
                        output.accept(ModItems.STEAM_GENERATOR.get());
                        output.accept(ModItems.WARM_LOUNGER.get());
                        output.accept(ModItems.CHIMNEY.get());
                        output.accept(ModItems.SMOKE_VENT.get());
                        output.accept(ModItems.LADLE.get());
                        output.accept(LadleItem.filled(ModItems.LADLE.get()));
                        output.accept(ModItems.BATH_BROOM.get());
                        output.accept(ModItems.SCRUB_MITT.get());
                        output.accept(ModItems.COLD_PLUNGE.get());
                        output.accept(ModItems.HOT_PLUNGE.get());
                        output.accept(ModItems.SAWDUST_PLUNGE.get());
                        output.accept(ModItems.SAWDUST.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
