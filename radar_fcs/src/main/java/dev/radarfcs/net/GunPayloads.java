package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.GunLinkBlockEntity;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.fcs.GunSettings;
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

/** Экран настроек орудия (Shift+ПКМ по орудийному линку): открыть (сервер -> клиент) и сохранить (клиент -> сервер). */
public final class GunPayloads {
    private static final double MAX_DIST = 8;

    private GunPayloads() {}

    public record Open(BlockPos link, String network, CompoundTag settings) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "gun_open"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::link, ByteBufCodecs.stringUtf8(16), Open::network,
                ByteBufCodecs.COMPOUND_TAG, Open::settings, Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Save(BlockPos link, CompoundTag settings) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "gun_save"));
        public static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Save::link, ByteBufCodecs.COMPOUND_TAG, Save::settings, Save::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(Open.TYPE, Open.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.FcsClientHooks.openGunSettings(p)));
        r.playToServer(Save.TYPE, Save.CODEC, GunPayloads::handleSave);
    }

    /** Открыть экран игроку. */
    public static void open(ServerPlayer sp, GunLinkBlockEntity be) {
        if (be.network() == null) {
            sp.displayClientMessage(Component.translatable("radar_fcs.gun_link.not_linked").withStyle(ChatFormatting.RED), true);
            return;
        }
        CompoundTag t = be.settings().write();
        // линк на станке CBC - этот станок можно сделать основной пушкой орудия
        if (sp.level().getBlockEntity(be.attached()) instanceof net.minecraft.world.level.block.entity.BlockEntity a
                && dev.radarfcs.compat.CbcMount.of(a) != null) t.putLong("ThisMount", be.attached().asLong());
        PacketDistributor.sendToPlayer(sp, new Open(be.getBlockPos(), RadarLinkBlockEntity.shortName(be.network()), t));
    }

    private static void handleSave(Save p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !sp.mayBuild()) return;
            if (!sp.level().isLoaded(p.link()) || sp.distanceToSqr(Vec3.atCenterOf(p.link())) > MAX_DIST * MAX_DIST) return;
            if (!(sp.level().getBlockEntity(p.link()) instanceof GunLinkBlockEntity be)) return;
            be.saveSettings(GunSettings.read(p.settings()));
            sp.displayClientMessage(Component.translatable("radar_fcs.gun.saved").withStyle(ChatFormatting.GREEN), true);
        });
    }
}
