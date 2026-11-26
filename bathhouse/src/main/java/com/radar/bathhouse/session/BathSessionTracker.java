package com.radar.bathhouse.session;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.block.ApplianceBlock;
import com.radar.bathhouse.block.ApplianceType;
import com.radar.bathhouse.block.BathGaugeBlockEntity;
import com.radar.bathhouse.climate.BathAssessment;
import com.radar.bathhouse.climate.BathClassifier;
import com.radar.bathhouse.climate.BathQuality;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.climate.SmokeVariant;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks each player's bath session: progress, and the "quality of steaming" that together with the bath's own
 * characteristics decides the steam level (1–3). Server-side only.
 */
public final class BathSessionTracker {
    /** A session survives this long without a visit (stepping out for a breath of air is fine, but counts as a break). */
    private static final int SESSION_TIMEOUT_TICKS = 20 * 20;
    private static final int ACTIVE_WINDOW_TICKS = 40;
    /** Broom / mitt uses for the ritual criterion. */
    public static final int RITUAL_TOOL_USES = 3;
    public static final int RITUAL_MITT_USES = 2;
    /** Water throws on the stones (löyly) for the ember room ritual. */
    public static final int RITUAL_THROWS = 2;
    public static final double OVERHEAT_TEMPERATURE = BathClassifier.DANGER_TEMPERATURE;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Warmth> WARMED = new HashMap<>();

    private BathSessionTracker() {
    }

    /** Last time a player was in a ready steam lodge or ember room, and the steam level they were heading for. */
    public record Warmth(int tick, int level) {
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
        private int seconds;
        private int idealSeconds;
        private int toolUses;
        private int steamThrows;
        private int loungerSeconds;
        private boolean interrupted;
        /** Seconds spent in a hot but wrong bath, and which bath it was trying to be. */
        private int failSeconds;
        @Nullable
        private BathType failType;

        private void reset() {
            failSeconds = 0;
            failType = null;
            progress = 0;
            seconds = 0;
            idealSeconds = 0;
            toolUses = 0;
            steamThrows = 0;
            loungerSeconds = 0;
            interrupted = false;
        }

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

        public int failSeconds() {
            return failSeconds;
        }

        /** Session criteria: steady ideal climate, ritual, no breaks. */
        public List<BathQuality.Criterion> criteria() {
            List<BathQuality.Criterion> list = new ArrayList<>();
            list.add(new BathQuality.Criterion("steady", seconds > 0 && idealSeconds >= BathQuality.STEADY_SHARE * seconds));
            boolean ritual = type == null ? false : switch (type) {
                case STEAM -> toolUses >= RITUAL_TOOL_USES;
                case EMBER -> steamThrows >= RITUAL_THROWS;
                case MIST -> toolUses >= RITUAL_MITT_USES || (seconds > 0 && loungerSeconds * 2 >= seconds);
            };
            list.add(new BathQuality.Criterion("ritual." + (type == null ? "steam" : type.id()), ritual));
            list.add(new BathQuality.Criterion("unbroken", !interrupted));
            return list;
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
            session.reset();
        } else if (session.progress > 0 && now - session.lastSeen > ACTIVE_WINDOW_TICKS) {
            session.interrupted = true; // stepped out
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
            session.failSeconds = 0;
            BathType type = assessment.type();
            session.seconds++;
            if (type.ideal(gauge.temperature(), gauge.humidity())) {
                session.idealSeconds++;
            }
            session.progress += 1.0;
            if (type == BathType.MIST && onWarmLounger(player)) {
                session.loungerSeconds++;
                session.progress += 1.0; // warming up on hot marble
            }
            int level = projectedLevel(session, gauge, type, assessment.variant());
            if (type == BathType.STEAM || type == BathType.EMBER) {
                WARMED.put(player.getUUID(), new Warmth(now, level));
            }
            if (session.progress >= required) {
                List<BathQuality.Criterion> criteria = allCriteria(session, gauge, type);
                BathRewards.grantBath(player, type, assessment.variant(), level, criteria);
                session.reset();
            } else {
                player.displayClientMessage(readyLine(assessment, gauge, session.progress, required, level), true);
            }
        } else {
            if (session.progress > 0) {
                session.interrupted = true;
            }
            trackFailure(player, session, assessment, gauge);
            if (gauge.scan() != null && !gauge.scan().sources().isEmpty()) {
                // Only nag in rooms that are actually set up as a bath.
                player.displayClientMessage(notReadyLine(assessment, gauge, session.failSeconds), true);
            }
        }
        applyHazards(player, gauge);
    }

    /**
     * A failed steaming attempt: the room is closed, heated by something, and at least as hot as the bath it is
     * closest to — but something is wrong with it. Heating up (still too cold) and airing out (door open) don't count.
     */
    public static boolean isFailedAttempt(BathAssessment assessment, BathGaugeBlockEntity gauge) {
        BathType closest = assessment.closest();
        return !assessment.ready() && closest != null && !gauge.leaky() && gauge.scan() != null
                && !gauge.scan().duplicateGauge() && !gauge.scan().sources().isEmpty()
                && gauge.temperature() >= closest.minTemperature();
    }

    private static void trackFailure(ServerPlayer player, Session session, BathAssessment assessment, BathGaugeBlockEntity gauge) {
        if (!BathConfig.FAILED_STEAM_DEBUFFS.get() || !isFailedAttempt(assessment, gauge)) {
            session.failSeconds = 0;
            return;
        }
        BathType closest = assessment.closest();
        if (session.failType != closest) {
            session.failType = closest;
            session.failSeconds = 0;
        }
        session.failSeconds++;
        if (session.failSeconds >= BathConfig.FAILED_STEAM_SECONDS.get()) {
            session.failSeconds = 0;
            BathRewards.penalize(player, closest, assessment.problems());
        }
    }

    private static List<BathQuality.Criterion> allCriteria(Session session, BathGaugeBlockEntity gauge, BathType type) {
        List<BathQuality.Criterion> criteria = new ArrayList<>(gauge.bathCriteria(type));
        criteria.addAll(session.criteria());
        return criteria;
    }

    private static int projectedLevel(Session session, BathGaugeBlockEntity gauge, BathType type, SmokeVariant variant) {
        return BathQuality.level(BathQuality.points(allCriteria(session, gauge, type), variant));
    }

    private static boolean onWarmLounger(ServerPlayer player) {
        BlockState below = player.level().getBlockState(player.getOnPos());
        return below.getBlock() instanceof ApplianceBlock block && block.type() == ApplianceType.WARM_LOUNGER
                && below.getValue(ApplianceBlock.LIT);
    }

    /** Whether the player has steamed in a ready steam lodge or ember room recently; null if not. */
    @Nullable
    public static Warmth warmth(ServerPlayer player) {
        Warmth warmth = WARMED.get(player.getUUID());
        if (warmth == null || player.server.getTickCount() - warmth.tick() > BathConfig.WARM_WINDOW_SECONDS.get() * 20) {
            return null;
        }
        return warmth;
    }

    public static boolean isWarm(ServerPlayer player) {
        return warmth(player) != null;
    }

    /** A contrast dip uses up the warmth: steam again before the next one. */
    public static void consumeWarmth(ServerPlayer player) {
        WARMED.remove(player.getUUID());
    }

    private static Component readyLine(BathAssessment assessment, BathGaugeBlockEntity gauge, double progress, int required, int level) {
        BathType type = assessment.type();
        SmokeVariant variant = assessment.variant();
        return Component.translatable(type.translationKey()).withStyle(type.color())
                .append(Component.literal(" "))
                .append(Component.translatable(variant.translationKey()).withStyle(variant.color()))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(climate(gauge))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("bathhouse.actionbar.progress", (int) progress, required).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(BathQuality.levelName(level).copy().withStyle(ChatFormatting.GOLD));
    }

    private static Component notReadyLine(BathAssessment assessment, BathGaugeBlockEntity gauge, int failSeconds) {
        MutableComponent line = Component.translatable("bathhouse.actionbar.not_ready").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(climate(gauge));
        if (failSeconds > 0) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("bathhouse.actionbar.failing", failSeconds, BathConfig.FAILED_STEAM_SECONDS.get())
                            .withStyle(ChatFormatting.RED));
        }
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

    /** Broom or mitt: speeds up the session and counts towards the ritual. */
    public static void recordTool(ServerPlayer player, double seconds) {
        Session session = active(player);
        if (session != null && session.ready) {
            session.toolUses++;
            session.progress = Math.min(session.progress + seconds, BathConfig.SESSION_SECONDS.get() - 1);
        }
    }

    /** Water thrown on hot stones (löyly). */
    public static void recordThrow(ServerPlayer player, boolean fullSteam) {
        Session session = active(player);
        if (session != null && session.ready && fullSteam) {
            session.steamThrows++;
        }
    }

    public static void cleanup(int now) {
        SESSIONS.values().removeIf(session -> now - session.lastSeen > SESSION_TIMEOUT_TICKS);
        WARMED.values().removeIf(warmth -> now - warmth.tick() > 20 * 60 * 20);
    }

    public static void clear() {
        SESSIONS.clear();
        WARMED.clear();
    }
}
