package com.radar.bathhouse.session;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.BathQuality;
import com.radar.bathhouse.plunge.PlungeControllerBlockEntity;
import com.radar.bathhouse.plunge.PlungeKind;
import com.radar.bathhouse.plunge.PlungeRegistry;
import com.radar.bathhouse.registry.ModDamageTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.jetbrains.annotations.Nullable;

/**
 * Follows players in and out of plunges and enforces the rules.
 * <ul>
 *     <li>Cold: only after the steam room; 10–30 s; no running jumps, no ducking the head. On the way out it grants
 *     Tempering parts by level: +1 for a good steam before (level 2+), +1 for icy 4–8 °C water, +1 for a second
 *     steam-and-plunge cycle within 15 minutes.</li>
 *     <li>Hot and sawdust: content no higher than the chest (1.4 blocks above the feet — sit on the bench), head above it,
 *     38–42 °C (45–50 °C), don't overstay. After a good soak it grants Bliss parts by level: +1 herbs, +1 steady ideal
 *     temperature, +1 insulated (wood or composite) basin, +1 for shavings.</li>
 * </ul>
 */
public final class PlungeTracker {
    /** Water above this height (blocks above the feet) covers the heart. */
    public static final double CHEST_DEPTH = 1.4;
    /** Falling this far into a plunge is jumping in. */
    public static final float ABRUPT_FALL = 1.5F;
    private static final int CYCLE_WINDOW_TICKS = 15 * 60 * 20;

    private static final Map<UUID, Dip> DIPS = new HashMap<>();
    private static final Map<UUID, Float> FALL = new HashMap<>();
    private static final Map<UUID, Integer> LAST_CONTRAST = new HashMap<>();

    private PlungeTracker() {
    }

    public static final class Dip {
        private BlockPos plunge;
        private PlungeKind kind;
        private int ticks;
        private int soak;
        private int idealSoak;
        private double lastTemperature;
        private boolean abrupt;
        private boolean headUnder;
        @Nullable
        private BathSessionTracker.Warmth warmth;
        private boolean chilled;
        private boolean rewarded;

        public int seconds() {
            return ticks / 20;
        }

        public boolean rewarded() {
            return rewarded;
        }
    }

    /** Remembers how far the player had fallen before this tick (entering water resets it). */
    public static void beforeTick(ServerPlayer player) {
        if (player.fallDistance > 0) {
            FALL.put(player.getUUID(), player.fallDistance);
        } else {
            FALL.remove(player.getUUID());
        }
    }

    @Nullable
    public static Dip current(ServerPlayer player) {
        return DIPS.get(player.getUUID());
    }

    /** Called every server tick for every player. */
    public static void tick(ServerPlayer player) {
        UUID id = player.getUUID();
        Dip dip = DIPS.get(id);
        PlungeControllerBlockEntity plunge = player.isSpectator() ? null : PlungeRegistry.find(player);
        if (plunge == null || plunge.kind() == null) {
            if (dip != null) {
                DIPS.remove(id);
                onExit(player, dip);
            }
            return;
        }
        if (dip == null || !dip.plunge.equals(plunge.getBlockPos()) || dip.kind != plunge.kind()) {
            if (dip != null) {
                onExit(player, dip);
            }
            dip = new Dip();
            dip.plunge = plunge.getBlockPos();
            dip.kind = plunge.kind();
            dip.lastTemperature = plunge.temperature();
            DIPS.put(id, dip);
            onEnter(player, dip);
        }
        if (dip.kind != PlungeKind.SAWDUST && player.isOnFire()) {
            player.clearFire();
        }
        dip.ticks++;
        if (dip.ticks % 20 == 0) {
            onSecond(player, plunge, dip);
        }
    }

    private static void onEnter(ServerPlayer player, Dip dip) {
        Float fall = FALL.remove(player.getUUID());
        dip.abrupt = player.isSprinting() || (fall != null && fall > ABRUPT_FALL);
        player.level().playSound(null, player.blockPosition(),
                dip.kind == PlungeKind.SAWDUST ? SoundEvents.SAND_STEP : SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.6F, 1.0F);
        if (dip.kind == PlungeKind.COLD) {
            dip.warmth = BathSessionTracker.warmth(player);
            if (dip.warmth == null) {
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 1));
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200, 0));
                player.displayClientMessage(Component.translatable("bathhouse.plunge.cold_shock").withStyle(ChatFormatting.RED), false);
            }
            if (dip.abrupt) {
                player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0));
                player.displayClientMessage(Component.translatable("bathhouse.plunge.abrupt").withStyle(ChatFormatting.RED), false);
            }
        }
    }

    private static void onSecond(ServerPlayer player, PlungeControllerBlockEntity plunge, Dip dip) {
        double temperature = plunge.temperature();
        dip.lastTemperature = temperature;
        int seconds = dip.seconds();
        PlungeKind kind = dip.kind;
        MutableComponent line = header(kind, temperature).append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));

        if (kind == PlungeKind.COLD) {
            int max = BathConfig.COLD_DIP_MAX_SECONDS.get();
            int min = BathConfig.COLD_DIP_MIN_SECONDS.get();
            if (plunge.eyeInMedium(player) && !dip.headUnder) {
                dip.headUnder = true;
                player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0));
                player.displayClientMessage(Component.translatable("bathhouse.plunge.head_under").withStyle(ChatFormatting.RED), false);
            }
            if (seconds > max && !dip.chilled) {
                dip.chilled = true;
                player.displayClientMessage(Component.translatable("bathhouse.plunge.chilled").withStyle(ChatFormatting.AQUA), false);
            }
            if (dip.chilled || temperature < kind.minTemperature()) {
                player.setTicksFrozen(Math.min(player.getTicksFrozen() + (dip.chilled ? 50 : 20), player.getTicksRequiredToFreeze() + 40));
                if (dip.chilled) {
                    player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 0));
                }
            }
            ChatFormatting timeColor = seconds < min ? ChatFormatting.YELLOW : seconds <= max ? ChatFormatting.GREEN : ChatFormatting.RED;
            player.displayClientMessage(line.append(Component.translatable("bathhouse.plunge.dip_time", seconds, min, max)
                    .withStyle(timeColor)), true);
            return;
        }

        int soakNeeded = BathConfig.HOT_SOAK_SECONDS.get();
        int soakMax = BathConfig.HOT_SOAK_MAX_SECONDS.get();
        if (seconds > soakMax) {
            // Long hot soaks overload the circulation.
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0));
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 0));
            line.append(Component.translatable("bathhouse.plunge.overload").withStyle(ChatFormatting.RED));
        } else if (dip.rewarded) {
            line.append(Component.translatable("bathhouse.plunge.soaked", seconds, soakMax).withStyle(ChatFormatting.GREEN));
        } else if (plunge.mediumDepth(player) > CHEST_DEPTH) {
            line.append(Component.translatable(kind == PlungeKind.SAWDUST
                    ? "bathhouse.plunge.too_deep_sawdust" : "bathhouse.plunge.too_deep_water").withStyle(ChatFormatting.YELLOW));
        } else if (plunge.eyeInMedium(player) || player.isShiftKeyDown()) {
            line.append(Component.translatable("bathhouse.plunge.ducking").withStyle(ChatFormatting.YELLOW));
        } else if (!kind.inRange(temperature)) {
            line.append(Component.translatable(temperature < kind.minTemperature()
                    ? "bathhouse.plunge.too_cold" : "bathhouse.plunge.too_hot").withStyle(ChatFormatting.YELLOW));
            if (temperature > kind.maxTemperature() + 8) {
                player.hurt(player.damageSources().source(ModDamageTypes.OVERHEAT), 1.0F);
            }
        } else {
            dip.soak++;
            if (kind.ideal(temperature)) {
                dip.idealSoak++;
            }
            if (dip.soak >= soakNeeded) {
                dip.rewarded = true;
                rewardHot(player, plunge, dip);
                line.append(Component.translatable("bathhouse.plunge.soaked", seconds, soakMax).withStyle(ChatFormatting.GREEN));
            } else {
                line.append(Component.translatable("bathhouse.plunge.soak", dip.soak, soakNeeded).withStyle(ChatFormatting.GREEN));
            }
        }
        player.displayClientMessage(line, true);
    }

    private static void rewardHot(ServerPlayer player, PlungeControllerBlockEntity plunge, Dip dip) {
        List<BathQuality.Criterion> criteria = new ArrayList<>();
        criteria.add(new BathQuality.Criterion("herbs", plunge.hasHerbs()));
        criteria.add(new BathQuality.Criterion("ideal_soak", dip.idealSoak >= BathQuality.STEADY_SHARE * dip.soak));
        criteria.add(new BathQuality.Criterion("insulated", plunge.scan() != null && plunge.scan().insulatedFraction() >= BathQuality.FINE_FINISH));
        if (dip.kind == PlungeKind.SAWDUST) {
            criteria.add(new BathQuality.Criterion("sawdust", true));
        }
        plunge.setHerbs(false);
        BathRewards.grantPlunge(player, dip.kind.effects(), plungeLevel(criteria), 1.0,
                Component.translatable(dip.kind.translationKey()).withStyle(ChatFormatting.GOLD), criteria);
    }

    /** Plunges are a supplement: one point gives level 2, two points give level 3. */
    public static int plungeLevel(List<BathQuality.Criterion> criteria) {
        long points = criteria.stream().filter(BathQuality.Criterion::met).count();
        return (int) Math.min(3, 1 + points);
    }

    private static MutableComponent header(PlungeKind kind, double temperature) {
        return Component.translatable(kind.translationKey()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("bathhouse.plunge.temperature", Math.round(temperature), kind.minTemperature(), kind.maxTemperature())
                        .withStyle(kind.inRange(temperature) ? ChatFormatting.WHITE : ChatFormatting.YELLOW));
    }

    private static void onExit(ServerPlayer player, Dip dip) {
        if (dip.kind != PlungeKind.COLD) {
            return;
        }
        int seconds = dip.seconds();
        String outcome;
        if (dip.warmth == null) {
            outcome = "bathhouse.plunge.contrast.not_warm";
        } else if (dip.abrupt) {
            outcome = "bathhouse.plunge.contrast.abrupt";
        } else if (dip.headUnder) {
            outcome = "bathhouse.plunge.contrast.head_under";
        } else if (dip.chilled) {
            outcome = "bathhouse.plunge.contrast.chilled";
        } else if (seconds < BathConfig.COLD_DIP_MIN_SECONDS.get()) {
            outcome = "bathhouse.plunge.contrast.too_short";
        } else if (!dip.kind.inRange(dip.lastTemperature)) {
            outcome = "bathhouse.plunge.contrast.wrong_temperature";
        } else {
            int now = player.server.getTickCount();
            Integer last = LAST_CONTRAST.get(player.getUUID());
            List<BathQuality.Criterion> criteria = new ArrayList<>();
            criteria.add(new BathQuality.Criterion("warm_quality", dip.warmth.level() >= 2));
            criteria.add(new BathQuality.Criterion("icy", dip.kind.ideal(dip.lastTemperature)));
            criteria.add(new BathQuality.Criterion("cycle", last != null && now - last <= CYCLE_WINDOW_TICKS));
            LAST_CONTRAST.put(player.getUUID(), now);
            BathSessionTracker.consumeWarmth(player);
            BathRewards.grantPlunge(player, dip.kind.effects(), plungeLevel(criteria), 1.0,
                    Component.translatable("bathhouse.plunge.contrast").withStyle(ChatFormatting.AQUA), criteria);
            return;
        }
        player.displayClientMessage(Component.translatable(outcome, seconds).withStyle(ChatFormatting.YELLOW), false);
    }

    public static void forget(ServerPlayer player) {
        DIPS.remove(player.getUUID());
        FALL.remove(player.getUUID());
    }

    public static void clear() {
        DIPS.clear();
        FALL.clear();
        LAST_CONTRAST.clear();
    }
}
