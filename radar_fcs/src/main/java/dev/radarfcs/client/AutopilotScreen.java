package dev.radarfcs.client;

import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.net.AutopilotPayloads;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Экран моста Flight Control: поворот корпуса к цели (вкл/выкл), режим (выкл, только дальность, дальность и высота),
 * дальность, высота над целью и предел скорости - числами с клавиатуры.
 */
public final class AutopilotScreen extends Screen {
    private static final int W = 300, H = 222;

    private final BlockPos pos;
    private boolean heading;
    private Autopilot.Mode mode;
    private final double distance0, height0, speed0;
    private int left, top;
    private EditBox distField, heightField, speedField;

    public AutopilotScreen(AutopilotPayloads.Open p) {
        super(Component.translatable("radar_fcs.autopilot.title"));
        pos = p.pos();
        CompoundTag t = p.settings();
        heading = !t.contains("Heading") || t.getBoolean("Heading");
        Autopilot.Mode m;
        try {
            m = Autopilot.Mode.valueOf(t.getString("Mode"));
        } catch (IllegalArgumentException e) {
            m = Autopilot.Mode.DISTANCE;
        }
        mode = m;
        distance0 = Autopilot.cleanDistance(t.getDouble("Distance"));
        height0 = Autopilot.cleanHeight(t.getDouble("Height"));
        speed0 = Autopilot.cleanSpeed(t.getDouble("MaxSpeed"));
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int x = left + 170, w = W - 180;
        addRenderableWidget(Button.builder(onOff(heading), b -> {
            heading = !heading;
            b.setMessage(onOff(heading));
        }).bounds(x, top + 26, w, 18).build());
        addRenderableWidget(Button.builder(Component.translatable(mode.key()), b -> {
            Autopilot.Mode[] v = Autopilot.Mode.values();
            mode = v[(mode.ordinal() + 1) % v.length];
            b.setMessage(Component.translatable(mode.key()));
            updateFields();
        }).bounds(left + 8, top + 50, W - 16, 18).build());
        distField = field(x, top + 74, w, keep(distField, distance0));
        heightField = field(x, top + 96, w, keep(heightField, height0));
        speedField = field(x, top + 118, w, keep(speedField, speed0));
        updateFields();
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gun.save"), b -> {
            CompoundTag t = new CompoundTag();
            t.putBoolean("Heading", heading);
            t.putString("Mode", mode.name());
            t.putDouble("Distance", parse(distField, distance0, Autopilot::cleanDistance));
            t.putDouble("Height", parse(heightField, height0, Autopilot::cleanHeight));
            t.putDouble("MaxSpeed", parse(speedField, speed0, Autopilot::cleanSpeed));
            PacketDistributor.sendToServer(new AutopilotPayloads.Save(pos, t));
            onClose();
        }).bounds(left + W / 2 - 104, top + H - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W / 2 + 4, top + H - 26, 100, 20).build());
    }

    private static String keep(EditBox old, double initial) {
        return old != null ? old.getValue() : AutopilotBlockEntity.fmt(initial);
    }

    private EditBox field(int x, int y, int w, String value) {
        EditBox b = new EditBox(font, x, y, w, 16, Component.empty());
        b.setMaxLength(8);
        b.setFilter(s -> s.matches("-?[0-9]*([.,][0-9]*)?"));
        b.setValue(value);
        return addRenderableWidget(b);
    }

    private static double parse(EditBox b, double fallback, DoubleUnaryOperator clean) {
        try {
            return clean.applyAsDouble(Double.parseDouble(b.getValue().replace(',', '.')));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Поля дальности - только с режимом дальности, высота - только в режиме «дальность и высота». */
    private void updateFields() {
        distField.setEditable(mode != Autopilot.Mode.OFF);
        speedField.setEditable(mode != Autopilot.Mode.OFF);
        heightField.setEditable(mode == Autopilot.Mode.DISTANCE_ALTITUDE);
        distField.setTextColor(mode != Autopilot.Mode.OFF ? 0xE0E0E0 : 0x606060);
        speedField.setTextColor(mode != Autopilot.Mode.OFF ? 0xE0E0E0 : 0x606060);
        heightField.setTextColor(mode == Autopilot.Mode.DISTANCE_ALTITUDE ? 0xE0E0E0 : 0x606060);
    }

    private static Component onOff(boolean on) {
        return Component.translatable(on ? "radar_fcs.gun.on" : "radar_fcs.gun.off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + H, 0xE0101418);
        g.renderOutline(left, top, W, H, 0xFF3A4048);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, left + 8, top + 8, 0xFFD27F, false);
        g.drawString(font, Component.translatable("radar_fcs.autopilot.heading"), left + 8, top + 31, 0xC0C0C0, false);
        int off = mode != Autopilot.Mode.OFF ? 0xC0C0C0 : 0x606060;
        g.drawString(font, Component.translatable("radar_fcs.autopilot.distance"), left + 8, top + 78, off, false);
        g.drawString(font, Component.translatable("radar_fcs.autopilot.height"), left + 8, top + 100,
                mode == Autopilot.Mode.DISTANCE_ALTITUDE ? 0xC0C0C0 : 0x606060, false);
        g.drawString(font, Component.translatable("radar_fcs.autopilot.max_speed"), left + 8, top + 122, off, false);
        g.drawWordWrap(font, Component.translatable(mode.key() + ".hint"), left + 8, top + 142, W - 16, 0x8A9098);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
