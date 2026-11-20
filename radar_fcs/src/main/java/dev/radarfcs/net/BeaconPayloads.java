package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.OrbitBeaconBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Экран маяка орбиты: открыть (сервер -> клиент, код) и сохранить код. */
public final class BeaconPayloads {
    private static final double MAX_DIST = 8;

    private BeaconPayloads() {}

    public record Open(BlockPos pos, String code) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "beacon_open"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::pos, ByteBufCodecs.stringUtf8(64), Open::code, Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Save(BlockPos pos, String code) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "beacon_save"));
        public static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Save::pos, ByteBufCodecs.stringUtf8(64), Save::code, Save::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(Open.TYPE, Open.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.FcsClientHooks.openBeacon(p)));
        r.playToServer(Save.TYPE, Save.CODEC, BeaconPayloads::handleSave);
    }

    public static void open(ServerPlayer sp, OrbitBeaconBlockEntity be) {
        PacketDistributor.sendToPlayer(sp, new Open(be.getBlockPos(), be.code()));
    }

    private static void handleSave(Save p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !sp.mayBuild()) return;
            if (!sp.level().isLoaded(p.pos())) return;
            Vec3 w = dev.radarfcs.compat.SableCompat.toWorld(sp.level(), Vec3.atCenterOf(p.pos()));
            if (sp.distanceToSqr(w) > MAX_DIST * MAX_DIST && sp.distanceToSqr(Vec3.atCenterOf(p.pos())) > MAX_DIST * MAX_DIST) return;
            if (!(sp.level().getBlockEntity(p.pos()) instanceof OrbitBeaconBlockEntity be)) return;
            be.setCode(p.code());
            sp.displayClientMessage(Component.translatable("radar_fcs.orbit_beacon.saved").withStyle(ChatFormatting.GREEN), true);
        });
    }
}
