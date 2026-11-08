package dev.radarfcs.fcs;

import dev.radarfcs.core.Vec;

/**
 * Ручное наведение с камеры сети: точка, куда смотрит камера, и зажат ли огонь. Пока оно есть, автоприцеливание
 * пушек вычислителя приостановлено: все пушки наводятся на эту точку и стреляют только по команде.
 */
public record ManualAim(Vec point, boolean fire, String camera, Vec velocity, java.util.Set<Long> guns, long stamp,
                        Hit hit) {
    public ManualAim(Vec point, boolean fire, String camera) {
        this(point, fire, camera, Vec.ZERO, java.util.Set.of(), 0, Hit.NONE);
    }

    /**
     * Во что упёрся взгляд камеры: блок (в мире или в плоте постройки Sable), постройка Sable, сущность (id или -1).
     * По нему вычислитель узнаёт, что камера смотрит на само орудие, - такое орудие не поворачивается.
     */
    public record Hit(@org.jetbrains.annotations.Nullable net.minecraft.core.BlockPos block,
                      @org.jetbrains.annotations.Nullable java.util.UUID ship, int entity) {
        public static final Hit NONE = new Hit(null, null, -1);
    }

    /** Управляет ли эта камера орудием с ключом key (пустой набор - всеми орудиями сети). */
    public boolean controls(long key) {
        return guns.isEmpty() || guns.contains(key);
    }
}
