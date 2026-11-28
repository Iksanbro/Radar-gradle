package dev.radarfcs.compat.rocket;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Копирование настроек носа ракеты крафтом (как копирование книги): настроенный нос + ненастроенные носы того же
 * вида в любом месте сетки - столько же настроенных копий, оригинал остаётся в сетке. Настроенный - с настройками в
 * предмете (сняли ключом или сломали поставленный нос): сеть, режим и допустимая цель программируемого носа, типы целей
 * тепловой головки.
 */
public class NoseCopyRecipe extends CustomRecipe {
    public NoseCopyRecipe(CraftingBookCategory category) {
        super(category);
    }

    private static boolean nose(ItemStack s) {
        Item i = s.getItem();
        return (RocketNoses.HEAT_ITEM != null && i == RocketNoses.HEAT_ITEM.get())
                || (RocketNoses.NET_ITEM != null && i == RocketNoses.NET_ITEM.get());
    }

    static boolean configured(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && !d.isEmpty();
    }

    /** Номер слота настроенного носа и число ненастроенных; не подходит - null. */
    private static int[] layout(CraftingInput input) {
        int source = -1, blanks = 0;
        Item kind = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.isEmpty()) continue;
            if (!nose(s) || (kind != null && s.getItem() != kind)) return null;
            kind = s.getItem();
            if (configured(s)) {
                if (source >= 0) return null;                  // два настроенных - непонятно, с какого копировать
                source = i;
            } else {
                blanks++;
            }
        }
        return source >= 0 && blanks > 0 ? new int[] {source, blanks} : null;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return layout(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int[] l = layout(input);
        if (l == null) return ItemStack.EMPTY;
        ItemStack src = input.getItem(l[0]);
        ItemStack out = new ItemStack(src.getItem(), l[1]);
        out.set(DataComponents.CUSTOM_DATA, src.get(DataComponents.CUSTOM_DATA));
        return out;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> rest = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        int[] l = layout(input);
        if (l != null) rest.set(l[0], input.getItem(l[0]).copyWithCount(1));
        return rest;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return RocketNoses.COPY.get();
    }
}
