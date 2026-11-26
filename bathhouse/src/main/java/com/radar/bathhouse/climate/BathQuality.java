package com.radar.bathhouse.climate;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.stove.FireboxType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Steam level (1–3) = what the bath is like + how well the player steamed.
 * <p>Bath criteria (from the room and its stove):</p>
 * <ul>
 *     <li>canon — the stove the tradition calls for: a massive wood stove (brick firebox or soapstone cladding) with a
 *     closed heater and an ash pit for the lodge; an open heater (electric, or wood with an ash pit) for the ember room;
 *     a heated marble lounger for the mist hall;</li>
 *     <li>finish — at least 80 % of surfaces in the right material;</li>
 *     <li>stones — heater three quarters full of good stones (lodge, ember room) / 30 % marble surfaces (mist hall).</li>
 * </ul>
 * <p>Session criteria (tracked per player): steady ideal climate for 70 % of the session, the ritual (broom in the
 * lodge, throwing water on the stones in the ember room, scrub mitt or warm lounger in the mist hall), and no breaks.</p>
 * <p>A bath heated black adds a bonus point. Level 2 needs 3 points, level 3 needs 5 (configurable).</p>
 */
public final class BathQuality {
    public static final double FINE_FINISH = 0.8;
    public static final double MARBLE_SHARE = 0.3;
    public static final double STEADY_SHARE = 0.7;

    private BathQuality() {
    }

    public record Criterion(String key, boolean met) {
        public Component label() {
            return Component.translatable("bathhouse.criterion." + key);
        }

        public Component marked() {
            MutableComponent mark = Component.literal(met ? "✔ " : "✘ ").withStyle(met ? ChatFormatting.GREEN : ChatFormatting.RED);
            return mark.append(label().copy().withStyle(met ? ChatFormatting.WHITE : ChatFormatting.GRAY));
        }
    }

    public static List<Criterion> bathCriteria(BathType type, RoomScan scan, Collection<SourceInfo> sources) {
        List<Criterion> criteria = new ArrayList<>();
        criteria.add(new Criterion("canon." + type.id(), canon(type, sources)));
        criteria.add(new Criterion("finish." + type.id(), scan.fraction(type.walls()) >= FINE_FINISH));
        if (type == BathType.MIST) {
            criteria.add(new Criterion("marble", scan.marbleFraction() >= MARBLE_SHARE));
        } else {
            criteria.add(new Criterion("stones", sources.stream().anyMatch(s -> type.equipment().matches(s) && s.goodStones())));
        }
        return criteria;
    }

    private static boolean canon(BathType type, Collection<SourceInfo> sources) {
        return switch (type) {
            case MIST -> sources.stream().anyMatch(s -> s.kind() == SourceInfo.Kind.WARM_LOUNGER && s.running());
            case STEAM -> sources.stream().anyMatch(s -> type.equipment().matches(s) && s.ashPit()
                    && (s.firebox() == FireboxType.BRICK || s.cladding() >= 2));
            case EMBER -> sources.stream().anyMatch(s -> type.equipment().matches(s)
                    && (s.firebox() == FireboxType.ELECTRIC || s.ashPit()));
        };
    }

    public static int points(List<Criterion> criteria, SmokeVariant variant) {
        int points = (int) criteria.stream().filter(Criterion::met).count();
        if (variant == SmokeVariant.BLACK) {
            points += BathConfig.BLACK_BONUS_POINTS.get();
        }
        return points;
    }

    public static int level(int points) {
        if (points >= BathConfig.LEVEL_3_POINTS.get()) {
            return 3;
        }
        return points >= BathConfig.LEVEL_2_POINTS.get() ? 2 : 1;
    }

    public static Component levelName(int level) {
        return Component.translatable("bathhouse.level." + level);
    }

    /** "✔ canon ✘ finish …" on one line. */
    public static Component line(List<Criterion> criteria, SmokeVariant variant) {
        MutableComponent line = Component.empty();
        for (int i = 0; i < criteria.size(); i++) {
            if (i > 0) {
                line.append(Component.literal("  "));
            }
            line.append(criteria.get(i).marked());
        }
        if (variant == SmokeVariant.BLACK) {
            line.append(Component.literal("  ★ ").withStyle(ChatFormatting.GOLD))
                    .append(Component.translatable("bathhouse.criterion.black").withStyle(ChatFormatting.GOLD));
        }
        return line;
    }
}
