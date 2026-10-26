package dev.radarfcs.radar;

import java.util.Locale;
import net.minecraft.ChatFormatting;

/** Состояние радара для очков инженера и отчётов. */
public enum RadarStatus {
    OK(ChatFormatting.GREEN),
    NO_ROTATION(ChatFormatting.GOLD),
    OVERSTRESSED(ChatFormatting.RED),
    NO_RECEIVER(ChatFormatting.RED),
    BAD_FACING(ChatFormatting.RED),
    DISABLED(ChatFormatting.GRAY);

    public final ChatFormatting color;

    RadarStatus(ChatFormatting color) {
        this.color = color;
    }

    public String key() {
        return "radar_fcs.radar.status." + name().toLowerCase(Locale.ROOT);
    }

    public static RadarStatus byOrdinal(int i) {
        RadarStatus[] v = values();
        return i >= 0 && i < v.length ? v[i] : DISABLED;
    }
}
