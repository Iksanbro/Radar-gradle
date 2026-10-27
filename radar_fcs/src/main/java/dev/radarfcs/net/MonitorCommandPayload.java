package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Команда с экрана радарного монитора: назначить или снять цель, масштаб, фильтры показа. */
public record MonitorCommandPayload(BlockPos pos, int action, int value, String id) implements CustomPacketPayload {
    public static final int DESIGNATE = 0, CLEAR = 1, SCALE = 2, SHOW = 3;
    public static final Type<MonitorCommandPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "monitor_command"));
    public static final StreamCodec<ByteBuf, MonitorCommandPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MonitorCommandPayload::pos,
            ByteBufCodecs.VAR_INT, MonitorCommandPayload::action,
            ByteBufCodecs.VAR_INT, MonitorCommandPayload::value,
            ByteBufCodecs.stringUtf8(64), MonitorCommandPayload::id,
            MonitorCommandPayload::new);
    /** Экран монитора может быть большим: от любого его блока не дальше этого. */
    private static final double MAX_DIST_SQR = 24 * 24;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, CODEC, MonitorCommandPayload::handle);
    }

    private static void handle(MonitorCommandPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator()) return;
            if (!sp.level().isLoaded(p.pos()) || sp.distanceToSqr(Vec3.atCenterOf(p.pos())) > MAX_DIST_SQR) return;
            if (!(sp.level().getBlockEntity(p.pos()) instanceof RadarMonitorBlockEntity be)) return;
            switch (p.action()) {
                case DESIGNATE -> {
                    UUID id;
                    try {
                        id = UUID.fromString(p.id());
                    } catch (IllegalArgumentException e) {
                        return;
                    }
                    be.designate(id, sp);
                }
                case CLEAR -> be.designate(null, sp);
                case SCALE -> be.setScale(p.value());
                case SHOW -> be.setShow(p.value());
                default -> { }
            }
        });
    }
}
