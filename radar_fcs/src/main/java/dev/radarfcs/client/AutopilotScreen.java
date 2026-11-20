package dev.radarfcs.client;

import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.core.Docking;
import dev.radarfcs.net.AutopilotPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Экран моста Flight Control. Вкладка «Цель»: оси ведения цели (рыскание, крен, тангаж) и пределы крена и тангажа,
 * режим (только поворот, только дальность, дальность и высота), дальность, высота над целью и предел скорости.
 * Вкладка «Без цели»: висеть или орбита (центр - координата или маяк орбиты сети, радиус, высота над центром,
 * направление) и стыковка (когда, что делать, если порты заняты). Числа - с клавиатуры.
 */
public final class AutopilotScreen extends Screen {
    private static final int W = 300, H = 272;

    private final BlockPos pos;
    private final CompoundTag initial;
    private boolean heading, pitch, roll, clockwise;
    private Autopilot.Mode mode;
    private Autopilot.Idle idle;
    private Docking.Trigger dock;
    private Docking.Busy busy;
    private int tab;
    private int left, top;
    private EditBox distField, heightField, speedField, rollField, pitchField;
    private EditBox xField, yField, zField, radiusField, orbitHeightField;
    private final List<AbstractWidget> targetTab = new ArrayList<>(), idleTab = new ArrayList<>();

    public AutopilotScreen(AutopilotPayloads.Open p) {
        super(Component.translatable("radar_fcs.autopilot.title"));
        pos = p.pos();
        CompoundTag t = p.settings();
        initial = t;
        heading = !t.contains("Heading") || t.getBoolean("Heading");
        pitch = t.getBoolean("Pitch");
        roll = t.getBoolean("Roll");
        mode = AutopilotBlockEntity.enumOr(Autopilot.Mode.class, t.getString("Mode"), Autopilot.Mode.DISTANCE);
        idle = AutopilotBlockEntity.enumOr(Autopilot.Idle.class, t.getString("Idle"), Autopilot.Idle.HOVER);
        dock = AutopilotBlockEntity.enumOr(Docking.Trigger.class, t.getString("Dock"), Docking.Trigger.OFF);
        busy = AutopilotBlockEntity.enumOr(Docking.Busy.class, t.getString("DockBusy"), Docking.Busy.WAIT);
        clockwise = t.getBoolean("OrbitCW");
    }

    private double init(String key, DoubleUnaryOperator clean, double fallback) {
        return clean.applyAsDouble(initial.contains(key) ? initial.getDouble(key) : fallback);
    }

    @Override
    protected void init() {
        targetTab.clear();
        idleTab.clear();
        left = (width - W) / 2;
        top = (height - H) / 2;
        int hw = (W - 20) / 2;
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.autopilot.tab.target"), b -> setTab(0))
                .bounds(left + 8, top + 20, hw, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.autopilot.tab.idle"), b -> setTab(1))
                .bounds(left + 12 + hw, top + 20, hw, 18).build());
        int x = left + 170, w = W - 180;
        // ---- вкладка «Цель»
        int bw = (W - 24) / 3;
        targetTab.add(addRenderableWidget(Button.builder(axis("yaw", heading), b -> {
            heading = !heading;
            b.setMessage(axis("yaw", heading));
        }).bounds(left + 8, top + 56, bw, 18).build()));
        targetTab.add(addRenderableWidget(Button.builder(axis("roll", roll), b -> {
            roll = !roll;
            b.setMessage(axis("roll", roll));
            updateFields();
        }).bounds(left + 12 + bw, top + 56, bw, 18).build()));
        targetTab.add(addRenderableWidget(Button.builder(axis("pitch", pitch), b -> {
            pitch = !pitch;
            b.setMessage(axis("pitch", pitch));
            updateFields();
        }).bounds(left + 16 + 2 * bw, top + 56, bw, 18).build()));
        rollField = field(targetTab, x, top + 78, w, keep(rollField, init("MaxRoll", Autopilot::cleanRoll, Double.NaN)));
        pitchField = field(targetTab, x, top + 98, w, keep(pitchField, init("MaxPitch", Autopilot::cleanPitch, Double.NaN)));
        targetTab.add(addRenderableWidget(Button.builder(Component.translatable(mode.key()), b -> {
            Autopilot.Mode[] v = Autopilot.Mode.values();
            mode = v[(mode.ordinal() + 1) % v.length];
            b.setMessage(Component.translatable(mode.key()));
            updateFields();
        }).bounds(left + 8, top + 118, W - 16, 18).build()));
        distField = field(targetTab, x, top + 140, w, keep(distField, init("Distance", Autopilot::cleanDistance, Double.NaN)));
        heightField = field(targetTab, x, top + 160, w, keep(heightField, init("Height", Autopilot::cleanHeight, Double.NaN)));
        speedField = field(targetTab, x, top + 180, w, keep(speedField, init("MaxSpeed", Autopilot::cleanSpeed, Double.NaN)));
        // ---- вкладка «Без цели»
        idleTab.add(addRenderableWidget(Button.builder(Component.translatable(idle.key()), b -> {
            Autopilot.Idle[] v = Autopilot.Idle.values();
            idle = v[(idle.ordinal() + 1) % v.length];
            b.setMessage(Component.translatable(idle.key()));
            updateFields();
        }).bounds(left + 8, top + 44, W - 16, 18).build()));
        int cw = (W - 16 - 58 - 12) / 3;
        xField = field(idleTab, left + 8, top + 78, cw, keep(xField, init("OrbitX", Autopilot::cleanCoord, 0)));
        yField = field(idleTab, left + 12 + cw, top + 78, cw, keep(yField, init("OrbitY", Autopilot::cleanCoord, 64)));
        zField = field(idleTab, left + 16 + 2 * cw, top + 78, cw, keep(zField, init("OrbitZ", Autopilot::cleanCoord, 0)));
        for (EditBox b : new EditBox[]{xField, yField, zField}) b.setMaxLength(10);
        idleTab.add(addRenderableWidget(Button.builder(Component.translatable("radar_fcs.autopilot.orbit_here"), b -> {
            var pl = Minecraft.getInstance().player;
            if (pl == null) return;
            xField.setValue(String.valueOf((long) Math.floor(pl.getX())));
            yField.setValue(String.valueOf((long) Math.floor(pl.getY())));
            zField.setValue(String.valueOf((long) Math.floor(pl.getZ())));
        }).bounds(left + W - 8 - 58, top + 77, 58, 18).tooltip(Tooltip.create(Component.translatable("radar_fcs.autopilot.orbit_here.hint"))).build()));
        radiusField = field(idleTab, x, top + 100, w, keep(radiusField, init("OrbitRadius", Autopilot::cleanRadius, Double.NaN)));
        orbitHeightField = field(idleTab, x, top + 120, w, keep(orbitHeightField, init("OrbitHeight", Autopilot::cleanHeight, 24)));
        idleTab.add(addRenderableWidget(Button.builder(direction(), b -> {
            clockwise = !clockwise;
            b.setMessage(direction());
        }).bounds(left + 8, top + 140, W - 16, 18).build()));
        idleTab.add(addRenderableWidget(Button.builder(Component.translatable(dock.key()), b -> {
            Docking.Trigger[] v = Docking.Trigger.values();
            dock = v[(dock.ordinal() + 1) % v.length];
            b.setMessage(Component.translatable(dock.key()));
        }).bounds(left + 8, top + 172, hw, 18).build()));
        idleTab.add(addRenderableWidget(Button.builder(Component.translatable(busy.key()), b -> {
            Docking.Busy[] v = Docking.Busy.values();
            busy = v[(busy.ordinal() + 1) % v.length];
            b.setMessage(Component.translatable(busy.key()));
        }).bounds(left + 12 + hw, top + 172, hw, 18).build()));
        // ---- сохранить / закрыть
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gun.save"), b -> {
            CompoundTag t = new CompoundTag();
            t.putBoolean("Heading", heading);
            t.putBoolean("Pitch", pitch);
            t.putBoolean("Roll", roll);
            t.putDouble("MaxRoll", parse(rollField, Autopilot::cleanRoll));
            t.putDouble("MaxPitch", parse(pitchField, Autopilot::cleanPitch));
            t.putString("Mode", mode.name());
            t.putDouble("Distance", parse(distField, Autopilot::cleanDistance));
            t.putDouble("Height", parse(heightField, Autopilot::cleanHeight));
            t.putDouble("MaxSpeed", parse(speedField, Autopilot::cleanSpeed));
            t.putString("Dock", dock.name());
            t.putString("DockBusy", busy.name());
            t.putString("Idle", idle.name());
            t.putDouble("OrbitX", parse(xField, Autopilot::cleanCoord));
            t.putDouble("OrbitY", parse(yField, Autopilot::cleanCoord));
            t.putDouble("OrbitZ", parse(zField, Autopilot::cleanCoord));
            t.putDouble("OrbitRadius", parse(radiusField, Autopilot::cleanRadius));
            t.putDouble("OrbitHeight", parse(orbitHeightField, Autopilot::cleanHeight));
            t.putBoolean("OrbitCW", clockwise);
            PacketDistributor.sendToServer(new AutopilotPayloads.Save(pos, t));
            onClose();
        }).bounds(left + W / 2 - 104, top + H - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W / 2 + 4, top + H - 26, 100, 20).build());
        setTab(tab);
    }

    private void setTab(int t) {
        tab = t;
        for (AbstractWidget w : targetTab) w.visible = t == 0;
        for (AbstractWidget w : idleTab) w.visible = t == 1;
        updateFields();
    }

    private static String keep(EditBox old, double initial) {
        return old != null ? old.getValue() : AutopilotBlockEntity.fmt(initial);
    }

    private EditBox field(List<AbstractWidget> tabList, int x, int y, int w, String value) {
        EditBox b = new EditBox(font, x, y, w, 16, Component.empty());
        b.setMaxLength(8);
        b.setFilter(s -> s.matches("-?[0-9]*([.,][0-9]*)?"));
        b.setValue(value);
        tabList.add(b);
        return addRenderableWidget(b);
    }

    /** Число из поля (чистится); пустое или неверное - то, что пришло с сервера (тоже чистится). */
    private double parse(EditBox b, DoubleUnaryOperator clean) {
        try {
            return clean.applyAsDouble(Double.parseDouble(b.getValue().replace(',', '.')));
        } catch (NumberFormatException e) {
            return clean.applyAsDouble(Double.NaN);
        }
    }

    /** Поля дальности - только с режимом дальности, высота - в «дальность и высота»; поля орбиты - только для орбиты. */
    private void updateFields() {
        edit(distField, mode != Autopilot.Mode.OFF);
        edit(speedField, mode != Autopilot.Mode.OFF);
        edit(heightField, mode == Autopilot.Mode.DISTANCE_ALTITUDE);
        edit(rollField, roll);
        edit(pitchField, pitch);
        for (EditBox b : new EditBox[]{xField, yField, zField}) edit(b, idle == Autopilot.Idle.ORBIT_POINT);
        edit(radiusField, idle.orbit());
        edit(orbitHeightField, idle.orbit());
    }

    private static void edit(EditBox b, boolean on) {
        b.setEditable(on);
        b.setTextColor(on ? 0xE0E0E0 : 0x606060);
    }

    private Component direction() {
        return Component.translatable(clockwise ? "radar_fcs.autopilot.orbit_cw" : "radar_fcs.autopilot.orbit_ccw");
    }

    private static Component axis(String name, boolean on) {
        return Component.translatable("radar_fcs.autopilot.axis." + name).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
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
        int tx = left + 8 + (tab == 0 ? 0 : (W - 20) / 2 + 4);
        g.fill(tx, top + 39, tx + (W - 20) / 2, top + 40, 0xFFFFD27F);         // отметка открытой вкладки
        if (tab == 0) {
            g.drawString(font, Component.translatable("radar_fcs.autopilot.axes_title"), left + 8, top + 45, 0xC0C0C0, false);
            g.drawString(font, Component.translatable("radar_fcs.autopilot.max_roll"), left + 8, top + 82, roll ? 0xC0C0C0 : 0x606060, false);
            g.drawString(font, Component.translatable("radar_fcs.autopilot.max_pitch"), left + 8, top + 102, pitch ? 0xC0C0C0 : 0x606060, false);
            int off = mode != Autopilot.Mode.OFF ? 0xC0C0C0 : 0x606060;
            g.drawString(font, Component.translatable("radar_fcs.autopilot.distance"), left + 8, top + 144, off, false);
            g.drawString(font, Component.translatable("radar_fcs.autopilot.height"), left + 8, top + 164,
                    mode == Autopilot.Mode.DISTANCE_ALTITUDE ? 0xC0C0C0 : 0x606060, false);
            g.drawString(font, Component.translatable("radar_fcs.autopilot.max_speed"), left + 8, top + 184, off, false);
            g.drawWordWrap(font, Component.translatable(mode.key() + ".hint"), left + 8, top + 204, W - 16, 0x8A9098);
        } else {
            boolean point = idle == Autopilot.Idle.ORBIT_POINT, orbit = idle.orbit();
            g.drawString(font, Component.translatable("radar_fcs.autopilot.orbit_center"), left + 8, top + 67, point ? 0xC0C0C0 : 0x606060, false);
            g.drawString(font, Component.translatable("radar_fcs.autopilot.orbit_radius"), left + 8, top + 104, orbit ? 0xC0C0C0 : 0x606060, false);
            g.drawString(font, Component.translatable("radar_fcs.autopilot.orbit_height"), left + 8, top + 124, orbit ? 0xC0C0C0 : 0x606060, false);
            g.drawString(font, Component.translatable("radar_fcs.autopilot.dock_title"), left + 8, top + 162, 0xC0C0C0, false);
            g.drawWordWrap(font, Component.translatable(idle.key() + ".hint"), left + 8, top + 194, W - 16, 0x8A9098);
            g.drawWordWrap(font, Component.translatable(dock.key() + ".hint"), left + 8, top + 218, W - 16, 0x6FA8B8);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
