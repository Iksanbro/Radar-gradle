package dev.radarfcs.client;

import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.core.Docking;
import dev.radarfcs.net.AutopilotPayloads;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Экран моста Flight Control: оси ведения цели (рыскание, крен, тангаж) и пределы крена и тангажа, режим (выкл, только дальность, дальность и высота),
 * дальность, высота над целью и предел скорости - числами с клавиатуры.
 */
public final class AutopilotScreen extends Screen {
    private static final int W = 300, H = 286;

    private final BlockPos pos;
    private boolean heading, pitch, roll;
    private Autopilot.Mode mode;
    private Docking.Trigger dock;
    private Docking.Busy busy;
    private final double distance0, height0, speed0, roll0, pitch0;
    private int left, top;
    private EditBox distField, heightField, speedField, rollField, pitchField;

    public AutopilotScreen(AutopilotPayloads.Open p) {
        super(Component.translatable("radar_fcs.autopilot.title"));
        pos = p.pos();
        CompoundTag t = p.settings();
        heading = !t.contains("Heading") || t.getBoolean("Heading");
        pitch = t.getBoolean("Pitch");
        roll = t.getBoolean("Roll");
        Autopilot.Mode m;
        try {
            m = Autopilot.Mode.valueOf(t.getString("Mode"));
        } catch (IllegalArgumentException e) {
            m = Autopilot.Mode.DISTANCE;
        }
        mode = m;
        dock = AutopilotBlockEntity.enumOr(Docking.Trigger.class, t.getString("Dock"), Docking.Trigger.OFF);
        busy = AutopilotBlockEntity.enumOr(Docking.Busy.class, t.getString("DockBusy"), Docking.Busy.WAIT);
        distance0 = Autopilot.cleanDistance(t.getDouble("Distance"));
        height0 = Autopilot.cleanHeight(t.getDouble("Height"));
        speed0 = Autopilot.cleanSpeed(t.getDouble("MaxSpeed"));
        roll0 = Autopilot.cleanRoll(t.contains("MaxRoll") ? t.getDouble("MaxRoll") : Double.NaN);
        pitch0 = Autopilot.cleanPitch(t.contains("MaxPitch") ? t.getDouble("MaxPitch") : Double.NaN);
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int x = left + 170, w = W - 180;
        // оси ведения цели: рыскание, крен, тангаж
        int bw = (W - 24) / 3;
        addRenderableWidget(Button.builder(axis("yaw", heading), b -> {
            heading = !heading;
            b.setMessage(axis("yaw", heading));
        }).bounds(left + 8, top + 36, bw, 18).build());
        addRenderableWidget(Button.builder(axis("roll", roll), b -> {
            roll = !roll;
            b.setMessage(axis("roll", roll));
            updateFields();
        }).bounds(left + 12 + bw, top + 36, bw, 18).build());
        addRenderableWidget(Button.builder(axis("pitch", pitch), b -> {
            pitch = !pitch;
            b.setMessage(axis("pitch", pitch));
            updateFields();
        }).bounds(left + 16 + 2 * bw, top + 36, bw, 18).build());
        rollField = field(x, top + 60, w, keep(rollField, roll0));
        pitchField = field(x, top + 82, w, keep(pitchField, pitch0));
        addRenderableWidget(Button.builder(Component.translatable(mode.key()), b -> {
            Autopilot.Mode[] v = Autopilot.Mode.values();
            mode = v[(mode.ordinal() + 1) % v.length];
            b.setMessage(Component.translatable(mode.key()));
            updateFields();
        }).bounds(left + 8, top + 106, W - 16, 18).build());
        distField = field(x, top + 130, w, keep(distField, distance0));
        heightField = field(x, top + 152, w, keep(heightField, height0));
        speedField = field(x, top + 174, w, keep(speedField, speed0));
        // стыковка: когда лететь и что делать, если порты заняты
        int hw = (W - 20) / 2;
        addRenderableWidget(Button.builder(Component.translatable(dock.key()), b -> {
            Docking.Trigger[] v = Docking.Trigger.values();
            dock = v[(dock.ordinal() + 1) % v.length];
            b.setMessage(Component.translatable(dock.key()));
            b.setTooltip(Tooltip.create(Component.translatable(dock.key() + ".hint")));
        }).bounds(left + 8, top + 198, hw, 18).tooltip(Tooltip.create(Component.translatable(dock.key() + ".hint"))).build());
        addRenderableWidget(Button.builder(Component.translatable(busy.key()), b -> {
            Docking.Busy[] v = Docking.Busy.values();
            busy = v[(busy.ordinal() + 1) % v.length];
            b.setMessage(Component.translatable(busy.key()));
        }).bounds(left + 12 + hw, top + 198, hw, 18).build());
        updateFields();
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gun.save"), b -> {
            CompoundTag t = new CompoundTag();
            t.putBoolean("Heading", heading);
            t.putBoolean("Pitch", pitch);
            t.putBoolean("Roll", roll);
            t.putDouble("MaxRoll", parse(rollField, roll0, Autopilot::cleanRoll));
            t.putDouble("MaxPitch", parse(pitchField, pitch0, Autopilot::cleanPitch));
            t.putString("Mode", mode.name());
            t.putDouble("Distance", parse(distField, distance0, Autopilot::cleanDistance));
            t.putDouble("Height", parse(heightField, height0, Autopilot::cleanHeight));
            t.putDouble("MaxSpeed", parse(speedField, speed0, Autopilot::cleanSpeed));
            t.putString("Dock", dock.name());
            t.putString("DockBusy", busy.name());
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
        rollField.setEditable(roll);
        pitchField.setEditable(pitch);
        rollField.setTextColor(roll ? 0xE0E0E0 : 0x606060);
        pitchField.setTextColor(pitch ? 0xE0E0E0 : 0x606060);
    }

    private static Component axis(String name, boolean on) {
        return Component.translatable("radar_fcs.autopilot.axis." + name).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
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
        g.drawString(font, Component.translatable("radar_fcs.autopilot.axes_title"), left + 8, top + 24, 0xC0C0C0, false);
        g.drawString(font, Component.translatable("radar_fcs.autopilot.max_roll"), left + 8, top + 64, roll ? 0xC0C0C0 : 0x606060, false);
        g.drawString(font, Component.translatable("radar_fcs.autopilot.max_pitch"), left + 8, top + 86, pitch ? 0xC0C0C0 : 0x606060, false);
        int off = mode != Autopilot.Mode.OFF ? 0xC0C0C0 : 0x606060;
        g.drawString(font, Component.translatable("radar_fcs.autopilot.distance"), left + 8, top + 134, off, false);
        g.drawString(font, Component.translatable("radar_fcs.autopilot.height"), left + 8, top + 156,
                mode == Autopilot.Mode.DISTANCE_ALTITUDE ? 0xC0C0C0 : 0x606060, false);
        g.drawString(font, Component.translatable("radar_fcs.autopilot.max_speed"), left + 8, top + 178, off, false);
        g.drawWordWrap(font, Component.translatable(mode.key() + ".hint"), left + 8, top + 222, W - 16, 0x8A9098);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
