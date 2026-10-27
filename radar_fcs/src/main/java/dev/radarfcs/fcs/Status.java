package dev.radarfcs.fcs;

import net.minecraft.ChatFormatting;

/** Состояние пушки для отчётов, очков инженера и Display Link. */
public enum Status {
    IDLE(ChatFormatting.GRAY),
    AIMING(ChatFormatting.YELLOW),
    FIRE(ChatFormatting.RED),
    HOLD(ChatFormatting.GOLD),
    NO_SOLUTION(ChatFormatting.DARK_GRAY),
    OUT_OF_ARC(ChatFormatting.DARK_GRAY),
    NO_AMMO(ChatFormatting.DARK_RED),
    BLOCKED_FRIENDLY(ChatFormatting.LIGHT_PURPLE),
    BLOCKED_TERRAIN(ChatFormatting.DARK_PURPLE),
    NOT_ASSEMBLED(ChatFormatting.DARK_GRAY),
    MANUAL(ChatFormatting.AQUA),
    RADAR_CONTROLLED(ChatFormatting.AQUA),
    OTHER_FCS(ChatFormatting.AQUA),
    UNSUPPORTED(ChatFormatting.DARK_GRAY),
    NO_RADAR(ChatFormatting.DARK_RED),
    LOST(ChatFormatting.DARK_GRAY),
    /** Наведение требует вращения, а на контроллер наводки оно не подано. */
    NO_POWER(ChatFormatting.GOLD),
    /** Пауза между одиночными выстрелами или очередями (режим огня). */
    PAUSE(ChatFormatting.YELLOW),
    /** У станка нет контроллера горизонтальной или вертикальной наводки. */
    NO_CONTROLLER(ChatFormatting.GOLD);

    public final ChatFormatting color;

    Status(ChatFormatting color) {
        this.color = color;
    }

    public String key() {
        return "radar_fcs.status." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Status byOrdinal(int i) {
        Status[] v = values();
        return i >= 0 && i < v.length ? v[i] : IDLE;
    }
}
