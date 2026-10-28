package dev.radarfcs.iff;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

/**
 * Кто видит и меняет коды «свой-чужой» (транспондера и вычислителя): владелец (кто поставил блок), его команда
 * и операторы. У блока без владельца (поставлен до 0.9 или командой) - любой; первый сохранивший становится владельцем.
 */
public final class IffAccess {
    private IffAccess() {}

    public static boolean canEdit(Player p, @Nullable UUID owner, String ownerTeam) {
        if (owner == null || p.getUUID().equals(owner) || p.hasPermissions(2)) return true;
        Team t = p.getTeam();
        return ownerTeam != null && !ownerTeam.isEmpty() && t != null && t.getName().equals(ownerTeam);
    }
}
