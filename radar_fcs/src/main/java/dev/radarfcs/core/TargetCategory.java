package dev.radarfcs.core;

/** Категория цели для фильтров монитора, наследования защиты транспондера и иконок. */
public enum TargetCategory {
    PLAYERS, HOSTILE, PASSIVE, SHIPS, CONTRAPTIONS, PROJECTILES, OTHER;

    public final int bit = 1 << ordinal();
    public static final int ALL = (1 << values().length) - 1;

    public static TargetCategory of(Track.Kind kind, boolean hostile) {
        return switch (kind) {
            case PLAYER -> PLAYERS;
            case MOB -> hostile ? HOSTILE : PASSIVE;
            case SHIP -> SHIPS;
            case CONTRAPTION -> CONTRAPTIONS;
            case PROJECTILE -> PROJECTILES;
            case EXTERNAL -> OTHER;
        };
    }
}
