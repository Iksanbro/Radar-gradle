package dev.aeroschematics.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ConnectivityOrderTest {
    private static List<BlockPos> sortedCreateOrder(Set<BlockPos> blocks) {
        List<BlockPos> list = new ArrayList<>(blocks);
        list.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
        return list;
    }

    /** Asserts that every block after the first touches an earlier one, i.e. Sable never sees two pieces. */
    private static void assertAlwaysConnected(List<BlockPos> order) {
        Set<BlockPos> placed = new HashSet<>();
        placed.add(order.get(0));
        for (int i = 1; i < order.size(); i++) {
            BlockPos next = order.get(i);
            boolean touches = false;
            for (BlockPos offset : ConnectivityOrder.NEIGHBOURS) {
                if (placed.contains(next.offset(offset))) {
                    touches = true;
                    break;
                }
            }
            assertTrue(touches, "block #" + i + " " + next + " would be printed disconnected");
            placed.add(next);
        }
    }

    @Test
    void airshipLikeShapeWhoseCreateOrderWouldSplitStaysConnected() {
        // Two pontoons only joined by a deck high above: Create's Y/Z/X order prints both pontoons first.
        Set<BlockPos> blocks = new HashSet<>();
        for (int x = 0; x < 10; x++) {
            blocks.add(new BlockPos(x, 0, 0));
            blocks.add(new BlockPos(x, 0, 6));
        }
        for (int y = 1; y <= 4; y++) {
            blocks.add(new BlockPos(0, y, 0));
            blocks.add(new BlockPos(0, y, 6));
        }
        for (int z = 0; z <= 6; z++) {
            for (int x = 0; x < 10; x++) {
                blocks.add(new BlockPos(x, 5, z));
            }
        }
        List<BlockPos> order = ConnectivityOrder.order(sortedCreateOrder(blocks), pos -> false, pos -> true);
        assertEquals(blocks.size(), order.size());
        assertEquals(blocks, new HashSet<>(order));
        assertAlwaysConnected(order);
    }

    @Test
    void attachmentsComeAfterTheirSupport() {
        Set<BlockPos> blocks = new HashSet<>();
        for (int x = 0; x < 5; x++) {
            blocks.add(new BlockPos(x, 0, 0));
        }
        BlockPos torch = new BlockPos(2, 1, 0);
        blocks.add(torch);
        List<BlockPos> order = ConnectivityOrder.order(sortedCreateOrder(blocks), torch::equals, pos -> true);
        assertEquals(torch, order.get(order.size() - 1));
        assertAlwaysConnected(order);
    }

    @Test
    void preferredSeedIsUsed() {
        Set<BlockPos> blocks = new HashSet<>();
        for (int x = 0; x < 5; x++) {
            blocks.add(new BlockPos(x, 0, 0));
        }
        BlockPos solid = new BlockPos(3, 0, 0);
        List<BlockPos> order = ConnectivityOrder.order(sortedCreateOrder(blocks), pos -> false, solid::equals);
        assertEquals(solid, order.get(0));
        assertAlwaysConnected(order);
    }

    @Test
    void randomConnectedStructuresStayConnected() {
        Random random = new Random(42);
        for (int run = 0; run < 50; run++) {
            Set<BlockPos> blocks = new HashSet<>();
            BlockPos cursor = BlockPos.ZERO;
            blocks.add(cursor);
            for (int i = 0; i < 300; i++) {
                BlockPos step = ConnectivityOrder.NEIGHBOURS[random.nextInt(ConnectivityOrder.NEIGHBOURS.length)];
                cursor = cursor.offset(step);
                blocks.add(cursor);
            }
            List<BlockPos> order = ConnectivityOrder.order(sortedCreateOrder(blocks), pos -> random.nextInt(10) == 0, pos -> true);
            assertEquals(blocks.size(), order.size());
            assertAlwaysConnected(order);
        }
    }
}
