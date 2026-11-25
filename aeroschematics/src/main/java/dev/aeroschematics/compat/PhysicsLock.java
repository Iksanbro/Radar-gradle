package dev.aeroschematics.compat;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import net.minecraft.server.level.ServerLevel;

/**
 * Holds a physics structure in place while the schematicannon builds it, using the same persistent lock as Simulated's
 * creative physics staff (it survives server restarts and is shown to players).
 */
public final class PhysicsLock {
    private PhysicsLock() {
    }

    /** Locks the sub-level (or re-applies the lock after a reload if Simulated has not done it yet). */
    public static void ensureLocked(ServerLevel level, SubLevel subLevel) {
        PhysicsStaffServerHandler handler = PhysicsStaffServerHandler.get(level);
        if (!handler.isLocked(subLevel)) {
            handler.applyLockIfNeeded(subLevel);
            if (!handler.isLocked(subLevel)) {
                handler.toggleLock(subLevel.getUniqueId());
            }
        }
    }

    public static void unlock(ServerLevel level, SubLevel subLevel) {
        PhysicsStaffServerHandler.get(level).removeLock(subLevel);
    }
}
