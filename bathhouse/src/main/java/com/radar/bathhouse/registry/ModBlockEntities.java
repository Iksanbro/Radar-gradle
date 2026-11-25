package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.block.ApplianceBlockEntity;
import com.radar.bathhouse.block.BathGaugeBlockEntity;
import com.radar.bathhouse.block.PlungeBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Bathhouse.MOD_ID);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ApplianceBlockEntity>> APPLIANCE =
            BLOCK_ENTITIES.register("appliance", () -> BlockEntityType.Builder.of(ApplianceBlockEntity::new,
                    ModBlocks.INNER_HEATER.get(),
                    ModBlocks.OUTER_HEATER.get(),
                    ModBlocks.BRICK_STOVE.get(),
                    ModBlocks.IRON_STOVE.get(),
                    ModBlocks.ELECTRIC_HEATER.get(),
                    ModBlocks.STEAM_GENERATOR.get(),
                    ModBlocks.WARM_LOUNGER.get()).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlungeBlockEntity>> PLUNGE =
            BLOCK_ENTITIES.register("plunge", () -> BlockEntityType.Builder.of(PlungeBlockEntity::new,
                    ModBlocks.COLD_PLUNGE.get(),
                    ModBlocks.HOT_PLUNGE.get(),
                    ModBlocks.SAWDUST_PLUNGE.get()).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BathGaugeBlockEntity>> BATH_GAUGE =
            BLOCK_ENTITIES.register("bath_gauge", () -> BlockEntityType.Builder.of(BathGaugeBlockEntity::new,
                    ModBlocks.BATH_GAUGE.get()).build(null));

    private ModBlockEntities() {
    }
}
