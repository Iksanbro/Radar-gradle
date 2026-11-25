package dev.aeroschematics.compat;

import dev.aeroschematics.capture.SubLevelCaptureMeta;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext;
import dev.ryanhcode.sable.sublevel.SubLevel;
import edn.stratodonut.drivebywire.WireConfig;
import edn.stratodonut.drivebywire.WireItems;
import edn.stratodonut.drivebywire.network.WireNetworkFullSyncPacket;
import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import edn.stratodonut.drivebywire.wire.graph.WireNetworkNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Drive-By-Wire keeps its wires in a per-level saved data graph keyed by absolute block positions, not in block
 * entities, so they never end up in a schematic by themselves. This class captures the connections of the saved
 * structures and recreates them on the printed copies.
 * <p>
 * Only referenced when Drive-By-Wire is loaded ({@link Mods#DRIVE_BY_WIRE}).
 */
public final class DriveByWireCompat {
    private DriveByWireCompat() {
    }

    /**
     * Collects all connections whose source is inside {@code source} and whose sink is inside any sub-level captured by
     * the same schematic (including {@code source} itself). Wires to blocks outside physics structures are skipped,
     * exactly like Drive-By-Wire's own backup block does.
     */
    public static List<SubLevelCaptureMeta.WireRecord> capture(Level level, SubLevel source, SubLevelSchematicSerializationContext context) {
        List<SubLevelCaptureMeta.WireRecord> wires = new ArrayList<>();
        WireNetworkManager manager = WireNetworkManager.get(level);
        BlockPos sourceCenter = source.getPlot().getCenterBlock();
        for (Map.Entry<Long, Map<String, Set<WireNetworkNode.WireNetworkSink>>> entry : manager.getNetwork().entrySet()) {
            BlockPos sourcePos = BlockPos.of(entry.getKey());
            if (Sable.HELPER.getContaining(level, sourcePos) != source) {
                continue;
            }
            for (Map.Entry<String, Set<WireNetworkNode.WireNetworkSink>> channel : entry.getValue().entrySet()) {
                for (WireNetworkNode.WireNetworkSink sink : channel.getValue()) {
                    BlockPos sinkPos = BlockPos.of(sink.position());
                    SubLevel sinkSubLevel = Sable.HELPER.getContaining(level, sinkPos);
                    if (sinkSubLevel == null) {
                        continue;
                    }
                    SubLevelSchematicSerializationContext.SchematicMapping mapping = context.getMapping(sinkSubLevel);
                    if (mapping == null) {
                        continue;
                    }
                    wires.add(new SubLevelCaptureMeta.WireRecord(
                            sourcePos.subtract(sourceCenter),
                            mapping.newUUID(),
                            sinkPos.subtract(sinkSubLevel.getPlot().getCenterBlock()),
                            Direction.from3DDataValue(sink.direction()),
                            channel.getKey()));
                }
            }
        }
        return wires;
    }

    /** Whether restoring a connection costs a wire item (survival only). */
    public static boolean wiresCostItems() {
        return WireConfig.CONFIG.shouldConsumeWires.get();
    }

    public static ItemStack wireStack() {
        return new ItemStack(WireItems.WIRE.get());
    }

    public static boolean hasConnection(Level level, BlockPos source, BlockPos sink, Direction direction, String channel) {
        return WireNetworkManager.hasConnection(level, source, sink, direction, channel);
    }

    /** @return {@code true} if the connection now exists. */
    public static boolean connect(ServerLevel level, BlockPos source, BlockPos sink, Direction direction, String channel) {
        WireNetworkManager.ConnectionResult result = WireNetworkManager.createConnection(level, source, sink, direction, channel);
        return result.isSuccess() || result == WireNetworkManager.ConnectionResult.FAIL_EXISTS;
    }

    /** Pushes the updated wire graph to players so that the new wires are rendered. */
    public static void syncToPlayers(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            WireNetworkFullSyncPacket.sendTo(player);
        }
    }
}
