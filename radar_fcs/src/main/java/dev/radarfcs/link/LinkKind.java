package dev.radarfcs.link;

import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.RadarLink;
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
    /** Радар Create Radars. */
    RADAR,
    /** Радарный монитор (любой блок стены). */
    MONITOR,
    /** Монитор Create Radars: выбранная на нём цель - ручное наведение для вычислителей сети. */
    CR_MONITOR,
    /** Блок памяти: расширяет списки вычислителей сети. */
    MEMORY;

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
        if (be instanceof dev.radarfcs.block.MemoryBlockEntity) return MEMORY;
        if (CbcMount.of(be) != null) return MOUNT;
        try {
            if (RadarLink.isRadar(be)) return RADAR;
            if (RadarLink.isMonitor(be)) return CR_MONITOR;
        } catch (Throwable ignored) {
            // конфиг сервера ещё не загружен (клиент в меню) - не радар
        }
        return null;
    }

    public Component displayName() {
        return Component.translatable("radar_fcs.link.kind." + name().toLowerCase(Locale.ROOT));
    }
}
