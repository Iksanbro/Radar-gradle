package dev.radarfcs.compat;

import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;

/**
 * Пушки CBC Modern Warfare (порт для 1.21.1 - CBC Neo Warfare), без зависимости от мода - по именам классов.
 * Роторная пушка стреляет как автопушка (пока есть сигнал, в тике контрапшена), снаряд появляется
 * на 0.75 блока перед блоком за последним стволом. Средняя пушка - как большая: по фронту сигнала, сразу,
 * по положению ствола на начало тика; снаряд появляется как у автопушки.
 */
public final class NeoWarfare {
    private NeoWarfare() {}

    public static boolean isMedium(AbstractMountedCannonContraption c) {
        return c.getClass().getSimpleName().contains("Mediumcannon");
    }

    public static boolean isRotary(AbstractMountedCannonContraption c) {
        return c.getClass().getSimpleName().contains("Rotarycannon");
    }
}
