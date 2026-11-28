package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.compat.rocket.HeatNoseBlockEntity;
import dev.radarfcs.compat.rocket.NetNoseBlockEntity;
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

/**
 * Экраны носов ракет: тепловая головка (типы целей) и программируемый нос (режим пуска, допустимая цель автопуска).
 * Открыть - сервер -> клиент, сохранить - клиент -> сервер (с проверкой расстояния).
 */
public final class RocketNosePayloads {
    private static final double MAX_DIST = 8;

    private RocketNosePayloads() {}

    public record OpenHeat(BlockPos pos, int mask) implements CustomPacketPayload {
        public static final Type<OpenHeat> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "heat_nose_open"));
        public static final StreamCodec<ByteBuf, OpenHeat> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenHeat::pos, ByteBufCodecs.VAR_INT, OpenHeat::mask, OpenHeat::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveHeat(BlockPos pos, int mask) implements CustomPacketPayload {
        public static final Type<SaveHeat> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "heat_nose_save"));
        public static final StreamCodec<ByteBuf, SaveHeat> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SaveHeat::pos, ByteBufCodecs.VAR_INT, SaveHeat::mask, SaveHeat::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Программируемый нос: режим (0 - автопуск, 1 - по редстоуну), допустимая цель (-1 - любая), сеть (имя). */
    public record OpenNet(BlockPos pos, int mode, int trigger, String network) implements CustomPacketPayload {
        public static final Type<OpenNet> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "net_nose_open"));
        public static final StreamCodec<ByteBuf, OpenNet> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenNet::pos, ByteBufCodecs.VAR_INT, OpenNet::mode, ByteBufCodecs.VAR_INT, OpenNet::trigger,
                ByteBufCodecs.stringUtf8(64), OpenNet::network, OpenNet::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveNet(BlockPos pos, int mode, int trigger) implements CustomPacketPayload {
        public static final Type<SaveNet> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "net_nose_save"));
        public static final StreamCodec<ByteBuf, SaveNet> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SaveNet::pos, ByteBufCodecs.VAR_INT, SaveNet::mode, ByteBufCodecs.VAR_INT, SaveNet::trigger, SaveNet::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(OpenHeat.TYPE, OpenHeat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.FcsClientHooks.openHeatNose(p)));
        r.playToClient(OpenNet.TYPE, OpenNet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.FcsClientHooks.openNetNose(p)));
        r.playToServer(SaveHeat.TYPE, SaveHeat.CODEC, RocketNosePayloads::handleHeat);
        r.playToServer(SaveNet.TYPE, SaveNet.CODEC, RocketNosePayloads::handleNet);
    }

    public static void openHeat(ServerPlayer sp, HeatNoseBlockEntity be) {
        PacketDistributor.sendToPlayer(sp, new OpenHeat(be.getBlockPos(), be.categories()));
    }

    public static void openNet(ServerPlayer sp, NetNoseBlockEntity be) {
        PacketDistributor.sendToPlayer(sp, new OpenNet(be.getBlockPos(), be.mode().ordinal(), be.trigger(),
                be.network() == null ? "" : RadarLinkBlockEntity.shortName(be.network())));
    }

    private static boolean near(ServerPlayer sp, BlockPos pos) {
        if (sp.isSpectator() || !sp.mayBuild() || !sp.level().isLoaded(pos)) return false;
        Vec3 w = dev.radarfcs.compat.SableCompat.toWorld(sp.level(), Vec3.atCenterOf(pos));
        return sp.distanceToSqr(w) <= MAX_DIST * MAX_DIST || sp.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_DIST * MAX_DIST;
    }

    private static void handleHeat(SaveHeat p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || !near(sp, p.pos())) return;
            if (!(sp.level().getBlockEntity(p.pos()) instanceof HeatNoseBlockEntity be)) return;
            be.setCategories(p.mask());
            sp.displayClientMessage(Component.translatable("radar_fcs.rocket_nose.saved").withStyle(ChatFormatting.GREEN), true);
        });
    }

    private static void handleNet(SaveNet p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || !near(sp, p.pos())) return;
            if (!(sp.level().getBlockEntity(p.pos()) instanceof NetNoseBlockEntity be)) return;
            NetNoseBlockEntity.Mode[] modes = NetNoseBlockEntity.Mode.values();
            be.apply(modes[Math.floorMod(p.mode(), modes.length)], p.trigger());
            sp.displayClientMessage(Component.translatable("radar_fcs.rocket_nose.saved").withStyle(ChatFormatting.GREEN), true);
        });
    }
}
