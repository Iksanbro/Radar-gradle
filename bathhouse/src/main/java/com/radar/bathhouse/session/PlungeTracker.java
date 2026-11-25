package com.radar.bathhouse.session;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.block.PlungeBlock;
import com.radar.bathhouse.block.PlungeBlockEntity;
import com.radar.bathhouse.block.PlungeType;
import com.radar.bathhouse.registry.ModDamageTypes;
import com.radar.bathhouse.registry.ModEffects;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.jetbrains.annotations.Nullable;

/**
 * Follows players in and out of plunge tubs and enforces the plunge rules:
 * <ul>
 *     <li>cold: only after the steam room, 10–30 s, no running jumps — rewards Tempering on the way out;</li>
 *     <li>hot: content no higher than the chest, don't duck under, 38–42 °C (45–50 °C for shavings),
 *     don't overstay — rewards Bliss after a good soak.</li>
 * </ul>
 */
public final class PlungeTracker {
    private static final int ABRUPT_WINDOW_TICKS = 10;
    private static final Map<UUID, Dip> DIPS = new HashMap<>();
    private static final Map<UUID, Integer> ABRUPT = new HashMap<>();

    private PlungeTracker() {
    }

    public static final class Dip {
        private BlockPos pos;
        private PlungeType type;
        private int ticks;
        private int soak;
        private double lastTemperature;
        private boolean abrupt;
        private boolean warmed;
        private boolean chilled;
        private boolean rewarded;

        public int seconds() {
            return ticks / 20;
        }

        public boolean rewarded() {
            return rewarded;
        }
    }

    /** Called by the tub when a player lands in it from a height. */
    public static void markAbrupt(ServerPlayer player) {
        ABRUPT.put(player.getUUID(), player.server.getTickCount());
    }

    @Nullable
    public static Dip current(ServerPlayer player) {
        return DIPS.get(player.getUUID());
    }

    /** Called every server tick for every player. */
    public static void tick(ServerPlayer player) {
        PlungeBlockEntity tub = tubAt(player);
        UUID id = player.getUUID();
        Dip dip = DIPS.get(id);
        if (tub == null) {
            if (dip != null) {
                DIPS.remove(id);
                onExit(player, dip);
            }
            return;
        }
        if (dip == null || !dip.pos.equals(tub.getBlockPos())) {
            if (dip != null) {
                onExit(player, dip);
            }
            dip = new Dip();
            dip.pos = tub.getBlockPos();
            dip.type = tub.type();
            dip.lastTemperature = tub.temperature();
            DIPS.put(id, dip);
            onEnter(player, tub, dip);
        }
        if (dip.type.medium() == PlungeType.Medium.WATER && player.isOnFire()) {
            player.clearFire();
        }
        dip.ticks++;
        if (dip.ticks % 20 == 0) {
            onSecond(player, tub, dip);
        }
    }

    @Nullable
    private static PlungeBlockEntity tubAt(ServerPlayer player) {
        if (player.isSpectator()) {
            return null;
        }
        BlockPos feet = player.blockPosition();
        if (!(player.level().getBlockState(feet).getBlock() instanceof PlungeBlock)) {
            return null;
        }
        return player.level().getBlockEntity(feet) instanceof PlungeBlockEntity tub && tub.contentLevel() > 0 ? tub : null;
    }

    private static void onEnter(ServerPlayer player, PlungeBlockEntity tub, Dip dip) {
        Integer abruptAt = ABRUPT.remove(player.getUUID());
        dip.abrupt = player.isSprinting() || (abruptAt != null && player.server.getTickCount() - abruptAt <= ABRUPT_WINDOW_TICKS);
        ServerLevel level = player.serverLevel();
        boolean water = dip.type.medium() == PlungeType.Medium.WATER;
        level.playSound(null, tub.getBlockPos(), water ? SoundEvents.PLAYER_SPLASH : SoundEvents.SAND_STEP,
                SoundSource.PLAYERS, 0.6F, 1.0F);
        if (water) {
            level.sendParticles(ParticleTypes.SPLASH, player.getX(), tub.getBlockPos().getY() + 0.9, player.getZ(), 12, 0.3, 0.05, 0.3, 0.1);
        }

        if (dip.type == PlungeType.COLD) {
            dip.warmed = BathSessionTracker.isWarm(player);
            if (!dip.warmed) {
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

    private static void onSecond(ServerPlayer player, PlungeBlockEntity tub, Dip dip) {
        double temperature = tub.temperature();
        dip.lastTemperature = temperature;
        int seconds = dip.seconds();
        PlungeType type = dip.type;

        if (type == PlungeType.COLD) {
            int max = BathConfig.COLD_DIP_MAX_SECONDS.get();
            int min = BathConfig.COLD_DIP_MIN_SECONDS.get();
            if (seconds > max && !dip.chilled) {
                dip.chilled = true;
                player.displayClientMessage(Component.translatable("bathhouse.plunge.chilled").withStyle(ChatFormatting.AQUA), false);
            }
            if (dip.chilled || temperature < type.minTemperature()) {
                player.setTicksFrozen(Math.min(player.getTicksFrozen() + (dip.chilled ? 50 : 20), player.getTicksRequiredToFreeze() + 40));
                if (dip.chilled) {
                    player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 0));
                }
            }
            ChatFormatting timeColor = seconds < min ? ChatFormatting.YELLOW : seconds <= max ? ChatFormatting.GREEN : ChatFormatting.RED;
            player.displayClientMessage(header(type, temperature)
                    .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("bathhouse.plunge.dip_time", seconds, min, max).withStyle(timeColor)), true);
            return;
        }

        int soakNeeded = BathConfig.HOT_SOAK_SECONDS.get();
        int soakMax = BathConfig.HOT_SOAK_MAX_SECONDS.get();
        MutableComponent line = header(type, temperature).append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
        if (seconds > soakMax) {
            // Long hot soaks overload the circulation.
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0));
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 0));
            line.append(Component.translatable("bathhouse.plunge.overload").withStyle(ChatFormatting.RED));
        } else if (dip.rewarded) {
            line.append(Component.translatable("bathhouse.plunge.soaked", seconds, soakMax).withStyle(ChatFormatting.GREEN));
        } else if (tub.contentLevel() > PlungeType.CHEST_LEVEL) {
            line.append(Component.translatable(type.medium() == PlungeType.Medium.WATER
                    ? "bathhouse.plunge.too_deep_water" : "bathhouse.plunge.too_deep_sawdust").withStyle(ChatFormatting.YELLOW));
        } else if (player.isShiftKeyDown()) {
            line.append(Component.translatable("bathhouse.plunge.ducking").withStyle(ChatFormatting.YELLOW));
        } else if (!type.inRange(temperature)) {
            line.append(Component.translatable(temperature < type.minTemperature()
                    ? "bathhouse.plunge.too_cold" : "bathhouse.plunge.too_hot").withStyle(ChatFormatting.YELLOW));
            if (temperature > type.maxTemperature() + 8) {
                player.hurt(player.damageSources().source(ModDamageTypes.OVERHEAT), 1.0F);
            }
        } else {
            dip.soak++;
            if (dip.soak >= soakNeeded) {
                dip.rewarded = true;
                int amplifier = type == PlungeType.SAWDUST ? 1 : 0;
                double multiplier = tub.hasHerbs() ? 1.5 : 1.0;
                tub.setHerbs(false);
                BathRewards.grantEffect(player, ModEffects.BLISS, amplifier, multiplier,
                        Component.translatable(type.translationKey()).withStyle(ChatFormatting.GOLD));
                line.append(Component.translatable("bathhouse.plunge.soaked", seconds, soakMax).withStyle(ChatFormatting.GREEN));
            } else {
                line.append(Component.translatable("bathhouse.plunge.soak", dip.soak, soakNeeded).withStyle(ChatFormatting.GREEN));
            }
        }
        player.displayClientMessage(line, true);
    }

    private static MutableComponent header(PlungeType type, double temperature) {
        return Component.translatable(type.translationKey()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("bathhouse.plunge.temperature", Math.round(temperature), type.minTemperature(), type.maxTemperature())
                        .withStyle(type.inRange(temperature) ? ChatFormatting.WHITE : ChatFormatting.YELLOW));
    }

    private static void onExit(ServerPlayer player, Dip dip) {
        if (dip.type != PlungeType.COLD) {
            return;
        }
        int seconds = dip.seconds();
        String outcome;
        if (!dip.warmed) {
            outcome = "bathhouse.plunge.contrast.not_warm";
        } else if (dip.abrupt) {
            outcome = "bathhouse.plunge.contrast.abrupt";
        } else if (dip.chilled) {
            outcome = "bathhouse.plunge.contrast.chilled";
        } else if (seconds < BathConfig.COLD_DIP_MIN_SECONDS.get()) {
            outcome = "bathhouse.plunge.contrast.too_short";
        } else if (!dip.type.inRange(dip.lastTemperature)) {
            outcome = "bathhouse.plunge.contrast.wrong_temperature";
        } else {
            BathSessionTracker.consumeWarmth(player);
            BathRewards.grantEffect(player, ModEffects.TEMPERING, 0, 1.0,
                    Component.translatable("bathhouse.plunge.contrast").withStyle(ChatFormatting.AQUA));
            return;
        }
        player.displayClientMessage(Component.translatable(outcome, seconds).withStyle(ChatFormatting.YELLOW), false);
    }

    public static void forget(ServerPlayer player) {
        DIPS.remove(player.getUUID());
        ABRUPT.remove(player.getUUID());
    }

    public static void clear() {
        DIPS.clear();
        ABRUPT.clear();
    }
}
