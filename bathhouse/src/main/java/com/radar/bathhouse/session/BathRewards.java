package com.radar.bathhouse.session;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.climate.SmokeVariant;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringUtil;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Buff granted at the end of a bath session. The base duration matches Hero of the Village (40 minutes);
 * a gray bath lasts longer, a black bath is stronger.
 */
public final class BathRewards {
    private BathRewards() {
    }

    public static int duration(SmokeVariant variant) {
        double base = BathConfig.BUFF_DURATION_TICKS.get();
        if (variant == SmokeVariant.GRAY) {
            base *= BathConfig.GRAY_DURATION_MULTIPLIER.get();
        }
        return (int) Math.round(base);
    }

    public static int amplifier(SmokeVariant variant) {
        return variant == SmokeVariant.BLACK ? BathConfig.BLACK_AMPLIFIER_BONUS.get() : 0;
    }

    /** "Fortitude II, 40:00". */
    public static Component describe(BathType type, SmokeVariant variant) {
        int amplifier = amplifier(variant);
        return Component.translatable("bathhouse.reward.effect",
                Component.translatable(type.effect().value().getDescriptionId()),
                Component.translatable("enchantment.level." + (amplifier + 1)),
                StringUtil.formatTickDuration(duration(variant), 20.0F));
    }

    /** Grants a plunge buff: base duration times {@code multiplier}. */
    public static void grantEffect(ServerPlayer player, Holder<MobEffect> effect, int amplifier, double multiplier, Component source) {
        int duration = (int) Math.round(BathConfig.BUFF_DURATION_TICKS.get() * multiplier);
        player.addEffect(new MobEffectInstance(effect, duration, amplifier, false, true, true));
        player.displayClientMessage(Component.translatable("bathhouse.reward.plunge", source,
                        Component.translatable("bathhouse.reward.effect",
                                Component.translatable(effect.value().getDescriptionId()),
                                Component.translatable("enchantment.level." + (amplifier + 1)),
                                StringUtil.formatTickDuration(duration, 20.0F)).withStyle(ChatFormatting.WHITE))
                .withStyle(ChatFormatting.GREEN), false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.3F);
    }

    public static void grant(ServerPlayer player, BathType type, SmokeVariant variant) {
        player.addEffect(new MobEffectInstance(type.effect(), duration(variant), amplifier(variant), false, true, true));
        player.displayClientMessage(Component.translatable("bathhouse.reward",
                        Component.translatable(type.translationKey()).withStyle(type.color()),
                        Component.translatable(variant.translationKey()).withStyle(variant.color()),
                        describe(type, variant).copy().withStyle(ChatFormatting.WHITE))
                .withStyle(ChatFormatting.GREEN), false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.3F);
    }
}
