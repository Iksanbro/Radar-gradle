package dev.radarfcs.client;

import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.iff.TransponderMode;
import dev.radarfcs.net.TransponderPayloads;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Экран транспондера «свой»: код (цифры), режим выделения (сублевел / радиус / сублевел и расстояние), радиус. */
public final class TransponderScreen extends Screen {
    private static final int W = 300, H = 170;

    private final BlockPos pos;
    private final boolean onSublevel, powered;
    private final int maxRadius;
    private String code;
    private TransponderMode mode;
    private int radius;
    private int left, top;

    public TransponderScreen(TransponderPayloads.Open p) {
        super(Component.translatable("radar_fcs.transponder_gui.title"));
        this.pos = p.pos();
        this.code = p.code();
        TransponderMode[] modes = TransponderMode.values();
        this.mode = modes[Math.floorMod(p.mode(), modes.length)];
        this.radius = p.radius();
        this.onSublevel = (p.flags() & 1) != 0;
        this.powered = (p.flags() & 2) != 0;
        this.maxRadius = Math.max(1, p.maxRadius());
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        EditBox box = new EditBox(font, left + 110, top + 10, 120, 20, Component.translatable("radar_fcs.transponder_gui.code"));
        box.setMaxLength(EngageSettings.CODE_LENGTH);
        box.setFilter(v -> v.chars().allMatch(ch -> ch >= '0' && ch <= '9'));
        box.setValue(code);
        box.setResponder(v -> code = v);
        box.setHint(Component.translatable("radar_fcs.transponder_gui.no_code").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(modeText(), b -> {
            TransponderMode[] all = TransponderMode.values();
            mode = all[(mode.ordinal() + 1) % all.length];
            rebuildWidgets();
        }).bounds(left + 8, top + 36, W - 16, 20).build());
        if (mode.usesRadius()) {
            Button value = Button.builder(radiusText(), b -> { }).bounds(left + 132, top + 62, 56, 20).build();
            value.active = false;
            addRenderableWidget(Button.builder(Component.literal("−"), b -> {
                radius = Math.max(1, radius - (hasShiftDown() ? 10 : 1));
                value.setMessage(radiusText());
            }).bounds(left + 110, top + 62, 20, 20).build());
            addRenderableWidget(value);
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                radius = Math.min(maxRadius, radius + (hasShiftDown() ? 10 : 1));
                value.setMessage(radiusText());
            }).bounds(left + 190, top + 62, 20, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.save"), b -> save())
                .bounds(left + W - 168, top + H - 24, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W - 84, top + H - 24, 80, 20).build());
    }

    private Component modeText() {
        return Component.translatable("radar_fcs.transponder_gui.mode." + mode.name().toLowerCase(Locale.ROOT));
    }

    private Component radiusText() {
        return Component.translatable("radar_fcs.transponder_gui.blocks", radius);
    }

    private void save() {
        PacketDistributor.sendToServer(new TransponderPayloads.Save(pos, EngageSettings.cleanCode(code), mode.ordinal(), radius));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, Component.translatable("radar_fcs.transponder_gui.code"), left + 8, top + 16, 0xE0E0E0, false);
        if (mode.usesRadius()) {
            g.drawString(font, Component.translatable(mode == TransponderMode.RADIUS ? "radar_fcs.transponder_gui.radius"
                    : "radar_fcs.transponder_gui.distance"), left + 8, top + 68, 0xE0E0E0, false);
        }
        int y = top + (mode.usesRadius() ? 90 : 64);
        Component where = Component.translatable(onSublevel ? "radar_fcs.transponder_gui.on_sublevel" : "radar_fcs.transponder_gui.on_ground")
                .withStyle(onSublevel ? ChatFormatting.AQUA : ChatFormatting.GRAY);
        g.drawString(font, where, left + 8, y, 0xFFFFFF, false);
        if (!powered) g.drawString(font, Component.translatable("radar_fcs.transponder_gui.no_energy").withStyle(ChatFormatting.RED),
                left + W - 8 - font.width(Component.translatable("radar_fcs.transponder_gui.no_energy")), y, 0xFF5050, false);
        y += 12;
        String hintKey = switch (mode) {
            case SUBLEVEL -> onSublevel ? "radar_fcs.transponder_gui.hint_sublevel" : "radar_fcs.transponder_gui.hint_sublevel_ground";
            case RADIUS -> "radar_fcs.transponder_gui.hint_radius";
            case SUBLEVEL_AND_DISTANCE -> onSublevel ? "radar_fcs.transponder_gui.hint_combined" : "radar_fcs.transponder_gui.hint_combined_ground";
        };
        g.drawWordWrap(font, Component.translatable(hintKey).withStyle(mode == TransponderMode.SUBLEVEL && !onSublevel
                ? ChatFormatting.GOLD : ChatFormatting.GRAY), left + 8, y, W - 16, 0xA0A0A0);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + H, 0xE0101418);
        g.renderOutline(left, top, W, H, 0xFF5A6A7A);
        g.drawCenteredString(font, title, width / 2, top - 12, 0x7FD4FF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
