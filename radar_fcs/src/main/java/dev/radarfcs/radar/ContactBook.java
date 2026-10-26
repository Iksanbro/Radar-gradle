package dev.radarfcs.radar;

import dev.radarfcs.core.Track;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Отметки одного радара: обновляются при обнаружении и гаснут, если цель долго не видна. */
public final class ContactBook {
    private final Map<UUID, RadarContact> map = new HashMap<>();
    /** Кэш прямой видимости: id -> {тик проверки, 1 = видно}. */
    private final Map<UUID, long[]> los = new HashMap<>();

    public RadarContact see(UUID id, Track.Kind kind, String name, Vec3 pos, @Nullable Vec3 knownVel, double size,
                            boolean hostile, @Nullable Entity entity, long now, int ttl) {
        RadarContact c = map.get(id);
        if (c == null || c.kind != kind) {
            c = new RadarContact(id, kind);
            c.firstSeen = now;
            c.lastSeen = now;
            c.pos = pos;
            map.put(id, c);
        }
        long dt = now - c.lastSeen;
        if (knownVel != null) {
            c.vel = knownVel;
        } else if (dt > 0 && dt <= 400) {
            Vec3 v = pos.subtract(c.pos).scale(1.0 / dt);
            // частые обнаружения сглаживаем, редкие (вращающаяся антенна) берём как есть
            c.vel = dt < 8 ? c.vel.scale(0.5).add(v.scale(0.5)) : v;
        } else if (dt > 400) {
            c.vel = Vec3.ZERO;
        }
        c.name = name;
        c.pos = pos;
        c.size = size;
        c.hostile = hostile;
        c.entity = entity;
        c.lastSeen = now;
        c.expires = Math.max(c.expires, now + ttl);
        return c;
    }

    public void expire(long now) {
        map.values().removeIf(c -> c.expires < now || (c.entity != null && c.entity.isRemoved()));
        if (los.size() > 4 * map.size() + 64) los.clear();
    }

    @Nullable
    public Boolean cachedLos(UUID id, long now, int maxAge) {
        long[] e = los.get(id);
        if (e == null || now - e[0] > maxAge) return null;
        return e[1] != 0;
    }

    public void putLos(UUID id, long now, boolean visible) {
        los.put(id, new long[]{now, visible ? 1 : 0});
    }

    public Collection<RadarContact> all() {
        return Collections.unmodifiableCollection(map.values());
    }

    @Nullable
    public RadarContact get(UUID id) {
        return map.get(id);
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
        los.clear();
    }
}
