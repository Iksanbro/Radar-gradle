package dev.radarfcs.client;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.item.RadarLinkItem;
import dev.radarfcs.link.LinkKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Подсветка радарной сети: с настроенным линком в руке - вся аппаратура его сети (голубая), аппаратура под прицелом
 * (зелёная - сюда можно поставить линк); взгляд на стоящий линк - аппаратура его сети (жёлтая).
 */
@EventBusSubscriber(modid = FcsMod.MODID, value = Dist.CLIENT)
public final class RadarLinkClient {
    private static final int NETWORK = 0x7FD4FF, VALID = 0x6CFF8C, LOOKED = 0xFFD24A;
    private static final float LINE = 1 / 16f;
    private static final int SCAN_CHUNKS = 6;

    private static UUID cachedNet;
    private static long cachedAt;
    private static List<BlockPos> cachedMembers = List.of();

    private RadarLinkClient() {}

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ClientLevel level = mc.level;
        BlockPos hover = mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : null;

        ItemStack held = mc.player.getMainHandItem().getItem() instanceof RadarLinkItem ? mc.player.getMainHandItem()
                : mc.player.getOffhandItem().getItem() instanceof RadarLinkItem ? mc.player.getOffhandItem() : null;
        UUID show = null;
        int color = NETWORK;
        if (hover != null && level.getBlockEntity(hover) instanceof RadarLinkBlockEntity link && link.network() != null) {
            show = link.network();
            color = LOOKED;
        } else if (held != null) {
            show = RadarLinkItem.network(held);
            if (hover != null && LinkKind.at(level, hover) != null) outline("radar_fcs:link_hover", hover, VALID);
        }
        if (show == null) return;
        long now = level.getGameTime();
        if (!show.equals(cachedNet) || now - cachedAt >= 10) {
            cachedNet = show;
            cachedAt = now;
            cachedMembers = scan(level, mc.player.blockPosition(), show);
        }
        int i = 0;
        for (BlockPos p : cachedMembers) outline("radar_fcs:link_member_" + i++, p, color);
    }

    /** Аппаратура сети в загруженных чанках вокруг игрока (по линкам на ней). */
    private static List<BlockPos> scan(ClientLevel level, BlockPos center, UUID net) {
        List<BlockPos> out = new ArrayList<>();
        int cx0 = center.getX() >> 4, cz0 = center.getZ() >> 4;
        for (int cx = cx0 - SCAN_CHUNKS; cx <= cx0 + SCAN_CHUNKS; cx++) {
            for (int cz = cz0 - SCAN_CHUNKS; cz <= cz0 + SCAN_CHUNKS; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof RadarLinkBlockEntity link && net.equals(link.network()) && !out.contains(link.attached())) {
                        out.add(link.attached());
                        if (out.size() >= 64) return out;
                    }
                }
            }
        }
        return out;
    }

    private static void outline(String slot, BlockPos pos, int color) {
        Outliner.getInstance().showAABB(slot, new AABB(pos).inflate(1 / 64.0)).colored(color).lineWidth(LINE);
    }
}
