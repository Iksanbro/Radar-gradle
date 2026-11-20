package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.DockingControllerBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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

/** Экран стыковочного контроллера: открыть (сервер -> клиент: настройки, связь с портом, состояние) и сохранить. */
public final class DockingPayloads {
    private static final double MAX_DIST = 8;

    private DockingPayloads() {}

    public record Open(BlockPos pos, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "docking_open"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::pos, ByteBufCodecs.COMPOUND_TAG, Open::data, Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Save(BlockPos pos, CompoundTag settings) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "docking_save"));
        public static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Save::pos, ByteBufCodecs.COMPOUND_TAG, Save::settings, Save::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(Open.TYPE, Open.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.FcsClientHooks.openDocking(p)));
        r.playToServer(Save.TYPE, Save.CODEC, DockingPayloads::handleSave);
    }

    public static void open(ServerPlayer sp, DockingControllerBlockEntity be) {
        CompoundTag t = be.writeSettings();
        t.putInt("Connectors", be.connectorCount());
        t.putString("Status", be.status().name());
        PacketDistributor.sendToPlayer(sp, new Open(be.getBlockPos(), t));
    }

    private static void handleSave(Save p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !sp.mayBuild()) return;
            if (!sp.level().isLoaded(p.pos())) return;
            // контроллер на корабле Sable: игрок рядом с его мировым положением
            Vec3 w = dev.radarfcs.compat.SableCompat.toWorld(sp.level(), Vec3.atCenterOf(p.pos()));
            if (sp.distanceToSqr(w) > MAX_DIST * MAX_DIST && sp.distanceToSqr(Vec3.atCenterOf(p.pos())) > MAX_DIST * MAX_DIST) return;
            if (!(sp.level().getBlockEntity(p.pos()) instanceof DockingControllerBlockEntity be)) return;
            be.applySettings(p.settings());
            sp.displayClientMessage(Component.translatable("radar_fcs.docking.saved").withStyle(ChatFormatting.GREEN), true);
        });
    }
}
