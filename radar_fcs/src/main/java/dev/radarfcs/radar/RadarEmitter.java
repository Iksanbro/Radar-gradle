package dev.radarfcs.radar;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Радар мода: источник отметок для вычислителей и мониторов своего канала. */
public interface RadarEmitter {
    enum Type { BEARING, ARRAY, DOME }

    Level radarLevel();

    BlockPos radarPos();

    boolean isRadarRemoved();

    /** Радар работает и обновляет отметки. */
    boolean radarRunning();

    /** Канал (id цвета красителя). */
    int radarChannel();

    /** Мировая позиция антенны. */
    Vec3 radarOrigin();

    /** Дальность обнаружения цели размером с игрока, блоков. */
    double radarRange();

    Type radarType();

    ContactBook radarContacts();
}
