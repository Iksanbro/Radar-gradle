package dev.radarfcs.block;

import dev.radarfcs.registry.FcsRegistry;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Регистрация подшипника орудия. Вызывается из {@link FcsRegistry} только при установленном Create Aeronautics (мод
 * Simulated из его состава): без него подшипника нет - ни блока, ни предмета, ни рецепта (без Aeronautics поворотных
 * частей Sable в игре и так не собрать).
 */
public final class GunBearings {
    public static DeferredBlock<GunBearingBlock> BLOCK;
    public static DeferredItem<BlockItem> ITEM;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<GunBearingBlockEntity>> BE;
    /** Пластина подшипника - на поворотной части (без предмета). */
    public static DeferredBlock<GunBearingPlateBlock> PLATE;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<GunBearingPlateBlockEntity>> PLATE_BE;

    private GunBearings() {}

    public static void register() {
        BLOCK = FcsRegistry.BLOCKS.register("gun_bearing", () -> new GunBearingBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE).strength(3.0f).requiresCorrectToolForDrops().sound(SoundType.METAL).noOcclusion()));
        ITEM = FcsRegistry.ITEMS.register("gun_bearing", () -> new BlockItem(BLOCK.get(), new Item.Properties()));
        BE = FcsRegistry.BLOCK_ENTITIES.register("gun_bearing",
                () -> BlockEntityType.Builder.of((pos, state) -> new GunBearingBlockEntity(BE.get(), pos, state), BLOCK.get()).build(null));
        PLATE = FcsRegistry.BLOCKS.register("gun_bearing_plate", () -> new GunBearingPlateBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE).strength(3.0f).sound(SoundType.METAL).noOcclusion().noLootTable()));
        PLATE_BE = FcsRegistry.BLOCK_ENTITIES.register("gun_bearing_plate",
                () -> BlockEntityType.Builder.of((pos, state) -> new GunBearingPlateBlockEntity(PLATE_BE.get(), pos, state), PLATE.get()).build(null));
    }
}
