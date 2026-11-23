package dev.radarfcs.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.radarfcs.FcsMod;
import dev.radarfcs.registry.FcsRegistry;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Прицеливание лазерным целеуказателем (ПКМ держать, вид от первого лица): приближение (колесо мыши - от 2x до 10x),
 * сетка прицела по центру экрана, рука и обычное перекрестие скрыты, чувствительность мыши - по приближению; внизу -
 * цель под перекрестием и дальность. Отпустить ПКМ - назначить (сервер, {@code LaserDesignatorItem.releaseUsing}).
 */
@EventBusSubscriber(modid = FcsMod.MODID, value = Dist.CLIENT)
public final class DesignatorClient {
    private static final ResourceLocation SCOPE = ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "textures/misc/designator_scope.png");
    private static final double RANGE = 512;
    private static final float[] ZOOMS = {2, 4, 6, 10};
    private static int zoom = 1;

    private DesignatorClient() {}

    /** Игрок прицеливается целеуказателем (от первого лица). */
    static boolean aiming() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        return p != null && p.isUsingItem() && p.getUseItem().is(FcsRegistry.LASER_DESIGNATOR.get())
                && mc.options.getCameraType().isFirstPerson();
    }

    @SubscribeEvent
    public static void fov(ComputeFovModifierEvent event) {
        if (aiming() && event.getPlayer() == Minecraft.getInstance().player) event.setNewFovModifier(1f / ZOOMS[zoom]);
    }

    @SubscribeEvent
    public static void turn(CalculatePlayerTurnEvent event) {
        if (aiming()) event.setMouseSensitivity(event.getMouseSensitivity() / Math.sqrt(ZOOMS[zoom]) * 0.8);
    }

    @SubscribeEvent
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        if (!aiming()) return;
        int z = zoom + (event.getScrollDeltaY() > 0 ? 1 : event.getScrollDeltaY() < 0 ? -1 : 0);
        zoom = Math.max(0, Math.min(ZOOMS.length - 1, z));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void hand(RenderHandEvent event) {
        if (aiming()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void hideCrosshair(RenderGuiLayerEvent.Pre event) {
        if (aiming() && event.getName().equals(VanillaGuiLayers.CROSSHAIR)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void overlay(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!aiming() || mc.options.hideGui || mc.player == null || mc.level == null) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        int s = Math.min(w, h), x = (w - s) / 2, y = (h - s) / 2;
        RenderSystem.enableBlend();
        g.blit(SCOPE, x, y, 0, 0, s, s, s, s);
        if (x > 0) {
            g.fill(0, 0, x, h, 0xFF000000);
            g.fill(x + s, 0, w, h, 0xFF000000);
        }
        if (y > 0) {
            g.fill(0, 0, w, y, 0xFF000000);
            g.fill(0, y + s, w, h, 0xFF000000);
        }
        RenderSystem.disableBlend();
        // цель под перекрестием и дальность (луч на клиенте - только для подсказки, назначает сервер)
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 eye = mc.player.getEyePosition(pt), end = eye.add(mc.player.getViewVector(pt).scale(RANGE));
        HitResult block = mc.player.pick(RANGE, pt, false);
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(mc.player, eye, stop, new AABB(eye, stop).inflate(1.0),
                e -> !e.isSpectator() && e != mc.player, eye.distanceToSqr(stop));
        String dist;
        Component what;
        if (eh != null) {
            Entity e = eh.getEntity();
            dist = String.format(Locale.ROOT, "%.0f", e.position().distanceTo(eye));
            what = e.getDisplayName().copy().withStyle(ChatFormatting.RED);
        } else if (block.getType() != HitResult.Type.MISS) {
            dist = String.format(Locale.ROOT, "%.0f", stop.distanceTo(eye));
            what = Component.translatable("radar_fcs.designator.no_target").withStyle(ChatFormatting.GRAY);
        } else {
            dist = "-";
            what = Component.translatable("radar_fcs.designator.no_target").withStyle(ChatFormatting.GRAY);
        }
        int cx = w / 2;
        g.drawCenteredString(mc.font, what, cx, h / 2 + s / 6, 0xFFFFFF);
        g.drawCenteredString(mc.font, Component.translatable("radar_fcs.designator.range", dist,
                String.format(Locale.ROOT, "%.0f", ZOOMS[zoom])), cx, h / 2 + s / 6 + 12, 0xE0E0E0);
        g.drawCenteredString(mc.font, Component.translatable("radar_fcs.designator.aim_hint"), cx, h - 24, 0xC0C0C0);
    }
}
