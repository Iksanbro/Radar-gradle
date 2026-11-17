package dev.radarfcs.compat.flight;

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
 * Регистрация моста Flight Control. Вызывается из {@link FcsRegistry} только при установленном Create: Flight Control:
 * без него блока, предмета и рецепта нет, классы с API Flight Control не грузятся.
 */
public final class FlightBridge {
    public static DeferredBlock<FlightBridgeBlock> BLOCK;
    public static DeferredItem<BlockItem> ITEM;
    public static DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>, BlockEntityType<FlightBridgeBlockEntity>> BE;

    private FlightBridge() {}

    public static void register() {
        BLOCK = FcsRegistry.BLOCKS.register("flight_bridge", () -> new FlightBridgeBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_ORANGE).strength(2.5f).requiresCorrectToolForDrops().sound(SoundType.METAL).lightLevel(s -> 4)));
        ITEM = FcsRegistry.ITEMS.register("flight_bridge", () -> new BlockItem(BLOCK.get(), new Item.Properties()));
        BE = FcsRegistry.BLOCK_ENTITIES.register("flight_bridge",
                () -> BlockEntityType.Builder.of(FlightBridgeBlockEntity::new, BLOCK.get()).build(null));
    }
}
