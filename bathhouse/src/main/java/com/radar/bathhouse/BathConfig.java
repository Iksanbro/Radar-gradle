package com.radar.bathhouse;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side (per world) tuning. */
public final class BathConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SESSION_SECONDS;
    public static final ModConfigSpec.IntValue BUFF_DURATION_TICKS;
    public static final ModConfigSpec.DoubleValue GRAY_DURATION_MULTIPLIER;
    public static final ModConfigSpec.IntValue BLACK_AMPLIFIER_BONUS;
    public static final ModConfigSpec.IntValue MAX_ROOM_VOLUME;
    public static final ModConfigSpec.IntValue MIN_ROOM_VOLUME;
    public static final ModConfigSpec.DoubleValue SIMULATION_SPEED;
    public static final ModConfigSpec.BooleanValue OVERHEAT_DAMAGE;
    public static final ModConfigSpec.BooleanValue SMOKE_HAZARDS;

    public static final ModConfigSpec.IntValue WARM_WINDOW_SECONDS;
    public static final ModConfigSpec.IntValue COLD_DIP_MIN_SECONDS;
    public static final ModConfigSpec.IntValue COLD_DIP_MAX_SECONDS;
    public static final ModConfigSpec.IntValue HOT_SOAK_SECONDS;
    public static final ModConfigSpec.IntValue HOT_SOAK_MAX_SECONDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("session");
        SESSION_SECONDS = b.comment("Seconds a player has to spend in a ready bath to receive its buff.")
                .defineInRange("sessionSeconds", 60, 5, 600);
        BUFF_DURATION_TICKS = b.comment("Base buff duration in ticks. 48000 = 40 minutes, the same as Hero of the Village after a raid.")
                .defineInRange("buffDurationTicks", 48000, 200, 1_000_000);
        GRAY_DURATION_MULTIPLIER = b.comment("Duration multiplier for baths heated 'gray' (smoke vented through a smoke vent).")
                .defineInRange("grayDurationMultiplier", 1.5, 1.0, 10.0);
        BLACK_AMPLIFIER_BONUS = b.comment("Extra buff levels for baths heated 'black' (no chimney, no vent; must be aired out).")
                .defineInRange("blackAmplifierBonus", 1, 0, 4);
        b.pop();

        b.push("room");
        MAX_ROOM_VOLUME = b.comment("Largest steam room (air blocks) the gauge will accept.")
                .defineInRange("maxRoomVolume", 300, 8, 4096);
        MIN_ROOM_VOLUME = b.comment("Smallest steam room (air blocks) that counts as a bath.")
                .defineInRange("minRoomVolume", 6, 1, 64);
        SIMULATION_SPEED = b.comment("Multiplier for how fast rooms heat up, cool down, gain and lose humidity and smoke.")
                .defineInRange("simulationSpeed", 1.0, 0.1, 5.0);
        b.pop();

        b.push("hazards");
        OVERHEAT_DAMAGE = b.comment("Players take burn damage in rooms hotter than 120 C.")
                .define("overheatDamage", true);
        SMOKE_HAZARDS = b.comment("Smoky rooms (carbon monoxide) cause nausea, weakness and poison.")
                .define("smokeHazards", true);
        b.pop();

        b.push("plunges");
        WARM_WINDOW_SECONDS = b.comment("How long after leaving a ready steam lodge or ember room a player still counts as warmed up for a cold plunge.")
                .defineInRange("warmWindowSeconds", 120, 10, 1200);
        COLD_DIP_MIN_SECONDS = b.comment("Shortest cold dip that counts as contrast.")
                .defineInRange("coldDipMinSeconds", 10, 1, 120);
        COLD_DIP_MAX_SECONDS = b.comment("Longer than this in the cold plunge and the player gets chilled instead.")
                .defineInRange("coldDipMaxSeconds", 30, 2, 300);
        HOT_SOAK_SECONDS = b.comment("Seconds of soaking at the right temperature needed for the hot plunge buff.")
                .defineInRange("hotSoakSeconds", 45, 5, 1200);
        HOT_SOAK_MAX_SECONDS = b.comment("After this many seconds in a hot plunge the heart is overloaded (nausea, weakness).")
                .defineInRange("hotSoakMaxSeconds", 150, 10, 3600);
        b.pop();

        SPEC = b.build();
    }

    private BathConfig() {
    }
}
