package dev.aeroschematics.place;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/**
 * Printing order that keeps a physics structure in one piece while it is built block by block.
 * <p>
 * Sable splits a structure as soon as its blocks are not connected through its 18-neighbourhood (faces and edges), so
 * every block after the first must touch an already placed one.
 */
public final class ConnectivityOrder {
    /** Same neighbourhood as Sable's {@code SubLevelHeatMapManager} (faces + edges, no corners). */
    public static final BlockPos[] NEIGHBOURS = {
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, -1, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(1, 1, 0), new BlockPos(-1, -1, 0), new BlockPos(1, -1, 0), new BlockPos(-1, 1, 0),
            new BlockPos(1, 0, 1), new BlockPos(-1, 0, -1), new BlockPos(1, 0, -1), new BlockPos(-1, 0, 1),
            new BlockPos(0, 1, 1), new BlockPos(0, -1, -1), new BlockPos(0, -1, 1), new BlockPos(0, 1, -1)
    };

    private ConnectivityOrder() {
    }

    public static boolean adjacent(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return dx <= 1 && dy <= 1 && dz <= 1 && dx + dy + dz >= 1 && dx + dy + dz <= 2;
    }

    /**
     * Breadth-first order: first every non-deferred block reachable from the seed, then everything else (attachments
     * such as torches or levers, which need their support first) growing out of the placed set, then whatever is left
     * unconnected.
     *
     * @param sorted   all positions, in the base order used to pick the seed and break ties
     * @param deferred blocks that must come after the structural ones
     * @param goodSeed preferred first blocks (solid full cubes)
     */
    public static List<BlockPos> order(List<BlockPos> sorted, Predicate<BlockPos> deferred, Predicate<BlockPos> goodSeed) {
        if (sorted.isEmpty()) {
            return List.of();
        }
        Set<BlockPos> remaining = new LinkedHashSet<>(sorted);
        BlockPos seed = null;
        for (BlockPos pos : sorted) {
            if (!deferred.test(pos) && goodSeed.test(pos)) {
                seed = pos;
                break;
            }
        }
        if (seed == null) {
            for (BlockPos pos : sorted) {
                if (!deferred.test(pos)) {
                    seed = pos;
                    break;
                }
            }
        }
        if (seed == null) {
            seed = sorted.get(0);
        }

        List<BlockPos> result = new ArrayList<>(sorted.size());
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        remaining.remove(seed);
        result.add(seed);
        bfs(queue, remaining, result, pos -> !deferred.test(pos));
        while (!remaining.isEmpty()) {
            queue.addAll(result);
            int before = result.size();
            bfs(queue, remaining, result, pos -> true);
            if (result.size() == before) {
                // Disconnected leftovers (cannot happen for a structure Sable kept as one piece): new component.
                BlockPos next = remaining.iterator().next();
                remaining.remove(next);
                result.add(next);
                queue.add(next);
            }
        }
        return result;
    }

    private static void bfs(ArrayDeque<BlockPos> queue, Set<BlockPos> remaining, List<BlockPos> result, Predicate<BlockPos> allowed) {
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (BlockPos offset : NEIGHBOURS) {
                BlockPos next = current.offset(offset);
                if (remaining.contains(next) && allowed.test(next)) {
                    remaining.remove(next);
                    result.add(next);
                    queue.add(next);
                }
            }
        }
    }
}
