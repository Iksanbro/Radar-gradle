package com.radar.bathhouse.registry;

import com.mojang.serialization.Codec;
import com.radar.bathhouse.Bathhouse;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Bathhouse.MOD_ID);

    /** Number of water portions held by a ladle. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> LADLE_WATER =
            COMPONENTS.registerComponentType("ladle_water",
                    builder -> builder.persistent(Codec.intRange(0, 64)).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** The ladle holds hot water from the stove's tank. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LADLE_HOT =
            COMPONENTS.registerComponentType("ladle_hot",
                    builder -> builder.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));

    private ModComponents() {
    }
}
