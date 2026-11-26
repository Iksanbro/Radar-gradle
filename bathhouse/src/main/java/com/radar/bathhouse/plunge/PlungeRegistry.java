package com.radar.bathhouse.plunge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Working plunges per dimension, so players can be matched to the basin they are sitting in. */
public final class PlungeRegistry {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> ACTIVE = new HashMap<>();

    private PlungeRegistry() {
    }

    public static void register(Level level, BlockPos pos) {
        ACTIVE.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(pos.immutable());
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = ACTIVE.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
    }

    /** The plunge whose water or shavings the player is in, or null. */
    @Nullable
    public static PlungeControllerBlockEntity find(ServerPlayer player) {
        Set<BlockPos> set = ACTIVE.get(player.level().dimension());
        if (set == null || set.isEmpty()) {
            return null;
        }
        for (BlockPos pos : List.copyOf(set)) {
            if (player.level().isLoaded(pos) && player.level().getBlockEntity(pos) instanceof PlungeControllerBlockEntity plunge) {
                if (plunge.isInMedium(player)) {
                    return plunge;
                }
            } else {
                set.remove(pos);
            }
        }
        return null;
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
