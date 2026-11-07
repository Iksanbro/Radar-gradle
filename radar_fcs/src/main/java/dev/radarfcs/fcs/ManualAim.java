package dev.radarfcs.fcs;

import dev.radarfcs.core.Vec;

/**
 * Ручное наведение с камеры сети: точка, куда смотрит камера, и зажат ли огонь. Пока оно есть, автоприцеливание
 * пушек вычислителя приостановлено: все пушки наводятся на эту точку и стреляют только по команде.
 */
public record ManualAim(Vec point, boolean fire, String camera, Vec velocity, java.util.Set<Long> guns, long stamp) {
    public ManualAim(Vec point, boolean fire, String camera) {
        this(point, fire, camera, Vec.ZERO, java.util.Set.of(), 0);
    }

    /** Управляет ли эта камера орудием с ключом key (пустой набор - всеми орудиями сети). */
    public boolean controls(long key) {
        return guns.isEmpty() || guns.contains(key);
    }
}
