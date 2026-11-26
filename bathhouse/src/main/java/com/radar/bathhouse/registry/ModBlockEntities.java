package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.block.ApplianceBlockEntity;
import com.radar.bathhouse.block.BathGaugeBlockEntity;
import com.radar.bathhouse.plunge.PlungeControllerBlockEntity;
import com.radar.bathhouse.plunge.PlungeStoveBlockEntity;
import com.radar.bathhouse.stove.StoveBlockEntity;
import com.radar.bathhouse.stove.StovePartBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@SuppressWarnings("DataFlowIssue")
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Bathhouse.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StoveBlockEntity>> STOVE =
            BLOCK_ENTITIES.register("stove", () -> BlockEntityType.Builder.of(StoveBlockEntity::new,
                    ModBlocks.STEEL_FIREBOX.get(), ModBlocks.CAST_IRON_FIREBOX.get(), ModBlocks.BRICK_FIREBOX.get(),
                    ModBlocks.HEATING_ELEMENT.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StovePartBlockEntity>> STOVE_PART =
            BLOCK_ENTITIES.register("stove_part", () -> BlockEntityType.Builder.of(StovePartBlockEntity::new,
                    ModBlocks.ASH_PIT.get(), ModBlocks.CLOSED_HEATER.get(), ModBlocks.OPEN_HEATER.get(),
                    ModBlocks.BOILER.get(), ModBlocks.WATER_TANK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ApplianceBlockEntity>> APPLIANCE =
            BLOCK_ENTITIES.register("appliance", () -> BlockEntityType.Builder.of(ApplianceBlockEntity::new,
                    ModBlocks.STEAM_GENERATOR.get(), ModBlocks.WARM_LOUNGER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BathGaugeBlockEntity>> BATH_GAUGE =
            BLOCK_ENTITIES.register("bath_gauge", () -> BlockEntityType.Builder.of(BathGaugeBlockEntity::new,
                    ModBlocks.BATH_GAUGE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlungeStoveBlockEntity>> PLUNGE_STOVE =
            BLOCK_ENTITIES.register("plunge_stove", () -> BlockEntityType.Builder.of(PlungeStoveBlockEntity::new,
                    ModBlocks.PLUNGE_STOVE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlungeControllerBlockEntity>> PLUNGE =
            BLOCK_ENTITIES.register("plunge", () -> BlockEntityType.Builder.of(PlungeControllerBlockEntity::new,
                    ModBlocks.PLUNGE_THERMOMETER.get()).build(null));

    private ModBlockEntities() {
    }
}
