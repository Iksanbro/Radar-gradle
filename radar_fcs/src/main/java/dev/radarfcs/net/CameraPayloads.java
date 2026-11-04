package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.CameraBlockEntity;
import dev.radarfcs.block.CameraMonitorBlockEntity;
import dev.radarfcs.link.LinkRegistry;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Камеры: список камер сети монитора (сервер -> клиент), выбор камеры и начало вида, команды взгляда и огня,
 * выход, переименование. Управлять можно, стоя у монитора камер (не дальше MAX_DIST блоков).
 */
public final class CameraPayloads {
    private CameraPayloads() {}

    public static final double MAX_DIST = 8;

    /** Сервер -> клиент: камеры сети монитора. cameras - список {p: позиция, n: имя для показа, r: заданное имя, b: занята}. */
    public record Open(BlockPos monitor, CompoundTag cameras) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "camera_list"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::monitor, ByteBufCodecs.COMPOUND_TAG, Open::cameras, Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: смотреть через камеру. */
    public record View(BlockPos monitor, BlockPos camera) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "camera_view"));
        public static final StreamCodec<ByteBuf, View> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, View::monitor, BlockPos.STREAM_CODEC, View::camera, View::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Сервер -> клиент: вид разрешён - глаз камеры (x, y, z), начальный взгляд (yaw, pitch) и имя (name). */
    public record Start(BlockPos monitor, BlockPos camera, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Start> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "camera_start"));
        public static final StreamCodec<ByteBuf, Start> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Start::monitor, BlockPos.STREAM_CODEC, Start::camera, ByteBufCodecs.COMPOUND_TAG, Start::data,
                Start::new);

        public double x() { return data.getDouble("x"); }
        public double y() { return data.getDouble("y"); }
        public double z() { return data.getDouble("z"); }
        public float yaw() { return data.getFloat("yaw"); }
        public float pitch() { return data.getFloat("pitch"); }
        public String name() { return data.getString("name"); }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер (каждый тик вида): взгляд камеры и зажат ли огонь. */
    public record Control(BlockPos camera, float yaw, float pitch, boolean fire) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "camera_control"));
        public static final StreamCodec<ByteBuf, Control> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Control::camera, ByteBufCodecs.FLOAT, Control::yaw, ByteBufCodecs.FLOAT, Control::pitch,
                ByteBufCodecs.BOOL, Control::fire, Control::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: выйти из камеры. */
    public record Stop(BlockPos camera) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "camera_stop"));
        public static final StreamCodec<ByteBuf, Stop> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Stop::camera, Stop::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Сервер -> клиент: выйти из камеры (у монитора нет энергии). */
    public record Kick(BlockPos camera) implements CustomPacketPayload {
        public static final Type<Kick> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "camera_kick"));
        public static final StreamCodec<ByteBuf, Kick> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Kick::camera, Kick::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: имя камеры. */
    public record Rename(BlockPos monitor, BlockPos camera, String name) implements CustomPacketPayload {
        public static final Type<Rename> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "camera_rename"));
        public static final StreamCodec<ByteBuf, Rename> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Rename::monitor, BlockPos.STREAM_CODEC, Rename::camera, ByteBufCodecs.stringUtf8(64), Rename::name,
                Rename::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(Open.TYPE, Open.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.CameraClient.openList(p)));
        r.playToClient(Start.TYPE, Start.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.CameraClient.start(p)));
        r.playToServer(View.TYPE, View.CODEC, CameraPayloads::handleView);
        r.playToServer(Control.TYPE, Control.CODEC, CameraPayloads::handleControl);
        r.playToServer(Stop.TYPE, Stop.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.level().isLoaded(p.camera())
                    && sp.level().getBlockEntity(p.camera()) instanceof CameraBlockEntity cam) cam.stopControl(sp.getUUID());
        }));
        r.playToServer(Rename.TYPE, Rename.CODEC, CameraPayloads::handleRename);
        r.playToClient(Kick.TYPE, Kick.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.CameraClient.kick(p.camera())));
    }

    /** Камеры сети монитора (через работающие линки). */
    public static List<CameraBlockEntity> cameras(ServerLevel level, BlockPos monitor) {
        List<CameraBlockEntity> out = new ArrayList<>();
        for (BlockPos p : LinkRegistry.linkedTo(level, monitor)) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof CameraBlockEntity cam) out.add(cam);
        }
        out.sort(java.util.Comparator.comparing(c -> c.displayName().getString()));
        return out;
    }

    /** Открыть игроку список камер монитора. */
    public static void openList(ServerPlayer sp, BlockPos monitor) {
        ServerLevel level = sp.serverLevel();
        if (!(level.getBlockEntity(monitor) instanceof CameraMonitorBlockEntity)) return;
        ListTag list = new ListTag();
        long now = level.getGameTime();
        for (CameraBlockEntity cam : cameras(level, monitor)) {
            CompoundTag c = new CompoundTag();
            c.putLong("p", cam.getBlockPos().asLong());
            c.putString("n", cam.displayName().getString());
            c.putString("r", cam.name());
            java.util.UUID who = cam.controller(now);
            c.putBoolean("b", who != null && !who.equals(sp.getUUID()));
            list.add(c);
        }
        CompoundTag t = new CompoundTag();
        t.put("c", list);
        PacketDistributor.sendToPlayer(sp, new Open(monitor, t));
    }

    private static boolean nearMonitor(ServerPlayer sp, BlockPos monitor) {
        return sp.level().isLoaded(monitor) && sp.level().getBlockEntity(monitor) instanceof CameraMonitorBlockEntity
                && sp.distanceToSqr(Vec3.atCenterOf(monitor)) <= MAX_DIST * MAX_DIST;
    }

    private static void handleView(View p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !nearMonitor(sp, p.monitor())) return;
            ServerLevel level = sp.serverLevel();
            if (!level.isLoaded(p.camera()) || !(level.getBlockEntity(p.camera()) instanceof CameraBlockEntity cam)) return;
            if (!LinkRegistry.linked(level, p.monitor(), p.camera())) {
                sp.displayClientMessage(Component.translatable("radar_fcs.camera.not_linked").withStyle(ChatFormatting.RED), true);
                return;
            }
            if (level.getBlockEntity(p.monitor()) instanceof CameraMonitorBlockEntity mon && !mon.powered()) {
                sp.displayClientMessage(Component.translatable("radar_fcs.camera_monitor.no_energy", mon.usage()).withStyle(ChatFormatting.RED), true);
                return;
            }
            if (!cam.startControl(sp, p.monitor())) {
                sp.displayClientMessage(Component.translatable("radar_fcs.camera.busy").withStyle(ChatFormatting.RED), true);
                return;
            }
            Vec3 eye = cam.eye();
            float[] v = cam.view();
            CompoundTag d = new CompoundTag();
            d.putDouble("x", eye.x);
            d.putDouble("y", eye.y);
            d.putDouble("z", eye.z);
            d.putFloat("yaw", v[0]);
            d.putFloat("pitch", v[1]);
            d.putString("name", cam.displayName().getString());
            PacketDistributor.sendToPlayer(sp, new Start(p.monitor(), p.camera(), d));
        });
    }

    private static void handleControl(Control p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator()) return;
            ServerLevel level = sp.serverLevel();
            if (!level.isLoaded(p.camera()) || !(level.getBlockEntity(p.camera()) instanceof CameraBlockEntity cam)) return;
            if (!sp.getUUID().equals(cam.controller(level.getGameTime()))) return;
            BlockPos monitor = cam.controlMonitor();
            if (monitor == null || !nearMonitor(sp, monitor) || !LinkRegistry.linked(level, monitor, p.camera())) {
                cam.stopControl(sp.getUUID());
                return;
            }
            if (level.getBlockEntity(monitor) instanceof CameraMonitorBlockEntity mon && !mon.powered()) {
                cam.stopControl(sp.getUUID());
                PacketDistributor.sendToPlayer(sp, new Kick(p.camera()));
                sp.displayClientMessage(Component.translatable("radar_fcs.camera_monitor.no_energy", mon.usage()).withStyle(ChatFormatting.RED), true);
                return;
            }
            if (!Float.isFinite(p.yaw()) || !Float.isFinite(p.pitch())) return;
            cam.control(sp, p.yaw(), p.pitch(), p.fire());
        });
    }

    private static void handleRename(Rename p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !sp.mayBuild() || !nearMonitor(sp, p.monitor())) return;
            ServerLevel level = sp.serverLevel();
            if (!level.isLoaded(p.camera()) || !(level.getBlockEntity(p.camera()) instanceof CameraBlockEntity cam)) return;
            if (!LinkRegistry.linked(level, p.monitor(), p.camera())) return;
            cam.setName(p.name());
            openList(sp, p.monitor());
        });
    }
}
