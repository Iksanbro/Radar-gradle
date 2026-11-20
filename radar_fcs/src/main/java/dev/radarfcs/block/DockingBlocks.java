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

/** Регистрация стыковочного контроллера и маяка орбиты: вызывается из {@link FcsRegistry} только при установленном Create: Flight Control. */
public final class DockingBlocks {
    public static DeferredBlock<DockingControllerBlock> BLOCK;
    public static DeferredItem<BlockItem> ITEM;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<DockingControllerBlockEntity>> BE;
    public static DeferredBlock<OrbitBeaconBlock> BEACON;
    public static DeferredItem<BlockItem> BEACON_ITEM;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<OrbitBeaconBlockEntity>> BEACON_BE;

    private DockingBlocks() {}

    public static void register() {
        BLOCK = FcsRegistry.BLOCKS.register("docking_controller", () -> new DockingControllerBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_ORANGE).strength(2.5f).requiresCorrectToolForDrops().sound(SoundType.METAL).lightLevel(s -> 4)));
        ITEM = FcsRegistry.ITEMS.register("docking_controller", () -> new BlockItem(BLOCK.get(), new Item.Properties()));
        BE = FcsRegistry.BLOCK_ENTITIES.register("docking_controller",
                () -> BlockEntityType.Builder.of((p, s) -> new DockingControllerBlockEntity(BE.get(), p, s), BLOCK.get()).build(null));
        BEACON = FcsRegistry.BLOCKS.register("orbit_beacon", () -> new OrbitBeaconBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_ORANGE).strength(2.5f).requiresCorrectToolForDrops().sound(SoundType.METAL).lightLevel(s -> 6)));
        BEACON_ITEM = FcsRegistry.ITEMS.register("orbit_beacon", () -> new BlockItem(BEACON.get(), new Item.Properties()));
        BEACON_BE = FcsRegistry.BLOCK_ENTITIES.register("orbit_beacon",
                () -> BlockEntityType.Builder.of((p, s) -> new OrbitBeaconBlockEntity(BEACON_BE.get(), p, s), BEACON.get()).build(null));
    }
}
