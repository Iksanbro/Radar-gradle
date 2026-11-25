package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.block.ApplianceBlock;
import com.radar.bathhouse.block.ApplianceType;
import com.radar.bathhouse.block.BathGaugeBlock;
import com.radar.bathhouse.block.ChimneyBlock;
import com.radar.bathhouse.block.PlungeBlock;
import com.radar.bathhouse.block.PlungeType;
import com.radar.bathhouse.block.SmokeVentBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Bathhouse.MOD_ID);

    public static final DeferredBlock<ApplianceBlock> INNER_HEATER = appliance(ApplianceType.INNER_HEATER, MapColor.COLOR_GRAY, SoundType.METAL);
    public static final DeferredBlock<ApplianceBlock> OUTER_HEATER = appliance(ApplianceType.OUTER_HEATER, MapColor.COLOR_RED, SoundType.STONE);
    public static final DeferredBlock<ApplianceBlock> BRICK_STOVE = appliance(ApplianceType.BRICK_STOVE, MapColor.COLOR_RED, SoundType.STONE);
    public static final DeferredBlock<ApplianceBlock> IRON_STOVE = appliance(ApplianceType.IRON_STOVE, MapColor.COLOR_BLACK, SoundType.METAL);
    public static final DeferredBlock<ApplianceBlock> ELECTRIC_HEATER = appliance(ApplianceType.ELECTRIC_HEATER, MapColor.METAL, SoundType.METAL);
    public static final DeferredBlock<ApplianceBlock> STEAM_GENERATOR = appliance(ApplianceType.STEAM_GENERATOR, MapColor.SNOW, SoundType.METAL);
    public static final DeferredBlock<ApplianceBlock> WARM_LOUNGER = appliance(ApplianceType.WARM_LOUNGER, MapColor.QUARTZ, SoundType.CALCITE);

    public static final DeferredBlock<PlungeBlock> COLD_PLUNGE = plunge(PlungeType.COLD);
    public static final DeferredBlock<PlungeBlock> HOT_PLUNGE = plunge(PlungeType.HOT);
    public static final DeferredBlock<PlungeBlock> SAWDUST_PLUNGE = plunge(PlungeType.SAWDUST);

    public static final DeferredBlock<BathGaugeBlock> BATH_GAUGE = BLOCKS.register("bath_gauge",
            () -> new BathGaugeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(0.5F)
                    .sound(SoundType.WOOD)
                    .noCollission()
                    .lightLevel(state -> state.getValue(BathGaugeBlock.ACTIVE) ? 4 : 0)));

    public static final DeferredBlock<ChimneyBlock> CHIMNEY = BLOCKS.register("chimney",
            () -> new ChimneyBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    public static final DeferredBlock<SmokeVentBlock> SMOKE_VENT = BLOCKS.register("smoke_vent",
            () -> new SmokeVentBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    .ignitedByLava()));

    private static DeferredBlock<ApplianceBlock> appliance(ApplianceType type, MapColor color, SoundType sound) {
        return BLOCKS.register(type.getSerializedName(), () -> new ApplianceBlock(BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(3.0F, 6.0F)
                .sound(sound)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> state.getValue(ApplianceBlock.LIT) ? type.lightLevel() : 0), type));
    }

    private static DeferredBlock<PlungeBlock> plunge(PlungeType type) {
        return BLOCKS.register(type.getSerializedName(), () -> new PlungeBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F, 3.0F)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .lightLevel(state -> state.getValue(PlungeBlock.LIT) ? 7 : 0), type));
    }

    private ModBlocks() {
    }
}
