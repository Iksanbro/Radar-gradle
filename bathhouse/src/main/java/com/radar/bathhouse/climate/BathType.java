package com.radar.bathhouse.climate;

import com.radar.bathhouse.block.ApplianceType;
import com.radar.bathhouse.registry.ModEffects;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

/**
 * The three kinds of bathhouse.
 * <ul>
 *     <li>MIST ("Туманница", hammam-like) — a stone steam hall: 35–50 °C, 90–100 %, steam generator. Only white.</li>
 *     <li>STEAM ("Парильня", banya-like) — a wooden steam lodge: 60–80 °C, 40–70 %, wood-fired stone heater.</li>
 *     <li>EMBER ("Калильня", sauna-like) — a wooden dry-heat room: 80–110 °C, 5–15 %, any stone heater.</li>
 * </ul>
 * Wood-fired lodges and ember rooms can be heated white, gray or black.
 * <ul>
 * </ul>
 */
public enum BathType {
    MIST("mist", 35, 50, 90, 100, WallMaterial.STONE, Equipment.STEAM_SOURCE,
            EnumSet.of(SmokeVariant.WHITE), ModEffects.PURITY, ChatFormatting.AQUA),
    STEAM("steam", 60, 80, 40, 70, WallMaterial.WOOD, Equipment.FIRED_STONE_HEATER,
            EnumSet.allOf(SmokeVariant.class), ModEffects.FORTITUDE, ChatFormatting.GOLD),
    EMBER("ember", 80, 110, 5, 15, WallMaterial.WOOD, Equipment.STONE_HEATER,
            EnumSet.allOf(SmokeVariant.class), ModEffects.LIGHTNESS, ChatFormatting.RED);

    /** Minimum share of the required wall material. */
    public static final double REQUIRED_WALL_FRACTION = 0.5;

    private final String id;
    private final int minTemperature;
    private final int maxTemperature;
    private final int minHumidity;
    private final int maxHumidity;
    private final WallMaterial walls;
    private final Equipment equipment;
    private final Set<SmokeVariant> variants;
    private final Holder<MobEffect> effect;
    private final ChatFormatting color;

    BathType(String id, int minTemperature, int maxTemperature, int minHumidity, int maxHumidity, WallMaterial walls,
             Equipment equipment, Set<SmokeVariant> variants, Holder<MobEffect> effect, ChatFormatting color) {
        this.id = id;
        this.minTemperature = minTemperature;
        this.maxTemperature = maxTemperature;
        this.minHumidity = minHumidity;
        this.maxHumidity = maxHumidity;
        this.walls = walls;
        this.equipment = equipment;
        this.variants = variants;
        this.effect = effect;
        this.color = color;
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

    public Holder<MobEffect> effect() {
        return effect;
    }

    public ChatFormatting color() {
        return color;
    }

    /** Which appliance the bath type needs. */
    public enum Equipment {
        STEAM_SOURCE("steam_source", t -> t == ApplianceType.STEAM_GENERATOR),
        FIRED_STONE_HEATER("fired_stone_heater", t -> t.isFueled() && t.hasStones()),
        STONE_HEATER("stone_heater", ApplianceType::hasStones);

        private final String id;
        private final Predicate<ApplianceType> matcher;

        Equipment(String id, Predicate<ApplianceType> matcher) {
            this.id = id;
            this.matcher = matcher;
        }

        public boolean matches(ApplianceType type) {
            return matcher.test(type);
        }

        public String translationKey() {
            return "bathhouse.equipment." + id;
        }
    }
}
