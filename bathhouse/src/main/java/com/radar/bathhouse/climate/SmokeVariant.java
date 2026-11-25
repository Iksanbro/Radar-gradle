package com.radar.bathhouse.climate;

import net.minecraft.ChatFormatting;

/**
 * How the smoke of a wood-fired bath leaves the room.
 * <ul>
 *     <li>WHITE: every firebox has its own chimney on top; smoke never touches the room.</li>
 *     <li>GRAY: a firebox without its own chimney, but the room has a flue in its ceiling or wall: smoke passes
 *     through the room on its way out. Mildly smoky while firing, heats fast.</li>
 *     <li>BLACK: no chimney at all; smoke leaves only through the door or a smoke window. Steam only after the
 *     fire has burnt out and the room has been aired.</li>
 * </ul>
 */
public enum SmokeVariant {
    WHITE("white", 100.0, ChatFormatting.WHITE),
    GRAY("gray", 35.0, ChatFormatting.GRAY),
    BLACK("black", 10.0, ChatFormatting.DARK_GRAY);

    private final String id;
    private final double maxSmoke;
    private final ChatFormatting color;

    SmokeVariant(String id, double maxSmoke, ChatFormatting color) {
        this.id = id;
        this.maxSmoke = maxSmoke;
        this.color = color;
    }

    public String translationKey() {
        return "bathhouse.variant." + id;
    }

    /** Highest smoke level (percent) at which the bath is ready. */
    public double maxSmoke() {
        return maxSmoke;
    }

    public ChatFormatting color() {
        return color;
    }
}
