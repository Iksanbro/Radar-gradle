package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class ModTags {
    /** Wooden surfaces: required for steam lodges and ember rooms. */
    public static final TagKey<Block> BATH_WOOD = TagKey.create(Registries.BLOCK, Bathhouse.id("bath_wood"));
    /** Stone, marble-like and tiled surfaces: required for mist baths. */
    public static final TagKey<Block> BATH_STONE = TagKey.create(Registries.BLOCK, Bathhouse.id("bath_stone"));
    /** Glass leaks heat quickly. */
    public static final TagKey<Block> BATH_GLASS = TagKey.create(Registries.BLOCK, Bathhouse.id("bath_glass"));
    /** Wool and similar insulation keeps heat in. */
    public static final TagKey<Block> BATH_INSULATION = TagKey.create(Registries.BLOCK, Bathhouse.id("bath_insulation"));

    private ModTags() {
    }
}
