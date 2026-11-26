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
                    .icon(() -> new ItemStack(ModBlocks.CLOSED_HEATER.get()))
                    .displayItems((params, output) -> {
                        // Blocks in registration order: gauge-related, stove parts, appliances, plunge parts.
                        ModItems.ITEMS.getEntries().forEach(item -> {
                            output.accept(item.get());
                            if (item.get() instanceof LadleItem ladle) {
                                output.accept(LadleItem.filled(ladle));
                            }
                        });
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
