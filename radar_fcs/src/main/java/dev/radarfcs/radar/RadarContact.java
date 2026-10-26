package dev.radarfcs.radar;

import dev.radarfcs.core.Track;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Отметка радара: что и где радар видел в последний раз. */
public final class RadarContact {
    public final UUID id;
    public final Track.Kind kind;
    public String name;
    /** Мировой центр цели при последнем обнаружении. */
    public Vec3 pos;
    /** Оценка скорости по двум обнаружениям (или по физике корабля), блоков/тик. */
    public Vec3 vel = Vec3.ZERO;
    /** Характерный радиус цели, блоков. */
    public double size;
    public boolean hostile;
    public long firstSeen, lastSeen, expires;
    /** Сущность на сервере (для кораблей - null). */
    @Nullable public Entity entity;

    RadarContact(UUID id, Track.Kind kind) {
        this.id = id;
        this.kind = kind;
    }

    public boolean isShip() {
        return kind == Track.Kind.SHIP;
    }

    public double speedPerTick() {
        return vel.length();
    }
}
