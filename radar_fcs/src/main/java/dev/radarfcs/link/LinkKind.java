package dev.radarfcs.link;

import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.radar.RadarEmitter;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/** Аппаратура, которую соединяет радарный линк. */
public enum LinkKind {
    /** Вычислитель управления огнём. */
    FCS,
    /** Станок CBC (пушка). */
    MOUNT,
    /** Радар этого мода (подшипник, решётка, купол). */
    RADAR,
    /** Радар Create Radars. */
    CR_RADAR,
    /** Радарный монитор (любой блок стены). */
    MONITOR;

    /** Вид аппаратуры в точке или null (не аппаратура, чанк не загружен). */
    @Nullable
    public static LinkKind at(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        return of(level.getBlockEntity(pos));
    }

    @Nullable
    public static LinkKind of(@Nullable BlockEntity be) {
        if (be == null || be.isRemoved()) return null;
        if (be instanceof FireControlBlockEntity) return FCS;
        if (be instanceof RadarMonitorBlockEntity) return MONITOR;
        if (be instanceof RadarEmitter) return RADAR;
        if (CbcMount.of(be) != null) return MOUNT;
        try {
            if (RadarLink.isRadar(be)) return CR_RADAR;
        } catch (Throwable ignored) {
            // конфиг сервера ещё не загружен (клиент в меню) - не радар
        }
        return null;
    }

    /**
     * Что с чем можно связать: вычислитель - со станком, радаром, монитором; монитор - с радаром этого мода
     * (картинку монитора дают только свои радары).
     */
    public static boolean canLink(LinkKind a, LinkKind b) {
        if (a == FCS) return b == MOUNT || b == RADAR || b == CR_RADAR || b == MONITOR;
        if (b == FCS) return canLink(b, a);
        return (a == MONITOR && b == RADAR) || (a == RADAR && b == MONITOR);
    }

    public Component displayName() {
        return Component.translatable("radar_fcs.link.kind." + name().toLowerCase(Locale.ROOT));
    }
}
