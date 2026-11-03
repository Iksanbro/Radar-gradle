package dev.radarfcs.client;

import dev.radarfcs.net.CameraPayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Окно монитора камер: камеры сети - имя (Enter - сохранить), «Смотреть»; занятые другим игроком - серые. */
public final class CameraMonitorScreen extends Screen {
    private static final int W = 360, ROW = 22, ROWS = 8;

    private record Entry(BlockPos pos, String shown, String raw, boolean busy) {}

    private final BlockPos monitor;
    private final List<Entry> cameras = new ArrayList<>();
    private int left, top, scroll;

    public CameraMonitorScreen(CameraPayloads.Open p) {
        super(Component.translatable("radar_fcs.camera_gui.title"));
        monitor = p.monitor();
        for (Tag t : p.cameras().getList("c", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            cameras.add(new Entry(BlockPos.of(c.getLong("p")), c.getString("n"), c.getString("r"), c.getBoolean("b")));
        }
    }

    private int height() {
        return 64 + ROWS * ROW;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - height()) / 2;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, cameras.size() - ROWS)));
        for (int i = 0; i < ROWS && scroll + i < cameras.size(); i++) {
            Entry e = cameras.get(scroll + i);
            int y = top + 24 + i * ROW;
            EditBox name = new EditBox(font, left + 8, y + 2, 180, 16, Component.empty()) {
                @Override
                public boolean keyPressed(int key, int scan, int mods) {
                    if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                        PacketDistributor.sendToServer(new CameraPayloads.Rename(monitor, e.pos(), getValue()));
                        return true;
                    }
                    return super.keyPressed(key, scan, mods);
                }
            };
            name.setMaxLength(24);
            name.setValue(e.raw());
            name.setHint(Component.literal(e.shown()).withStyle(ChatFormatting.DARK_GRAY));
            addRenderableWidget(name);
            Button view = addRenderableWidget(Button.builder(Component.translatable(e.busy() ? "radar_fcs.camera_gui.busy" : "radar_fcs.camera_gui.view"),
                    b -> PacketDistributor.sendToServer(new CameraPayloads.View(monitor, e.pos()))).bounds(left + W - 108, y, 100, 20).build());
            view.active = !e.busy();
        }
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W - 84, top + height() - 24, 80, 20).build());
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
        g.drawString(font, Component.translatable("radar_fcs.camera_gui.header", cameras.size()), left + 8, top + 8, 0xA0A0A0, false);
        if (cameras.isEmpty()) {
            g.drawWordWrap(font, Component.translatable("radar_fcs.camera_gui.empty").withStyle(ChatFormatting.GRAY), left + 8, top + 30, W - 16, 0xA0A0A0);
        }
        g.drawWordWrap(font, Component.translatable("radar_fcs.camera_gui.hint").withStyle(ChatFormatting.GRAY), left + 8, top + height() - 34,
                W - 100, 0xA0A0A0);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        int before = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(dy), Math.max(0, cameras.size() - ROWS)));
        if (scroll != before) rebuildWidgets();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
