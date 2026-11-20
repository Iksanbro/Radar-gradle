package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.IffTransponderBlockEntity;
import dev.radarfcs.iff.TransponderSettings;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Экран транспондера: открытие с сервера (настройки - только тому, кому можно) и сохранение. */
public final class TransponderPayloads {
    private TransponderPayloads() {}

    private static final double MAX_DIST_SQR = 10 * 10;

    /** Сервер -> клиент: открыть экран транспондера. settings - TransponderSettings; flags: 1 - на сублевеле, 2 - есть питание. */
    public record Open(BlockPos pos, CompoundTag settings, int flags, int maxRadius) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "transponder_open"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::pos,
                ByteBufCodecs.COMPOUND_TAG, Open::settings,
                ByteBufCodecs.VAR_INT, Open::flags,
                ByteBufCodecs.VAR_INT, Open::maxRadius,
                Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: сохранить настройки транспондера (TransponderSettings). */
    public record Save(BlockPos pos, CompoundTag settings) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "transponder_save"));
        public static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Save::pos,
                ByteBufCodecs.COMPOUND_TAG, Save::settings,
                Save::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(Open.TYPE, Open.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.FcsClientHooks.openTransponder(p)));
        r.playToServer(Save.TYPE, Save.CODEC, TransponderPayloads::handleSave);
    }

    /** Открыть экран транспондера игроку (если ему можно видеть код). */
    public static void open(ServerPlayer sp, IffTransponderBlockEntity be) {
        PacketDistributor.sendToPlayer(sp, new Open(be.getBlockPos(), be.settings().write(),
                (be.onSublevel() ? 1 : 0) | (be.powered() ? 2 : 0), IffTransponderBlockEntity.maxRadius()));
    }

    private static void handleSave(Save p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !sp.mayBuild()) return;
            if (!sp.level().isLoaded(p.pos()) || sp.distanceToSqr(Vec3.atCenterOf(p.pos())) > MAX_DIST_SQR) return;
            if (sp.level().getBlockEntity(p.pos()) instanceof IffTransponderBlockEntity be) {
                be.apply(TransponderSettings.read(p.settings()), sp);
            }
        });
    }
}
