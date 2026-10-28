package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.IffTransponderBlockEntity;
import dev.radarfcs.iff.TransponderMode;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
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

    /** Сервер -> клиент: открыть экран транспондера с его настройками. */
    public record Open(BlockPos pos, String code, int mode, int radius, boolean onSublevel, int maxRadius)
            implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "transponder_open"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::pos,
                ByteBufCodecs.stringUtf8(16), Open::code,
                ByteBufCodecs.VAR_INT, Open::mode,
                ByteBufCodecs.VAR_INT, Open::radius,
                ByteBufCodecs.BOOL, Open::onSublevel,
                ByteBufCodecs.VAR_INT, Open::maxRadius,
                Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: сохранить настройки транспондера. */
    public record Save(BlockPos pos, String code, int mode, int radius) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "transponder_save"));
        public static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Save::pos,
                ByteBufCodecs.stringUtf8(16), Save::code,
                ByteBufCodecs.VAR_INT, Save::mode,
                ByteBufCodecs.VAR_INT, Save::radius,
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
        PacketDistributor.sendToPlayer(sp, new Open(be.getBlockPos(), be.code(), be.effectiveMode().ordinal(), be.radius(),
                be.onSublevel(), IffTransponderBlockEntity.maxRadius()));
    }

    private static void handleSave(Save p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !sp.mayBuild()) return;
            if (!sp.level().isLoaded(p.pos()) || sp.distanceToSqr(Vec3.atCenterOf(p.pos())) > MAX_DIST_SQR) return;
            if (sp.level().getBlockEntity(p.pos()) instanceof IffTransponderBlockEntity be) {
                TransponderMode[] modes = TransponderMode.values();
                be.apply(p.code(), modes[Math.floorMod(p.mode(), modes.length)], p.radius(), sp);
            }
        });
    }
}
