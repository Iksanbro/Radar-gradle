package dev.radarfcs.client;

import dev.radarfcs.FcsMod;
import dev.radarfcs.block.CameraBlockEntity;
import dev.radarfcs.net.CameraPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Вид от лица камеры. Мышь поворачивает игрока, а взгляд камеры повторяет его поворот; ходьба и удары отключены,
 * зажатая ЛКМ - огонь пушек сети по точке взгляда, Shift - выход (взгляд игрока возвращается).
 */
@EventBusSubscriber(modid = FcsMod.MODID, value = Dist.CLIENT)
public final class CameraClient {
    @Nullable private static BlockPos camera, monitor;
    private static Vec3 eye = Vec3.ZERO;
    private static String name = "";
    @Nullable private static Marker view;
    private static float savedYaw, savedPitch;
    /** Shift отпущен после входа: только тогда Shift выводит из камеры. */
    private static boolean shiftReleased;

    private CameraClient() {}

    public static boolean active() {
        return camera != null;
    }

    /** Камера, через которую сейчас смотрит этот игрок, или null. */
    @Nullable
    public static BlockPos viewedCamera() {
        return camera;
    }

    public static void openList(CameraPayloads.Open p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.setScreen(new CameraMonitorScreen(p));
    }

    public static void start(CameraPayloads.Start p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (active()) stop(true);
        mc.setScreen(null);
        savedYaw = mc.player.getYRot();
        savedPitch = mc.player.getXRot();
        setPlayerRot(mc, p.yaw(), p.pitch());
        camera = p.camera();
        monitor = p.monitor();
        eye = new Vec3(p.x(), p.y(), p.z());
        name = p.name();
        Marker m = new Marker(EntityType.MARKER, mc.level);
        m.setPos(eye.x, eye.y, eye.z);
        m.xo = m.xOld = eye.x;
        m.yo = m.yOld = eye.y;
        m.zo = m.zOld = eye.z;
        m.setYRot(p.yaw());
        m.setXRot(p.pitch());
        view = m;
        mc.setCameraEntity(m);
        shiftReleased = !mc.options.keyShift.isDown();
    }

    private static void setPlayerRot(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) return;
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setYHeadRot(yaw);
    }

    /** Сервер выкинул из камеры (у монитора нет энергии). */
    public static void kick(BlockPos cam) {
        if (cam.equals(camera)) stop(false);
    }

    /** Выйти из камеры (send - сообщить серверу). */
    public static void stop(boolean send) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos cam = camera;
        camera = null;
        monitor = null;
        view = null;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            setPlayerRot(mc, savedYaw, savedPitch);
        }
        if (send && cam != null && mc.getConnection() != null) PacketDistributor.sendToServer(new CameraPayloads.Stop(cam));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) {
            stop(false);
            return;
        }
        // камера снята или игрок ушёл от монитора
        if (mc.level.isLoaded(camera) && !(mc.level.getBlockEntity(camera) instanceof CameraBlockEntity)) {
            stop(true);
            return;
        }
        if (monitor != null && mc.player.distanceToSqr(Vec3.atCenterOf(monitor)) > CameraPayloads.MAX_DIST * CameraPayloads.MAX_DIST) {
            stop(true);
            return;
        }
        if (mc.options.keyShift.isDown()) {
            if (shiftReleased) {
                stop(true);
                return;
            }
        } else {
            shiftReleased = true;
        }
        if (mc.getCameraEntity() != view && view != null) mc.setCameraEntity(view);
        // камера на движущейся постройке Sable: вид едет вместе с ней (плавно, между тиками)
        if (view != null && mc.level.isLoaded(camera) && mc.level.getBlockEntity(camera) instanceof CameraBlockEntity cbe) {
            Vec3 e = cbe.eye();
            view.xo = view.xOld = view.getX();
            view.yo = view.yOld = view.getY();
            view.zo = view.zOld = view.getZ();
            if (e.distanceToSqr(view.position()) > 1e-8) {
                view.setPos(e.x, e.y, e.z);
                eye = e;
            }
        }
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        if (view != null) {
            view.yRotO = view.getYRot();
            view.xRotO = view.getXRot();
            view.setYRot(yaw);
            view.setXRot(pitch);
        }
        boolean fire = mc.screen == null && mc.options.keyAttack.isDown();
        PacketDistributor.sendToServer(new CameraPayloads.Control(camera, yaw, pitch, fire));
    }

    /**
     * Камера на постройке Sable: глаз - каждый кадр по позе, в которой постройка рисуется (между тиками). По тикам
     * вид отставал от постройки и дёргался, когда орудие с камерой поворачивалось.
     */
    @SubscribeEvent
    public static void frame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || view == null || mc.level == null || !mc.level.isLoaded(camera)) return;
        if (!(mc.level.getBlockEntity(camera) instanceof CameraBlockEntity cbe)) return;
        if (dev.radarfcs.compat.SableCompat.containingId(mc.level, camera) == null) return;
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 e = dev.radarfcs.compat.SableCompat.toWorldRender(mc.level, camera, cbe.pivot(), pt);
        // xo = x: камера Minecraft берёт ровно эту точку при любой доле тика
        view.setPos(e.x, e.y, e.z);
        view.xo = view.xOld = e.x;
        view.yo = view.yOld = e.y;
        view.zo = view.zOld = e.z;
        eye = e;
    }

    /** Взгляд камеры - поворот игрока с точностью до кадра (мышь), а не по тикам. */
    @SubscribeEvent
    public static void angles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || mc.player == null) return;
        float pt = (float) event.getPartialTick();
        event.setYaw(mc.player.getViewYRot(pt));
        event.setPitch(mc.player.getViewXRot(pt));
    }

    /** Игрок у монитора стоит на месте. */
    @SubscribeEvent
    public static void movement(MovementInputUpdateEvent event) {
        if (!active()) return;
        var in = event.getInput();
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    /** Удары, использование и выбор блока через камеру не работают (ЛКМ - огонь). */
    @SubscribeEvent
    public static void input(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    @SubscribeEvent
    public static void hand(RenderHandEvent event) {
        if (active()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void hideHud(RenderGuiLayerEvent.Pre event) {
        if (!active()) return;
        // глаз внутри своего блока: «под прицелом» была бы сама камера (подсказки, очки инженера) - ничего не выделено
        Minecraft mc = Minecraft.getInstance();
        if (eye != null && mc.hitResult instanceof BlockHitResult bh && bh.getBlockPos().equals(camera)) {
            // «промах» с точкой вне мира: оверлеи (очки инженера) смотрят только на координаты блока
            mc.hitResult = BlockHitResult.miss(eye, net.minecraft.core.Direction.UP, new BlockPos(camera.getX(), 100_000, camera.getZ()));
        }
        var n = event.getName();
        if (n.equals(VanillaGuiLayers.HOTBAR) || n.equals(VanillaGuiLayers.CROSSHAIR) || n.equals(VanillaGuiLayers.EXPERIENCE_BAR)
                || n.equals(VanillaGuiLayers.PLAYER_HEALTH) || n.equals(VanillaGuiLayers.FOOD_LEVEL) || n.equals(VanillaGuiLayers.ARMOR_LEVEL)
                || n.equals(VanillaGuiLayers.SELECTED_ITEM_NAME)) {
            event.setCanceled(true);
        }
    }

    /** Прицел камеры, имя, расстояние до точки взгляда и подсказка. */
    @SubscribeEvent
    public static void overlay(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || mc.player == null || mc.level == null || mc.options.hideGui) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2, cy = h / 2;
        boolean fire = mc.options.keyAttack.isDown();
        int col = fire ? 0xFFFF4040 : 0xFFFFFFFF;
        g.fill(cx - 8, cy, cx - 2, cy + 1, col);
        g.fill(cx + 3, cy, cx + 9, cy + 1, col);
        g.fill(cx, cy - 8, cx + 1, cy - 2, col);
        g.fill(cx, cy + 3, cx + 1, cy + 9, col);
        g.renderOutline(4, 4, w - 8, h - 8, 0x60FFFFFF);
        Vec3 dir = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        // луч - от края своего блока (глаз в центре камеры, сам блок не помеха)
        BlockHitResult hit = mc.level.clip(new ClipContext(eye.add(dir.scale(0.9)), eye.add(dir.scale(CameraBlockEntity.RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        Vec3 stop = hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
        // постройки Sable (корабли) на луче - тоже точка взгляда
        var ship = dev.radarfcs.compat.SableCompat.clipShips(mc.level, eye.add(dir.scale(0.9)),
                stop != null ? stop : eye.add(dir.scale(CameraBlockEntity.RANGE)),
                id -> id.equals(dev.radarfcs.compat.SableCompat.containingId(mc.level, camera)), mc.level, false);
        if (ship != null) stop = ship.point();
        String dist = stop == null ? "-" : String.format(java.util.Locale.ROOT, "%.0f", stop.distanceTo(eye));
        g.drawString(mc.font, Component.literal(name).withStyle(ChatFormatting.AQUA), 10, 10, 0xFFFFFF);
        g.drawString(mc.font, Component.translatable("radar_fcs.camera.distance", dist), 10, 22, 0xFFFFFF);
        Component hint = Component.translatable(fire ? "radar_fcs.camera.firing" : "radar_fcs.camera.hint");
        g.drawCenteredString(mc.font, hint, cx, h - 20, fire ? 0xFF6060 : 0xC0C0C0);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        camera = null;
        monitor = null;
        view = null;
    }
}
