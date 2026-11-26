package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.block.ApplianceBlock;
import com.radar.bathhouse.block.ApplianceType;
import com.radar.bathhouse.block.BathGaugeBlock;
import com.radar.bathhouse.block.ChimneyBlock;
import com.radar.bathhouse.block.SmokeVentBlock;
import com.radar.bathhouse.plunge.PlungeBenchBlock;
import com.radar.bathhouse.plunge.PlungeChillerBlock;
import com.radar.bathhouse.plunge.PlungeMaterial;
import com.radar.bathhouse.plunge.PlungeStoveBlock;
import com.radar.bathhouse.plunge.PlungeThermometerBlock;
import com.radar.bathhouse.plunge.PlungeWallBlock;
import com.radar.bathhouse.plunge.SawdustBlock;
import com.radar.bathhouse.stove.FireboxBlock;
import com.radar.bathhouse.stove.FireboxType;
import com.radar.bathhouse.stove.StovePartBlock;
import com.radar.bathhouse.stove.StovePartType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Bathhouse.MOD_ID);

    // --- stove: fireboxes (cores)
    public static final DeferredBlock<FireboxBlock> STEEL_FIREBOX = firebox(FireboxType.STEEL, MapColor.METAL, SoundType.METAL);
    public static final DeferredBlock<FireboxBlock> CAST_IRON_FIREBOX = firebox(FireboxType.CAST_IRON, MapColor.COLOR_BLACK, SoundType.METAL);
    public static final DeferredBlock<FireboxBlock> BRICK_FIREBOX = firebox(FireboxType.BRICK, MapColor.COLOR_RED, SoundType.STONE);
    public static final DeferredBlock<FireboxBlock> HEATING_ELEMENT = firebox(FireboxType.ELECTRIC, MapColor.METAL, SoundType.METAL);

    // --- stove: parts
    public static final DeferredBlock<StovePartBlock> ASH_PIT = part(StovePartType.ASH_PIT, MapColor.COLOR_GRAY, SoundType.METAL);
    public static final DeferredBlock<StovePartBlock> CLOSED_HEATER = part(StovePartType.CLOSED_HEATER, MapColor.COLOR_GRAY, SoundType.METAL);
    public static final DeferredBlock<StovePartBlock> OPEN_HEATER = part(StovePartType.OPEN_HEATER, MapColor.STONE, SoundType.STONE);
    public static final DeferredBlock<StovePartBlock> BOILER = part(StovePartType.BOILER, MapColor.COLOR_ORANGE, SoundType.COPPER);
    public static final DeferredBlock<StovePartBlock> WATER_TANK = part(StovePartType.WATER_TANK, MapColor.METAL, SoundType.METAL);
    public static final DeferredBlock<StovePartBlock> DAMPER = part(StovePartType.DAMPER, MapColor.COLOR_RED, SoundType.STONE);
    public static final DeferredBlock<StovePartBlock> FUEL_TUNNEL = part(StovePartType.FUEL_TUNNEL, MapColor.METAL, SoundType.METAL);
    public static final DeferredBlock<StovePartBlock> STOVE_CLADDING = part(StovePartType.CLADDING, MapColor.COLOR_LIGHT_GREEN, SoundType.STONE);

    public static final DeferredBlock<ChimneyBlock> CHIMNEY = BLOCKS.register("chimney",
            () -> new ChimneyBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    // --- single-block appliances
    public static final DeferredBlock<ApplianceBlock> STEAM_GENERATOR = appliance(ApplianceType.STEAM_GENERATOR, MapColor.SNOW, SoundType.METAL);
    public static final DeferredBlock<ApplianceBlock> WARM_LOUNGER = appliance(ApplianceType.WARM_LOUNGER, MapColor.QUARTZ, SoundType.CALCITE);

    public static final DeferredBlock<BathGaugeBlock> BATH_GAUGE = BLOCKS.register("bath_gauge",
            () -> new BathGaugeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(0.5F)
                    .sound(SoundType.WOOD)
                    .noCollission()
                    .lightLevel(state -> state.getValue(BathGaugeBlock.ACTIVE) ? 4 : 0)));

    public static final DeferredBlock<SmokeVentBlock> SMOKE_VENT = BLOCKS.register("smoke_vent",
            () -> new SmokeVentBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    .ignitedByLava()));

    // --- plunge parts
    public static final DeferredBlock<PlungeWallBlock> PLUNGE_WALL_WOOD = plungeWall(PlungeMaterial.WOOD, MapColor.WOOD, SoundType.WOOD);
    public static final DeferredBlock<PlungeWallBlock> PLUNGE_WALL_COMPOSITE = plungeWall(PlungeMaterial.COMPOSITE, MapColor.COLOR_BROWN, SoundType.WOOD);
    public static final DeferredBlock<PlungeWallBlock> PLUNGE_WALL_TILE = plungeWall(PlungeMaterial.TILE, MapColor.COLOR_LIGHT_BLUE, SoundType.STONE);

    public static final DeferredBlock<PlungeBenchBlock> PLUNGE_BENCH = BLOCKS.register("plunge_bench",
            () -> new PlungeBenchBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5F)
                    .sound(SoundType.WOOD)));
    public static final DeferredBlock<PlungeStoveBlock> PLUNGE_STOVE = BLOCKS.register("plunge_stove",
            () -> new PlungeStoveBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> state.getValue(PlungeStoveBlock.LIT) ? 11 : 0)));
    public static final DeferredBlock<PlungeChillerBlock> PLUNGE_CHILLER = BLOCKS.register("plunge_chiller",
            () -> new PlungeChillerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.ICE)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> state.getValue(PlungeChillerBlock.POWERED) ? 5 : 0)));
    public static final DeferredBlock<PlungeThermometerBlock> PLUNGE_THERMOMETER = BLOCKS.register("plunge_thermometer",
            () -> new PlungeThermometerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 3.0F)
                    .sound(SoundType.WOOD)
                    .lightLevel(state -> state.getValue(PlungeThermometerBlock.ACTIVE) ? 4 : 0)));
    public static final DeferredBlock<SawdustBlock> SAWDUST = BLOCKS.register("sawdust",
            () -> new SawdustBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SAND)
                    .strength(0.3F)
                    .sound(SoundType.SAND)
                    .noCollission()
                    .noOcclusion()
                    .ignitedByLava()
                    .pushReaction(PushReaction.DESTROY)));

    private static DeferredBlock<FireboxBlock> firebox(FireboxType type, MapColor color, SoundType sound) {
        int light = type.burnsFuel() ? 13 : 7;
        return BLOCKS.register(type.getSerializedName(), () -> new FireboxBlock(BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(3.0F, 6.0F)
                .sound(sound)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> state.getValue(FireboxBlock.LIT) ? light : 0), type));
    }

    private static DeferredBlock<StovePartBlock> part(StovePartType type, MapColor color, SoundType sound) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(3.0F, 6.0F)
                .sound(sound)
                .requiresCorrectToolForDrops();
        if (type == StovePartType.DAMPER) {
            properties = properties.noOcclusion();
        }
        BlockBehaviour.Properties finalProperties = properties;
        return BLOCKS.register(type.getSerializedName(), () -> new StovePartBlock(finalProperties, type));
    }

    private static DeferredBlock<ApplianceBlock> appliance(ApplianceType type, MapColor color, SoundType sound) {
        return BLOCKS.register(type.getSerializedName(), () -> new ApplianceBlock(BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(3.0F, 6.0F)
                .sound(sound)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> state.getValue(ApplianceBlock.LIT) ? type.lightLevel() : 0), type));
    }

    private static DeferredBlock<PlungeWallBlock> plungeWall(PlungeMaterial material, MapColor color, SoundType sound) {
        return BLOCKS.register(material.blockId(), () -> new PlungeWallBlock(BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(2.0F, 3.0F)
                .sound(sound), material));
    }

    private ModBlocks() {
    }
}
