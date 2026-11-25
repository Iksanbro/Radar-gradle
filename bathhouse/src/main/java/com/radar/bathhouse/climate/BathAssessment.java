package com.radar.bathhouse.climate;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Result of classifying a room.
 *
 * @param type     the bath type the room currently qualifies as, or null if it is not a ready bath
 * @param variant  white / gray / black, derived from fireboxes, chimneys and vents
 * @param closest  the type the room is closest to (equal to {@code type} when ready)
 * @param problems what is missing for {@code closest}; empty when ready
 */
public record BathAssessment(@Nullable BathType type, SmokeVariant variant, @Nullable BathType closest,
                             List<Component> problems) {
    public static final BathAssessment NONE = new BathAssessment(null, SmokeVariant.WHITE, null,
            List.of(Component.translatable("bathhouse.problem.no_room")));

    public boolean ready() {
        return type != null;
    }
}
