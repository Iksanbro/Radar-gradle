package com.radar.bathhouse.stove;

import com.radar.bathhouse.Bathhouse;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Heater stones by item tags. Dense dark rock (basalt, blackstone — like gabbro-diabase) holds heat and never
 * cracks; ordinary rock does; crumbly rock cracks under water.
 */
public final class StoneQuality {
    public static final TagKey<Item> EXCELLENT = TagKey.create(Registries.ITEM, Bathhouse.id("heater_stones/excellent"));
    public static final TagKey<Item> GOOD = TagKey.create(Registries.ITEM, Bathhouse.id("heater_stones/good"));
    public static final TagKey<Item> POOR = TagKey.create(Registries.ITEM, Bathhouse.id("heater_stones/poor"));

    private StoneQuality() {
    }

    /** 3 excellent, 2 good, 1 poor, 0 not a heater stone. */
    public static int of(ItemStack stack) {
        if (stack.is(EXCELLENT)) {
            return 3;
        }
        if (stack.is(GOOD)) {
            return 2;
        }
        if (stack.is(POOR)) {
            return 1;
        }
        return 0;
    }

    public static String translationKey(double average) {
        if (average >= 2.5) {
            return "bathhouse.stones.excellent";
        }
        if (average >= 2.0) {
            return "bathhouse.stones.good";
        }
        if (average > 0) {
            return "bathhouse.stones.poor";
        }
        return "bathhouse.stones.none";
    }
}
