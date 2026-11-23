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
 * цель под перекрестием и дальность. Луч - зелёный, у всех игроков с целеуказателем в прицеливании; ЛКМ - назначить цель
 * под перекрестием (в воздух - снять, сервер {@code LaserDesignatorItem.fire}), при назначении луч на миг красный.
 */
@EventBusSubscriber(modid = FcsMod.MODID, value = Dist.CLIENT)
public final class DesignatorClient {
    private static final ResourceLocation SCOPE = ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "textures/misc/designator_scope.png");
    private static final double RANGE = 512;
    private static final float[] ZOOMS = {2, 4, 6, 10};
    private static int zoom = 1;
    /** До какого игрового тика луч красный (цель только что назначена). */
    private static long redUntil = Long.MIN_VALUE;
    private static final int GREEN = 0x30FF50, RED = 0xFF2020;

    private DesignatorClient() {}

    /** Игрок прицеливается целеуказателем (от первого лица). */
    static boolean aiming() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        return p != null && p.isUsingItem() && p.getUseItem().is(FcsRegistry.LASER_DESIGNATOR.get())
                && mc.options.getCameraType().isFirstPerson();
    }

    /** ЛКМ при прицеливании - назначить цель (до обработки клавиш игрой: она при использовании предмета ЛКМ глотает). */
    @SubscribeEvent
    public static void clicks(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isUsingItem() || !mc.player.getUseItem().is(FcsRegistry.LASER_DESIGNATOR.get())) return;
        boolean fire = false;
        while (mc.options.keyAttack.consumeClick()) fire = true;
        if (fire) net.neoforged.neoforge.network.PacketDistributor.sendToServer(new dev.radarfcs.net.DesignatorPayloads.Fire());
    }

    /** Ответ сервера на ЛКМ: цель назначена - луч на миг красный. */
    public static void onResult(boolean designated) {
        Minecraft mc = Minecraft.getInstance();
        if (designated && mc.level != null) redUntil = mc.level.getGameTime() + 15;
    }

    /** Лучи целеуказателей всех игроков, которые прицеливаются (до точки под их перекрестием). */
    @SubscribeEvent
    public static void beams(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        for (Player p : mc.level.players()) {
            if (!p.isUsingItem() || !p.getUseItem().is(FcsRegistry.LASER_DESIGNATOR.get())) continue;
            Vec3 eye = p.getEyePosition(pt), look = p.getViewVector(pt);
            Vec3 end = hitPoint(mc, p, eye, look, pt);
            Vec3 right = look.cross(new Vec3(0, 1, 0));
            right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
            Vec3 up = right.cross(look).normalize();
            boolean self = p == mc.player && mc.options.getCameraType().isFirstPerson();
            // начало луча - у передней линзы: от первого лица - чуть ниже и правее глаза, иначе - у руки
            Vec3 start = self ? eye.add(look.scale(0.6)).add(right.scale(0.08)).subtract(up.scale(0.09))
                    : eye.add(look.scale(0.55)).add(right.scale(p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? 0.3 : -0.3)).subtract(up.scale(0.2));
            int color = p == mc.player && mc.level.getGameTime() < redUntil ? RED : GREEN;
            net.createmod.catnip.outliner.Outliner.getInstance().showLine("radar_fcs:designator_beam_" + p.getId(), start, end)
                    .colored(color).lineWidth(self ? 1 / 64f : 1 / 32f);
        }
    }

    /** Точка под перекрестием игрока: первая сущность или блок (в том числе постройки Sable) на луче, иначе конец луча. */
    private static Vec3 hitPoint(Minecraft mc, Player p, Vec3 eye, Vec3 look, float pt) {
        Vec3 end = eye.add(look.scale(RANGE));
        HitResult block = p.pick(RANGE, pt, false);
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(p, eye, stop, new AABB(eye, stop).inflate(1.0),
                e -> !e.isSpectator() && e != p, eye.distanceToSqr(stop));
        return eh != null ? eh.getLocation() : stop;
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
        boolean red = mc.level.getGameTime() < redUntil;
        g.drawCenteredString(mc.font, Component.translatable(red ? "radar_fcs.designator.designated_hint" : "radar_fcs.designator.aim_hint"),
                cx, h - 24, red ? 0xFF6060 : 0xC0C0C0);
    }
}
