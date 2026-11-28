package dev.radarfcs.fcs;

import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.TargetPicker;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.Vec;
import dev.radarfcs.link.LinkKind;
import dev.radarfcs.link.LinkRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * «Выбранная цель сети» (как у моста автопилота): назначенная на мониторе сети (свой радарный - первым, затем
 * Create Radars), иначе цель, которую ведёт или по которой стреляет вычислитель сети, иначе цель, выбранная им для
 * пушек вне их досягаемости, иначе лучшая цель вычислителя по его настройкам (для ракет: они летят дальше пушек).
 * Сведения - из сенсора вычислителя или монитора, который её сопровождает.
 */
public final class NetworkTargets {
    /** Цель: id (UUID сущности или корабля), тип целей вычислителя, позиция, скорость (бл/тик), имя. */
    public record Target(String id, EngageSettings.Category category, Vec pos, Vec vel, String name) {}

    private NetworkTargets() {}

    @Nullable
    public static Target of(ServerLevel level, @Nullable UUID network) {
        return of(level, network, null);
    }

    /**
     * @param from точка пуска (для выбора цели по настройкам вычислителя - «ближайшая» и т. п. от неё); null - от
     *             вычислителя
     */
    @Nullable
    public static Target of(ServerLevel level, @Nullable UUID network, @Nullable Vec from) {
        return of(level, network, from, false);
    }

    /**
     * @param manualOnly только цель, назначенная вручную на мониторе сети (кликом по отметке или лазерным
     *                   целеуказателем; свой монитор или Create Radars), без целей вычислителя
     */
    @Nullable
    public static Target of(ServerLevel level, @Nullable UUID network, @Nullable Vec from, boolean manualOnly) {
        if (network == null) return null;
        long now = level.getGameTime();
        List<FireControlBlockEntity> computers = new ArrayList<>();
        List<RadarMonitorBlockEntity> monitors = new ArrayList<>();
        List<BlockEntity> crMonitors = new ArrayList<>();
        for (BlockPos p : LinkRegistry.members(level, network)) {
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            LinkKind k = LinkKind.of(be);
            if (k == null) continue;
            switch (k) {
                case FCS -> { if (be instanceof FireControlBlockEntity f) computers.add(f); }
                case MONITOR -> { if (be instanceof RadarMonitorBlockEntity m) monitors.add(m); }
                case CR_MONITOR -> crMonitors.add(be);
                default -> { }
            }
        }
        String id = null;
        for (RadarMonitorBlockEntity m : monitors) {
            UUID d = m.localDesignation(now);
            if (d != null) {
                id = d.toString();
                break;
            }
        }
        if (id == null) for (BlockEntity be : crMonitors) {
            id = RadarLink.monitorSelection(be);
            if (id != null) break;
        }
        if (id == null && manualOnly) return null;
        if (id == null) for (FireControlBlockEntity f : computers) {
            id = f.currentDesignation();
            if (id != null) break;
            for (MountState m : f.mountStates()) {
                if (m.targetId != null && (m.status == Status.FIRE || m.status == Status.AIMING)) {
                    id = m.targetId;
                    break;
                }
            }
            if (id != null) break;
        }
        // цель, выбранная вычислителем для пушек, но вне их досягаемости (ракета летит дальше пушек)
        if (id == null) for (FireControlBlockEntity f : computers) {
            for (MountState m : f.mountStates()) {
                if (m.targetId != null) {
                    id = m.targetId;
                    break;
                }
            }
            if (id != null) break;
        }
        // пушки цель не взяли (не достают, пушек нет): лучшая цель вычислителя по его настройкам - типы и белый
        // список, уровни приоритета и режим приоритета (от точки пуска)
        if (id == null) for (FireControlBlockEntity f : computers) {
            EngageSettings s = f.effectiveSettings();
            List<Track> pool = new ArrayList<>();
            for (Track t : f.sensor().active()) {
                if (t.pos == null || f.isDropped(t.id)) continue;
                if (!TargetLists.matches(s.whitelist, t) && !s.enabled(EngageSettings.Category.of(t))) continue;
                pool.add(t);
            }
            if (pool.isEmpty()) continue;
            BlockPos fp = f.getBlockPos();
            Vec pivot = from != null ? from : new Vec(fp.getX() + 0.5, fp.getY() + 0.5, fp.getZ() + 0.5);
            List<Track> order = TargetPicker.order(pool, pivot, s, new TargetPicker.State(), now, 1.0, null);
            if (!order.isEmpty()) {
                id = order.get(0).id;
                break;
            }
        }
        if (id == null) return null;
        Track t = null;
        for (FireControlBlockEntity f : computers) if ((t = f.sensor().get(id)) != null) break;
        if (t == null) for (RadarMonitorBlockEntity m : monitors) if ((t = m.sensor().get(id)) != null) break;
        if (t == null || t.pos == null) return null;
        return new Target(id, EngageSettings.Category.of(t), t.pos, t.vel == null ? Vec.ZERO : t.vel, t.name == null ? "?" : t.name);
    }
}
