package dev.radarfcs.block;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.radarfcs.core.AngleLimit;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Пределы поворота блока в обе стороны - два независимых ползунка ({@link AngleLimitBehaviour}): ряд 0 - влево (курс)
 * или вверх (наклон), ряд 1 - вправо или вниз.
 */
public final class AngleLimits {
    public final AngleLimitBehaviour firstBox, secondBox;

    public AngleLimits(SmartBlockEntity be, BooleanSupplier pitch, Runnable onChange) {
        firstBox = new AngleLimitBehaviour(be, 0, pitch, onChange);
        secondBox = new AngleLimitBehaviour(be, 1, pitch, onChange);
    }

    public void addTo(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(firstBox);
        behaviours.add(secondBox);
    }

    /** Влево (курс) или вверх (наклон), градусы; {@link AngleLimit#FREE} - без предела. */
    public int first() {
        return firstBox.limit();
    }

    /** Вправо (курс) или вниз (наклон). */
    public int second() {
        return secondBox.limit();
    }

    public boolean limited() {
        return !AngleLimit.free(first()) || !AngleLimit.free(second());
    }

    public void set(int first, int second) {
        firstBox.set(first);
        secondBox.set(second);
    }

    public String formatValue() {
        return firstBox.formatValue() + " " + secondBox.formatValue();
    }
}
