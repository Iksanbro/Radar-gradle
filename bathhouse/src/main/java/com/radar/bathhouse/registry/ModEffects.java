package com.radar.bathhouse.registry;

import com.radar.bathhouse.Bathhouse;
import com.radar.bathhouse.effect.BathEffect;
import com.radar.bathhouse.effect.PurityEffect;
import com.radar.bathhouse.effect.TemperingEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, Bathhouse.MOD_ID);

    /** Mist bath: pores cleansed. Slow regeneration and immunity to poison, hunger and nausea. */
    public static final DeferredHolder<MobEffect, MobEffect> PURITY = EFFECTS.register("purity",
            () -> new PurityEffect(0x9FD8E6));

    /** Steam lodge: muscles and joints warmed through. Extra health, a heavier hand, a steadier stance. */
    public static final DeferredHolder<MobEffect, MobEffect> FORTITUDE = EFFECTS.register("fortitude",
            () -> new BathEffect(0xC9622F)
                    .addAttributeModifier(Attributes.MAX_HEALTH, Bathhouse.id("effect.fortitude.max_health"),
                            4.0, AttributeModifier.Operation.ADD_VALUE)
                    .addAttributeModifier(Attributes.ATTACK_DAMAGE, Bathhouse.id("effect.fortitude.attack_damage"),
                            1.0, AttributeModifier.Operation.ADD_VALUE)
                    .addAttributeModifier(Attributes.KNOCKBACK_RESISTANCE, Bathhouse.id("effect.fortitude.knockback_resistance"),
                            0.1, AttributeModifier.Operation.ADD_VALUE));

    /** Ember room: recovered and relieved. Quicker feet, hands and tools. */
    public static final DeferredHolder<MobEffect, MobEffect> LIGHTNESS = EFFECTS.register("lightness",
            () -> new BathEffect(0xE8A33A)
                    .addAttributeModifier(Attributes.MOVEMENT_SPEED, Bathhouse.id("effect.lightness.movement_speed"),
                            0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                    .addAttributeModifier(Attributes.ATTACK_SPEED, Bathhouse.id("effect.lightness.attack_speed"),
                            0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                    .addAttributeModifier(Attributes.BLOCK_BREAK_SPEED, Bathhouse.id("effect.lightness.block_break_speed"),
                            0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

    /** Cold plunge after a hot room: hardened by contrast. Armour, toughness, and the cold no longer bites. */
    public static final DeferredHolder<MobEffect, MobEffect> TEMPERING = EFFECTS.register("tempering",
            () -> new TemperingEffect(0x6FB7E8)
                    .addAttributeModifier(Attributes.ARMOR, Bathhouse.id("effect.tempering.armor"),
                            2.0, AttributeModifier.Operation.ADD_VALUE)
                    .addAttributeModifier(Attributes.ARMOR_TOUGHNESS, Bathhouse.id("effect.tempering.armor_toughness"),
                            1.0, AttributeModifier.Operation.ADD_VALUE));

    /** Hot plunge: deep relaxation. Luck, and falls hurt less. */
    public static final DeferredHolder<MobEffect, MobEffect> BLISS = EFFECTS.register("bliss",
            () -> new BathEffect(0xD98FB3)
                    .addAttributeModifier(Attributes.LUCK, Bathhouse.id("effect.bliss.luck"),
                            1.0, AttributeModifier.Operation.ADD_VALUE)
                    .addAttributeModifier(Attributes.SAFE_FALL_DISTANCE, Bathhouse.id("effect.bliss.safe_fall_distance"),
                            2.0, AttributeModifier.Operation.ADD_VALUE)
                    .addAttributeModifier(Attributes.FALL_DAMAGE_MULTIPLIER, Bathhouse.id("effect.bliss.fall_damage_multiplier"),
                            -0.25, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

    private ModEffects() {
    }
}
