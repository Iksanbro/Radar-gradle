package dev.radarfcs.compat.rocket;

import dev.radarfcs.registry.FcsRegistry;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Регистрация носов ракет. Вызывается из {@link FcsRegistry} только при установленном CBC Enchanced Shells: без него
 * носов нет - ни блоков, ни предметов, ни рецептов (ракеты - из этого мода).
 */
public final class RocketNoses {
    public static DeferredBlock<HeatNoseBlock> HEAT;
    public static DeferredItem<BlockItem> HEAT_ITEM;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<HeatNoseBlockEntity>> HEAT_BE;
    public static DeferredBlock<NetNoseBlock> NET;
    public static DeferredItem<NetNoseItem> NET_ITEM;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<NetNoseBlockEntity>> NET_BE;
    /** Копирование настроек носа крафтом. */
    public static DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<NoseCopyRecipe>> COPY;

    private RocketNoses() {}

    private static BlockBehaviour.Properties props() {
        // как стальной нос ракеты CBC Enchanced Shells
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f, 6.0f).sound(SoundType.NETHERITE_BLOCK).noOcclusion();
    }

    public static void register() {
        HEAT = FcsRegistry.BLOCKS.register("heat_seeker_nose", () -> new HeatNoseBlock(props()));
        HEAT_ITEM = FcsRegistry.ITEMS.register("heat_seeker_nose", () -> new BlockItem(HEAT.get(), new Item.Properties()));
        HEAT_BE = FcsRegistry.BLOCK_ENTITIES.register("heat_seeker_nose",
                () -> BlockEntityType.Builder.of((pos, state) -> new HeatNoseBlockEntity(HEAT_BE.get(), pos, state), HEAT.get()).build(null));
        NET = FcsRegistry.BLOCKS.register("network_nose", () -> new NetNoseBlock(props()));
        NET_ITEM = FcsRegistry.ITEMS.register("network_nose", () -> new NetNoseItem(NET.get(), new Item.Properties()));
        NET_BE = FcsRegistry.BLOCK_ENTITIES.register("network_nose",
                () -> BlockEntityType.Builder.of((pos, state) -> new NetNoseBlockEntity(NET_BE.get(), pos, state), NET.get()).build(null));
        COPY = FcsRegistry.RECIPE_SERIALIZERS.register("rocket_nose_copy", () -> new SimpleCraftingRecipeSerializer<>(NoseCopyRecipe::new));
    }
}
