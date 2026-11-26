package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.effect.BathEffect;
import com.radar.bathhouse.effect.FrostproofEffect;
import com.radar.bathhouse.effect.ImmunityEffect;
import com.radar.bathhouse.effect.PurityEffect;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Every bath (and plunge) buff is split into three parts, granted cumulatively by the steam level:
 * level 1 gives the first part, level 2 the first two, level 3 all three.
 */
public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, Bathhouse.MOD_ID);

    // Mist hall
    public static final DeferredHolder<MobEffect, MobEffect> PURITY = EFFECTS.register("purity", () -> new PurityEffect(0x9FD8E6));
    public static final DeferredHolder<MobEffect, MobEffect> CLARITY = EFFECTS.register("clarity",
            () -> new ImmunityEffect(0xC9E9F2, List.of(MobEffects.CONFUSION, MobEffects.HUNGER)));
    public static final DeferredHolder<MobEffect, MobEffect> CLEANSING = EFFECTS.register("cleansing",
            () -> new ImmunityEffect(0x5FB9A5, List.of(MobEffects.POISON)));

    // Steam lodge
    public static final DeferredHolder<MobEffect, MobEffect> FORTITUDE = EFFECTS.register("fortitude",
            () -> new BathEffect(0xC9622F).addAttributeModifier(Attributes.MAX_HEALTH, Bathhouse.id("effect.fortitude"),
                    4.0, AttributeModifier.Operation.ADD_VALUE));
    public static final DeferredHolder<MobEffect, MobEffect> PROWESS = EFFECTS.register("prowess",
            () -> new BathEffect(0xA8432A).addAttributeModifier(Attributes.ATTACK_DAMAGE, Bathhouse.id("effect.prowess"),
                    1.0, AttributeModifier.Operation.ADD_VALUE));
    public static final DeferredHolder<MobEffect, MobEffect> STEADFASTNESS = EFFECTS.register("steadfastness",
            () -> new BathEffect(0x8C6B4A).addAttributeModifier(Attributes.KNOCKBACK_RESISTANCE, Bathhouse.id("effect.steadfastness"),
                    0.2, AttributeModifier.Operation.ADD_VALUE));

    // Ember room
    public static final DeferredHolder<MobEffect, MobEffect> LIGHTNESS = EFFECTS.register("lightness",
            () -> new BathEffect(0xE8A33A).addAttributeModifier(Attributes.MOVEMENT_SPEED, Bathhouse.id("effect.lightness"),
                    0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    public static final DeferredHolder<MobEffect, MobEffect> QUICK_HANDS = EFFECTS.register("quick_hands",
            () -> new BathEffect(0xF0C060).addAttributeModifier(Attributes.ATTACK_SPEED, Bathhouse.id("effect.quick_hands"),
                    0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    public static final DeferredHolder<MobEffect, MobEffect> KNACK = EFFECTS.register("knack",
            () -> new BathEffect(0xD98A2B).addAttributeModifier(Attributes.BLOCK_BREAK_SPEED, Bathhouse.id("effect.knack"),
                    0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

    // Cold plunge (contrast)
    public static final DeferredHolder<MobEffect, MobEffect> TEMPERING = EFFECTS.register("tempering",
            () -> new BathEffect(0x6FB7E8).addAttributeModifier(Attributes.ARMOR, Bathhouse.id("effect.tempering"),
                    2.0, AttributeModifier.Operation.ADD_VALUE));
    public static final DeferredHolder<MobEffect, MobEffect> HARDINESS = EFFECTS.register("hardiness",
            () -> new BathEffect(0x4C8FC0).addAttributeModifier(Attributes.ARMOR_TOUGHNESS, Bathhouse.id("effect.hardiness"),
                    1.0, AttributeModifier.Operation.ADD_VALUE));
    public static final DeferredHolder<MobEffect, MobEffect> FROSTPROOF = EFFECTS.register("frostproof",
            () -> new FrostproofEffect(0xBFE6FF));

    // Hot plunges
    public static final DeferredHolder<MobEffect, MobEffect> BLISS = EFFECTS.register("bliss",
            () -> new BathEffect(0xD98FB3).addAttributeModifier(Attributes.LUCK, Bathhouse.id("effect.bliss"),
                    1.0, AttributeModifier.Operation.ADD_VALUE));
    public static final DeferredHolder<MobEffect, MobEffect> SUPPLENESS = EFFECTS.register("suppleness",
            () -> new BathEffect(0xE3A6C6).addAttributeModifier(Attributes.SAFE_FALL_DISTANCE, Bathhouse.id("effect.suppleness"),
                    2.0, AttributeModifier.Operation.ADD_VALUE));
    public static final DeferredHolder<MobEffect, MobEffect> FEATHERLIGHT = EFFECTS.register("featherlight",
            () -> new BathEffect(0xF4D3E3).addAttributeModifier(Attributes.FALL_DAMAGE_MULTIPLIER, Bathhouse.id("effect.featherlight"),
                    -0.25, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

    public static final List<Holder<MobEffect>> MIST_PARTS = List.of(PURITY, CLARITY, CLEANSING);
    public static final List<Holder<MobEffect>> STEAM_PARTS = List.of(FORTITUDE, PROWESS, STEADFASTNESS);
    public static final List<Holder<MobEffect>> EMBER_PARTS = List.of(LIGHTNESS, QUICK_HANDS, KNACK);
    public static final List<Holder<MobEffect>> COLD_PARTS = List.of(TEMPERING, HARDINESS, FROSTPROOF);
    public static final List<Holder<MobEffect>> HOT_PARTS = List.of(BLISS, SUPPLENESS, FEATHERLIGHT);

    private ModEffects() {
    }
}
