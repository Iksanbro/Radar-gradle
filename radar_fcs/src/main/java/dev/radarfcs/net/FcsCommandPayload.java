package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Команда вычислителю с экрана настроек: собрать или разобрать все его пушки. */
public record FcsCommandPayload(BlockPos pos, int command) implements CustomPacketPayload {
    public static final int ASSEMBLE = 0, DISASSEMBLE = 1;
    public static final Type<FcsCommandPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "fcs_command"));
    public static final StreamCodec<ByteBuf, FcsCommandPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FcsCommandPayload::pos,
            ByteBufCodecs.VAR_INT, FcsCommandPayload::command,
            FcsCommandPayload::new);
    private static final double MAX_DIST_SQR = 10 * 10;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, CODEC, FcsCommandPayload::handle);
    }

    private static void handle(FcsCommandPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !sp.mayBuild()) return;
            if (!sp.level().isLoaded(p.pos()) || sp.distanceToSqr(Vec3.atCenterOf(p.pos())) > MAX_DIST_SQR) return;
            if (sp.level().getBlockEntity(p.pos()) instanceof FireControlBlockEntity be) {
                if (p.command() == ASSEMBLE) be.assembleAll(sp);
                else if (p.command() == DISASSEMBLE) be.disassembleAll(sp);
            }
        });
    }
}
