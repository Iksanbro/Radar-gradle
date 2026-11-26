package com.radar.bathhouse.climate;

import com.radar.bathhouse.registry.ModEffects;
import com.radar.bathhouse.stove.ChamberType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

/**
 * The three kinds of bathhouse.
 * <ul>
 *     <li>MIST ("Туманница", hammam-like) — stone steam hall: 35–50 °C, 90–100 %, steam generator or boiler stove. Only white.</li>
 *     <li>STEAM ("Парильня", banya-like) — wooden steam lodge: 60–80 °C, 40–70 %, wood stove with a closed (inner) heater.</li>
 *     <li>EMBER ("Калильня", sauna-like) — wooden dry-heat room: 80–110 °C, 5–15 %, stove with an open (outer) heater.</li>
 * </ul>
 * Lodges and ember rooms can be heated white, gray or black. Each type's buff is split into three parts that the
 * steam level (1–3) grants cumulatively; steaming too long in a hot but wrong bath gives the parts inverted
 * at half strength and half duration.
 */
public enum BathType {
    MIST("mist", 35, 50, 90, 100, WallMaterial.STONE, Equipment.STEAM_SOURCE,
            EnumSet.of(SmokeVariant.WHITE), ModEffects.MIST_PARTS, ModEffects.MIST_PENALTIES, ChatFormatting.AQUA),
    STEAM("steam", 60, 80, 40, 70, WallMaterial.WOOD, Equipment.CLOSED_HEATER_STOVE,
            EnumSet.allOf(SmokeVariant.class), ModEffects.STEAM_PARTS, ModEffects.STEAM_PENALTIES, ChatFormatting.GOLD),
    EMBER("ember", 80, 110, 5, 15, WallMaterial.WOOD, Equipment.OPEN_HEATER_STOVE,
            EnumSet.allOf(SmokeVariant.class), ModEffects.EMBER_PARTS, ModEffects.EMBER_PENALTIES, ChatFormatting.RED);

    /** Minimum share of the required wall material. */
    public static final double REQUIRED_WALL_FRACTION = 0.5;
    /** The "ideal" band is the range shrunk by this share of its width from each side. */
    private static final double IDEAL_MARGIN = 0.2;

    private final String id;
    private final int minTemperature;
    private final int maxTemperature;
    private final int minHumidity;
    private final int maxHumidity;
    private final WallMaterial walls;
    private final Equipment equipment;
    private final Set<SmokeVariant> variants;
    private final List<Holder<MobEffect>> effects;
    private final List<Holder<MobEffect>> penalties;
    private final ChatFormatting color;

    BathType(String id, int minTemperature, int maxTemperature, int minHumidity, int maxHumidity, WallMaterial walls,
             Equipment equipment, Set<SmokeVariant> variants, List<Holder<MobEffect>> effects,
             List<Holder<MobEffect>> penalties, ChatFormatting color) {
        this.id = id;
        this.minTemperature = minTemperature;
        this.maxTemperature = maxTemperature;
        this.minHumidity = minHumidity;
        this.maxHumidity = maxHumidity;
        this.walls = walls;
        this.equipment = equipment;
        this.variants = variants;
        this.effects = effects;
        this.penalties = penalties;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "bathhouse.type." + id;
    }

    public int minTemperature() {
        return minTemperature;
    }

    public int maxTemperature() {
        return maxTemperature;
    }

    public int minHumidity() {
        return minHumidity;
    }

    public int maxHumidity() {
        return maxHumidity;
    }

    public WallMaterial walls() {
        return walls;
    }

    public Equipment equipment() {
        return equipment;
    }

    public Set<SmokeVariant> variants() {
        return variants;
    }

    public boolean allows(SmokeVariant variant) {
        return variants.contains(variant);
    }

    /** The three parts of this bath's buff, in the order the steam levels grant them. */
    public List<Holder<MobEffect>> effects() {
        return effects;
    }

    /** The three parts inverted at half strength: what a failed steaming attempt in this kind of bath gives. */
    public List<Holder<MobEffect>> penalties() {
        return penalties;
    }

    public ChatFormatting color() {
        return color;
    }

    public double idealMinTemperature() {
        return minTemperature + (maxTemperature - minTemperature) * IDEAL_MARGIN;
    }

    public double idealMaxTemperature() {
        return maxTemperature - (maxTemperature - minTemperature) * IDEAL_MARGIN;
    }

    public double idealMinHumidity() {
        return minHumidity + (maxHumidity - minHumidity) * IDEAL_MARGIN;
    }

    public double idealMaxHumidity() {
        // Saturated air is fine for a mist hall.
        return maxHumidity >= 100 ? 100 : maxHumidity - (maxHumidity - minHumidity) * IDEAL_MARGIN;
    }

    /** Climate in the comfortable middle of the range. */
    public boolean ideal(double temperature, double humidity) {
        return temperature >= idealMinTemperature() && temperature <= idealMaxTemperature()
                && humidity >= idealMinHumidity() && humidity <= idealMaxHumidity();
    }

    /** Which heat source the bath type needs. */
    public enum Equipment {
        STEAM_SOURCE("steam_source", SourceInfo::steamSource),
        CLOSED_HEATER_STOVE("closed_heater_stove", s -> s.kind() == SourceInfo.Kind.STOVE && s.woodFired() && s.chamber() == ChamberType.CLOSED),
        OPEN_HEATER_STOVE("open_heater_stove", s -> s.kind() == SourceInfo.Kind.STOVE && s.chamber() == ChamberType.OPEN);

        private final String id;
        private final Predicate<SourceInfo> matcher;

        Equipment(String id, Predicate<SourceInfo> matcher) {
            this.id = id;
            this.matcher = matcher;
        }

        public boolean matches(SourceInfo source) {
            return matcher.test(source);
        }

        public String translationKey() {
            return "bathhouse.equipment." + id;
        }
    }
}
