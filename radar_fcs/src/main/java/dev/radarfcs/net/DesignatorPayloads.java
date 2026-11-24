package dev.radarfcs.net;

import dev.radarfcs.FcsMod;
import dev.radarfcs.item.LaserDesignatorItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Лазерный целеуказатель: ЛКМ и нажатие колеса мыши при прицеливании (клиент -> сервер) и итог - цель назначена или нет
 * (сервер -> клиент).
 */
public final class DesignatorPayloads {
    private DesignatorPayloads() {}

    /** ЛКМ при прицеливании: назначить цель под перекрестием (в воздух - снять назначение). */
    public record Fire() implements CustomPacketPayload {
        public static final Type<Fire> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "designator_fire"));
        public static final StreamCodec<ByteBuf, Fire> CODEC = StreamCodec.unit(new Fire());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Нажатие колеса мыши при прицеливании: исключить цель под перекрестием из огня (исключённую - вернуть). */
    public record Exclude() implements CustomPacketPayload {
        public static final Type<Exclude> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "designator_exclude"));
        public static final StreamCodec<ByteBuf, Exclude> CODEC = StreamCodec.unit(new Exclude());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Итог: цель назначена (луч на миг красный). */
    public record Result(boolean designated) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "designator_result"));
        public static final StreamCodec<ByteBuf, Result> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Result::designated, Result::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(Fire.TYPE, Fire.CODEC, DesignatorPayloads::handleFire);
        r.playToServer(Exclude.TYPE, Exclude.CODEC, DesignatorPayloads::handleExclude);
        r.playToClient(Result.TYPE, Result.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.radarfcs.client.DesignatorClient.onResult(p.designated())));
    }

    private static void handleFire(Fire p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !(sp.level() instanceof ServerLevel sl)) return;
            ItemStack stack = sp.getUseItem();
            if (!sp.isUsingItem() || !(stack.getItem() instanceof LaserDesignatorItem item)) return;
            boolean ok = item.fire(sl, sp, stack);
            PacketDistributor.sendToPlayer(sp, new Result(ok));
        });
    }

    private static void handleExclude(Exclude p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || sp.isSpectator() || !(sp.level() instanceof ServerLevel sl)) return;
            ItemStack stack = sp.getUseItem();
            if (sp.isUsingItem() && stack.getItem() instanceof LaserDesignatorItem item) item.exclude(sl, sp, stack);
        });
    }
}
