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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Настройки вычислителя с экрана настроек на сервер. */
public record FcsSettingsPayload(BlockPos pos, CompoundTag settings) implements CustomPacketPayload {
    public static final Type<FcsSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "fcs_settings"));
    public static final StreamCodec<ByteBuf, FcsSettingsPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FcsSettingsPayload::pos,
            ByteBufCodecs.COMPOUND_TAG, FcsSettingsPayload::settings,
            FcsSettingsPayload::new);
    /** Дальше этого от блока (квадрат расстояния) настройки не принимаются. */
    private static final double MAX_DIST_SQR = 10 * 10;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, CODEC, FcsSettingsPayload::handle);
    }

    private static void handle(FcsSettingsPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !sp.mayBuild()) return;
            if (!sp.level().isLoaded(p.pos()) || sp.distanceToSqr(Vec3.atCenterOf(p.pos())) > MAX_DIST_SQR) return;
            if (sp.level().getBlockEntity(p.pos()) instanceof FireControlBlockEntity be) {
                be.applySettings(SettingsNbt.read(p.settings()), sp);
            }
        });
    }
}
