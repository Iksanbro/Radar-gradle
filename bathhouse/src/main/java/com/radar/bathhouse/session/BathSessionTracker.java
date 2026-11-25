package com.radar.bathhouse.session;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.block.ApplianceBlock;
import com.radar.bathhouse.block.ApplianceType;
import com.radar.bathhouse.block.BathGaugeBlockEntity;
import com.radar.bathhouse.climate.BathAssessment;
import com.radar.bathhouse.climate.BathClassifier;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.climate.SmokeVariant;
import com.radar.bathhouse.registry.ModDamageTypes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Tracks how long each player has been steaming in which bath. Server-side only. */
public final class BathSessionTracker {
    /** A session survives this long without a visit (stepping out for a breath of air is fine). */
    private static final int SESSION_TIMEOUT_TICKS = 20 * 20;
    /** A player counts as "in the bath right now" if visited within this many ticks. */
    private static final int ACTIVE_WINDOW_TICKS = 40;
    public static final double OVERHEAT_TEMPERATURE = BathClassifier.DANGER_TEMPERATURE;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** Last server tick each player spent in a ready steam lodge or ember room (warmed up for a cold plunge). */
    private static final Map<UUID, Integer> WARMED = new HashMap<>();

    private BathSessionTracker() {
    }

    public static final class Session {
        private ResourceKey<Level> dimension;
        private BlockPos gauge;
        @Nullable
        private BathType type;
        private SmokeVariant variant = SmokeVariant.WHITE;
        private double progress;
        private int lastSeen;
        private boolean ready;

        @Nullable
        public BathType type() {
            return type;
        }

        public SmokeVariant variant() {
            return variant;
        }

        public double progress() {
            return progress;
        }

        public boolean ready() {
            return ready;
        }
    }

    /** Called once a second by a gauge for every player inside its room. */
    public static void visit(ServerPlayer player, BathGaugeBlockEntity gauge) {
        int now = player.server.getTickCount();
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
        BathAssessment assessment = gauge.assessment();
        ResourceKey<Level> dimension = player.level().dimension();

        boolean sameRoom = gauge.getBlockPos().equals(session.gauge) && dimension.equals(session.dimension);
        boolean typeChanged = assessment.type() != null && session.type != null && assessment.type() != session.type;
        if (!sameRoom || typeChanged) {
            session.progress = 0;
        }
        session.dimension = dimension;
        session.gauge = gauge.getBlockPos();
        session.lastSeen = now;
        session.variant = assessment.variant();
        session.ready = assessment.ready();
        if (assessment.type() != null) {
            session.type = assessment.type();
        }

        int required = BathConfig.SESSION_SECONDS.get();
        if (assessment.ready()) {
            if (assessment.type() == BathType.STEAM || assessment.type() == BathType.EMBER) {
                WARMED.put(player.getUUID(), now);
            }
            session.progress += 1.0;
            if (assessment.type() == BathType.MIST && onWarmLounger(player)) {
                session.progress += 1.0; // warming up on hot marble
            }
            if (session.progress >= required) {
                session.progress = 0;
                BathRewards.grant(player, assessment.type(), assessment.variant());
            } else {
                player.displayClientMessage(readyLine(assessment, gauge, session.progress, required), true);
            }
        } else if (gauge.scan() != null && !gauge.scan().appliances().isEmpty()) {
            // Only nag in rooms that are actually set up as a bath.
            player.displayClientMessage(notReadyLine(assessment, gauge), true);
        }
        applyHazards(player, gauge);
    }

    private static boolean onWarmLounger(ServerPlayer player) {
        BlockState below = player.level().getBlockState(player.getOnPos());
        return below.getBlock() instanceof ApplianceBlock block && block.type() == ApplianceType.WARM_LOUNGER
                && below.getValue(ApplianceBlock.LIT);
    }

    /** Whether the player has steamed in a ready steam lodge or ember room recently. */
    public static boolean isWarm(ServerPlayer player) {
        Integer last = WARMED.get(player.getUUID());
        return last != null && player.server.getTickCount() - last <= BathConfig.WARM_WINDOW_SECONDS.get() * 20;
    }

    /** A contrast dip uses up the warmth: steam again before the next one. */
    public static void consumeWarmth(ServerPlayer player) {
        WARMED.remove(player.getUUID());
    }

    private static Component readyLine(BathAssessment assessment, BathGaugeBlockEntity gauge, double progress, int required) {
        BathType type = assessment.type();
        SmokeVariant variant = assessment.variant();
        return Component.translatable(type.translationKey()).withStyle(type.color())
                .append(Component.literal(" "))
                .append(Component.translatable(variant.translationKey()).withStyle(variant.color()))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(climate(gauge))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("bathhouse.actionbar.progress", (int) progress, required).withStyle(ChatFormatting.GREEN));
    }

    private static Component notReadyLine(BathAssessment assessment, BathGaugeBlockEntity gauge) {
        MutableComponent line = Component.translatable("bathhouse.actionbar.not_ready").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(climate(gauge));
        if (!assessment.problems().isEmpty()) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(assessment.problems().getFirst().copy().withStyle(ChatFormatting.YELLOW));
        }
        return line;
    }

    private static Component climate(BathGaugeBlockEntity gauge) {
        MutableComponent climate = Component.translatable("bathhouse.climate.short",
                BathClassifier.fmt(gauge.temperature()), BathClassifier.fmt(gauge.humidity())).withStyle(ChatFormatting.WHITE);
        if (gauge.smoke() >= 1.0) {
            climate.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("bathhouse.climate.smoke", BathClassifier.fmt(gauge.smoke())).withStyle(ChatFormatting.DARK_GRAY));
        }
        return climate;
    }

    private static void applyHazards(ServerPlayer player, BathGaugeBlockEntity gauge) {
        if (player.isCreative()) {
            return;
        }
        double temperature = gauge.temperature();
        if (BathConfig.OVERHEAT_DAMAGE.get() && temperature > OVERHEAT_TEMPERATURE) {
            float damage = 1.0F + (float) ((temperature - OVERHEAT_TEMPERATURE) / 20.0);
            player.hurt(player.damageSources().source(ModDamageTypes.OVERHEAT), damage);
        }
        double smoke = gauge.smoke();
        if (BathConfig.SMOKE_HAZARDS.get() && smoke >= 40.0) {
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
            if (smoke >= 60.0) {
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200, 0));
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 0));
            }
            if (smoke >= 85.0) {
                player.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
            }
        }
    }

    /** The session of a player who is in a bath right now, or null. */
    @Nullable
    public static Session active(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || player.server.getTickCount() - session.lastSeen > ACTIVE_WINDOW_TICKS) {
            return null;
        }
        return session;
    }

    /** Speeds up the current session (bath broom). */
    public static void addProgress(ServerPlayer player, double seconds) {
        Session session = active(player);
        if (session != null && session.ready) {
            session.progress = Math.min(session.progress + seconds, BathConfig.SESSION_SECONDS.get() - 1);
        }
    }

    public static void cleanup(int now) {
        SESSIONS.values().removeIf(session -> now - session.lastSeen > SESSION_TIMEOUT_TICKS);
        WARMED.values().removeIf(last -> now - last > 20 * 60 * 20);
    }

    public static void clear() {
        SESSIONS.clear();
        WARMED.clear();
    }
}
