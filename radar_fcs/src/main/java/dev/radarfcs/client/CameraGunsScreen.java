package dev.radarfcs.client;

import dev.radarfcs.net.CameraPayloads;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Какими орудиями управляет камера: отмеченные - наводятся по её взгляду и стреляют по ЛКМ, остальные в это время
 * продолжают автоприцеливание (или слушаются другой камеры). Ничего не отмечено - все орудия сети.
 */
public final class CameraGunsScreen extends Screen {
    private static final int W = 360, ROW = 22, ROWS = 8;

    private final Screen parent;
    private final BlockPos monitor, camera;
    private final String cameraName;
    private final List<CameraMonitorScreen.Gun> guns;
    private final Set<Long> selected = new LinkedHashSet<>();
    private int left, top, scroll;

    public CameraGunsScreen(Screen parent, BlockPos monitor, BlockPos camera, String cameraName, List<CameraMonitorScreen.Gun> guns, long[] selected) {
        super(Component.translatable("radar_fcs.camera_gui.guns_title"));
        this.parent = parent;
        this.monitor = monitor;
        this.camera = camera;
        this.cameraName = cameraName;
        this.guns = guns;
        for (long k : selected) this.selected.add(k);
    }

    private int height() {
        return 84 + ROWS * ROW;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - height()) / 2;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, guns.size() - ROWS)));
        for (int i = 0; i < ROWS && scroll + i < guns.size(); i++) {
            CameraMonitorScreen.Gun g = guns.get(scroll + i);
            addRenderableWidget(Button.builder(label(g), b -> {
                if (!selected.remove(g.key())) selected.add(g.key());
                b.setMessage(label(g));
            }).bounds(left + 8, top + 24 + i * ROW, W - 16, 20).build());
        }
        int y = top + height() - 26;
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.camera_gui.guns_all"), b -> {
            selected.clear();
            rebuildWidgets();
        }).bounds(left + 8, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gun.save"), b -> {
            List<Long> keys = new ArrayList<>(selected);
            // отмечены все орудия - то же, что «все» (и новые орудия сети тоже)
            if (keys.size() == guns.size()) keys.clear();
            PacketDistributor.sendToServer(new CameraPayloads.Guns(monitor, camera, keys));
        }).bounds(left + W / 2 - 50, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.camera_gui.back"), b -> minecraft.setScreen(parent))
                .bounds(left + W - 108, y, 100, 20).build());
    }

    private Component label(CameraMonitorScreen.Gun g) {
        boolean on = selected.isEmpty() || selected.contains(g.key());
        return Component.literal((on ? "☑ " : "☐ ") + g.label()).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + height(), 0xE0101418);
        g.renderOutline(left, top, W, height(), 0xFF5A6A7A);
        g.drawCenteredString(font, title, width / 2, top - 12, 0x7FD4FF);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, Component.translatable("radar_fcs.camera_gui.guns_header", cameraName,
                selected.isEmpty() ? Component.translatable("radar_fcs.camera_gui.guns_all") : Component.literal(selected.size() + " / " + guns.size())),
                left + 8, top + 8, 0xA0A0A0, false);
        if (guns.isEmpty()) {
            g.drawWordWrap(font, Component.translatable("radar_fcs.camera_gui.guns_empty").withStyle(ChatFormatting.GRAY), left + 8, top + 30, W - 16, 0xA0A0A0);
        }
        g.drawWordWrap(font, Component.translatable("radar_fcs.camera_gui.guns_hint"), left + 8, top + height() - 52, W - 16, 0x8A9098);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        int before = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(dy), Math.max(0, guns.size() - ROWS)));
        if (scroll != before) rebuildWidgets();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
