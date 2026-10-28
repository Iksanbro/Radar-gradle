package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.fcs.SettingsNbt;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Сервер -> клиент: открыть экран вычислителя. Коды «свой-чужой» - только владельцу, его команде и операторам. */
public record FcsOpenPayload(BlockPos pos, CompoundTag settings, boolean iffAccess) implements CustomPacketPayload {
    public static final Type<FcsOpenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "fcs_open"));
    public static final StreamCodec<ByteBuf, FcsOpenPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FcsOpenPayload::pos,
            ByteBufCodecs.COMPOUND_TAG, FcsOpenPayload::settings,
            ByteBufCodecs.BOOL, FcsOpenPayload::iffAccess,
            FcsOpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.FcsClientHooks.openFcsSettings(p.pos(), SettingsNbt.read(p.settings()), p.iffAccess())));
    }

    public static void open(ServerPlayer sp, FireControlBlockEntity be) {
        boolean access = be.canEditIff(sp);
        PacketDistributor.sendToPlayer(sp, new FcsOpenPayload(be.getBlockPos(),
                SettingsNbt.write(access ? be.settings() : be.settingsForClients()), access));
    }
}
