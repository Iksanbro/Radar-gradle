package dev.radarfcs.compat;

import com.happysg.radar.block.controller.id.IDManager;
import com.happysg.radar.block.radar.behavior.IRadar;
import com.happysg.radar.block.radar.track.RadarTrack;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Необязательная связь с Create: Radars (если он установлен). Используются только методы, общие для версий 0.4.9.x и 5.x:
 * IRadar.getTracks/getRange/isRunning/getWorldPos и RadarTrack.getId/getPosition/getVelocity/
 * getScannedTime/getTrackCategory/getEntityType. Любая несовместимость отключает интеграцию без падения игры.
 */
public final class RadarLink {
    private static boolean apiBroken;

    private RadarLink() {}

    /** Цель радара в нейтральном виде. category - имя категории Create Radars (HOSTILE, PLAYER, SABLE...). */
    public record Contact(String id, String category, Vec3 position, Vec3 velocity, long scannedTime, String entityType) {}

    private static void fail(Throwable t) {
        if (!apiBroken) FcsMod.LOG.warn("[radar_fcs] Create Radars API mismatch, falling back to direct sensing: {}", t.toString());
        apiBroken = true;
    }

    public static boolean apiAvailable() { return Mods.CREATE_RADARS && !apiBroken; }

    /** Блок-радар: реализует IRadar или его ID указан в конфиге. */
    public static boolean isRadar(BlockEntity be) {
        if (!Mods.CREATE_RADARS) return false;
        if (!apiBroken) {
            try {
                if (be instanceof IRadar) return true;
            } catch (Throwable t) {
                fail(t);
            }
        }
        String id = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
        return FcsConfig.RADAR_BLOCKS.get().contains(id);
    }

    public static boolean isRunning(BlockEntity be) {
        if (!Mods.CREATE_RADARS) return false;
        if (apiBroken) return true;
        try {
            return !(be instanceof IRadar r) || r.isRunning();
        } catch (Throwable t) {
            fail(t);
            return true;
        }
    }

    public static float range(BlockEntity be) {
        if (!apiAvailable()) return 0;
        try {
            return be instanceof IRadar r ? r.getRange() : 0;
        } catch (Throwable t) {
            fail(t);
            return 0;
        }
    }

    /** Позиция радара, которую он сам сообщает (у радара на корабле - мировая). */
    public static BlockPos worldPos(BlockEntity be) {
        if (apiAvailable()) {
            try {
                if (be instanceof IRadar r && r.getWorldPos() != null) return r.getWorldPos();
            } catch (Throwable t) {
                fail(t);
            }
        }
        return be.getBlockPos();
    }

    /** Цели радара; null, если API недоступно. */
    @Nullable
    public static List<Contact> contacts(BlockEntity be) {
        if (!apiAvailable() || !(be instanceof IRadar)) return null;
        try {
            IRadar r = (IRadar) be;
            Collection<RadarTrack> tracks = r.getTracks();
            List<Contact> out = new ArrayList<>(tracks == null ? 0 : tracks.size());
            if (tracks == null) return out;
            for (RadarTrack t : List.copyOf(tracks)) {
                if (t == null || t.getPosition() == null) continue;
                Vec3 v = t.getVelocity() == null ? Vec3.ZERO : t.getVelocity();
                out.add(new Contact(t.getId(), t.getTrackCategory().name(), t.getPosition(), v,
                        t.getScannedTime(), String.valueOf(t.getEntityType())));
            }
            return out;
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    /**
     * Корабль отмечен идентификационным транспондером Create Radars с секретным ID из списка своих.
     * Пустой список - функция отключена.
     */
    public static boolean shipHasFriendlyRadarId(UUID ship, List<? extends String> friendlyIds) {
        if (friendlyIds.isEmpty() || !apiAvailable()) return false;
        try {
            IDManager.IDRecord rec = IDManager.getIDRecordByShipId(ship);
            return rec != null && friendlyIds.contains(rec.secretID());
        } catch (Throwable t) {
            return false;
        }
    }
}
