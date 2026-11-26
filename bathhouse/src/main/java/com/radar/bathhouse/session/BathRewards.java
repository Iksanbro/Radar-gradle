package com.radar.bathhouse.session;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.BathQuality;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.climate.SmokeVariant;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringUtil;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Buffs are split into three parts; the steam level grants them cumulatively. The base duration matches Hero of the
 * Village (40 minutes); a gray bath lasts 1.5 times longer.
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

    /** "Fortitude → Prowess → Steadfastness (40:00)". */
    public static Component describeParts(BathType type, SmokeVariant variant) {
        return parts(type.effects(), 3, duration(variant), " → ");
    }

    private static Component parts(List<Holder<MobEffect>> effects, int level, int duration, String separator) {
        MutableComponent line = Component.empty();
        for (int i = 0; i < Math.min(level, effects.size()); i++) {
            if (i > 0) {
                line.append(Component.literal(separator).withStyle(ChatFormatting.GRAY));
            }
            line.append(Component.translatable(effects.get(i).value().getDescriptionId()).withStyle(ChatFormatting.WHITE));
        }
        return line.append(Component.literal(" (" + StringUtil.formatTickDuration(duration, 20.0F) + ")").withStyle(ChatFormatting.GRAY));
    }

    /** Applies the first {@code level} parts. */
    public static void apply(ServerPlayer player, List<Holder<MobEffect>> effects, int level, int duration) {
        for (int i = 0; i < Math.min(level, effects.size()); i++) {
            player.addEffect(new MobEffectInstance(effects.get(i), duration, 0, false, true, true));
        }
    }

    public static void grantBath(ServerPlayer player, BathType type, SmokeVariant variant, int level,
                                 List<BathQuality.Criterion> criteria) {
        int duration = duration(variant);
        apply(player, type.effects(), level, duration);
        player.displayClientMessage(Component.translatable("bathhouse.reward",
                        Component.translatable(type.translationKey()).withStyle(type.color()),
                        Component.translatable(variant.translationKey()).withStyle(variant.color()),
                        BathQuality.levelName(level).copy().withStyle(ChatFormatting.GOLD),
                        parts(type.effects(), level, duration, ", "))
                .withStyle(ChatFormatting.GREEN), false);
        player.displayClientMessage(BathQuality.line(criteria, variant), false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F,
                1.0F + 0.15F * level);
    }

    public static void grantPlunge(ServerPlayer player, List<Holder<MobEffect>> effects, int level, double multiplier,
                                   Component source, List<BathQuality.Criterion> criteria) {
        int duration = (int) Math.round(BathConfig.BUFF_DURATION_TICKS.get() * multiplier);
        apply(player, effects, level, duration);
        player.displayClientMessage(Component.translatable("bathhouse.reward.plunge", source,
                        BathQuality.levelName(level).copy().withStyle(ChatFormatting.GOLD),
                        parts(effects, level, duration, ", "))
                .withStyle(ChatFormatting.GREEN), false);
        player.displayClientMessage(BathQuality.line(criteria, SmokeVariant.WHITE), false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F,
                1.0F + 0.15F * level);
    }
}
