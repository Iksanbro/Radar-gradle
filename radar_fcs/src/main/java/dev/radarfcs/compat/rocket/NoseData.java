package dev.radarfcs.compat.rocket;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Настройки носа ракеты на момент пуска (нос уходит в ракету вместе со стеком): вид наведения, типы целей тепловой
 * головки, сеть программируемого носа, цель, захваченная при пуске (автопуск), корабль, с которого пущена ракета.
 */
public record NoseData(Kind kind, int categories, @Nullable UUID network, @Nullable String lock, @Nullable UUID ownShip) {
    public enum Kind { HEAT, NETWORK }
}
