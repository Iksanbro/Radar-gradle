package dev.radarfcs.client;

import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.TargetCategory;
import dev.radarfcs.iff.InheritPolicy;
import dev.radarfcs.iff.TransponderMode;
import dev.radarfcs.iff.TransponderSettings;
import dev.radarfcs.net.TransponderPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Экран транспондера «свой»: вкладка «Основное» - имя (корабля, базы), код (цифры), режим выделения, радиус;
 * вкладка «Наследование» - какие типы целей в зоне наследуют защиту и белый / чёрный списки наследования.
 */
public final class TransponderScreen extends Screen {
    private enum Tab { MAIN, INHERIT }

    private static final int W = 320, H = 230, LIST_ROW = 14, LIST_ROWS = 4;

    private final BlockPos pos;
    private final boolean onSublevel, powered;
    private final int maxRadius;
    private String code, name;
    private TransponderMode mode;
    private int radius;
    private int mask;
    private final List<String> white, black;
    private String entry = "";
    private final int[] scroll = new int[2];
    private Tab tab = Tab.MAIN;
    private int left, top;

    public TransponderScreen(TransponderPayloads.Open p) {
        super(Component.translatable("radar_fcs.transponder_gui.title"));
        this.pos = p.pos();
        TransponderSettings s = TransponderSettings.read(p.settings());
        this.code = s.code();
        this.name = s.name();
        this.mode = s.mode();
        this.radius = s.radius();
        this.mask = s.inherit().mask();
        this.white = new ArrayList<>(s.inherit().whitelist());
        this.black = new ArrayList<>(s.inherit().blacklist());
        this.onSublevel = (p.flags() & 1) != 0;
        this.powered = (p.flags() & 2) != 0;
        this.maxRadius = Math.max(1, p.maxRadius());
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int tw = (W - 8) / Tab.values().length;
        for (Tab t : Tab.values()) {
            Button b = addRenderableWidget(Button.builder(Component.translatable("radar_fcs.transponder_gui.tab." + t.name().toLowerCase(Locale.ROOT)),
                    x -> { tab = t; rebuildWidgets(); }).bounds(left + 4 + t.ordinal() * tw, top + 4, tw - 2, 18).build());
            b.active = t != tab;
        }
        if (tab == Tab.MAIN) mainTab();
        else inheritTab();
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.save"), b -> save())
                .bounds(left + W - 168, top + H - 24, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W - 84, top + H - 24, 80, 20).build());
    }

    private void mainTab() {
        int y = top + 30;
        EditBox nameBox = new EditBox(font, left + 110, y, W - 118, 20, Component.translatable("radar_fcs.transponder_gui.name"));
        nameBox.setMaxLength(TransponderSettings.NAME_LENGTH);
        nameBox.setValue(name);
        nameBox.setResponder(v -> name = v);
        nameBox.setHint(Component.translatable("radar_fcs.transponder_gui.name_hint").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(nameBox);
        y += 24;
        EditBox box = new EditBox(font, left + 110, y, 120, 20, Component.translatable("radar_fcs.transponder_gui.code"));
        box.setMaxLength(EngageSettings.CODE_LENGTH);
        box.setFilter(v -> v.chars().allMatch(ch -> ch >= '0' && ch <= '9'));
        box.setValue(code);
        box.setResponder(v -> code = v);
        box.setHint(Component.translatable("radar_fcs.transponder_gui.no_code").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(box);
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.transponder_gui.mode." + mode.name().toLowerCase(Locale.ROOT)), b -> {
            TransponderMode[] all = TransponderMode.values();
            mode = all[(mode.ordinal() + 1) % all.length];
            rebuildWidgets();
        }).bounds(left + 8, y, W - 16, 20).build());
        y += 24;
        if (mode.usesRadius()) {
            Button value = Button.builder(radiusText(), b -> { }).bounds(left + 132, y, 56, 20).build();
            value.active = false;
            addRenderableWidget(Button.builder(Component.literal("−"), b -> {
                radius = Math.max(1, radius - (hasShiftDown() ? 10 : 1));
                value.setMessage(radiusText());
            }).bounds(left + 110, y, 20, 20).build());
            addRenderableWidget(value);
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                radius = Math.min(maxRadius, radius + (hasShiftDown() ? 10 : 1));
                value.setMessage(radiusText());
            }).bounds(left + 190, y, 20, 20).build());
        }
    }

    private void inheritTab() {
        int y = top + 42, cw = (W - 16) / 2;
        for (TargetCategory c : TargetCategory.values()) {
            int i = c.ordinal();
            boolean on = (mask & c.bit) != 0;
            addRenderableWidget(Button.builder(Component.literal(on ? "☑ " : "☐ ").append(Component.translatable(
                            "radar_fcs.monitor_gui.show." + c.name().toLowerCase(Locale.ROOT))).withStyle(on ? ChatFormatting.WHITE : ChatFormatting.GRAY),
                    b -> { mask ^= c.bit; rebuildWidgets(); }).bounds(left + 8 + (i % 2) * cw, y + (i / 2) * 17, cw - 4, 16).build());
        }
        y += 4 * 17 + 4;
        EditBox box = new EditBox(font, left + 8, y, 150, 20, Component.translatable("radar_fcs.gui.list_entry"));
        box.setMaxLength(InheritPolicy.MAX_ENTRY);
        box.setValue(entry);
        box.setResponder(v -> entry = v);
        box.setHint(Component.translatable("radar_fcs.gui.list_entry_hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setTooltip(Tooltip.create(Component.translatable("radar_fcs.transponder_gui.inherit_hint")));
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.transponder_gui.inherit_always"), b -> add(white, black))
                .bounds(left + 162, y, 74, 20).build()).active = white.size() < InheritPolicy.MAX_ENTRIES;
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.transponder_gui.inherit_never"), b -> add(black, white))
                .bounds(left + 240, y, 72, 20).build()).active = black.size() < InheritPolicy.MAX_ENTRIES;
        y += 36;
        entries(white, 0, left + 8, y, ChatFormatting.GREEN);
        entries(black, 1, left + 8 + cw, y, ChatFormatting.RED);
    }

    private void entries(List<String> list, int which, int x, int y, ChatFormatting color) {
        scroll[which] = Math.max(0, Math.min(scroll[which], Math.max(0, list.size() - LIST_ROWS)));
        for (int r = 0; r < LIST_ROWS && scroll[which] + r < list.size(); r++) {
            int k = scroll[which] + r;
            addRenderableWidget(Button.builder(Component.literal("✕ ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(list.get(k)).withStyle(color)),
                    b -> { list.remove(k); rebuildWidgets(); }).bounds(x, y + r * LIST_ROW, (W - 16) / 2 - 4, LIST_ROW - 1).build());
        }
    }

    private void add(List<String> to, List<String> other) {
        String e = entry.trim();
        if (e.isEmpty() || to.size() >= InheritPolicy.MAX_ENTRIES) return;
        other.removeIf(x -> x.equalsIgnoreCase(e));
        if (to.stream().noneMatch(x -> x.equalsIgnoreCase(e))) to.add(e);
        entry = "";
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (tab == Tab.INHERIT && mouseY >= top + 150) {
            int which = mouseX < left + W / 2.0 ? 0 : 1;
            scroll[which] = Math.max(0, scroll[which] - (int) Math.signum(dy));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    private Component radiusText() {
        return Component.translatable("radar_fcs.transponder_gui.blocks", radius);
    }

    private void save() {
        TransponderSettings s = new TransponderSettings(EngageSettings.cleanCode(code), TransponderSettings.cleanName(name), mode, radius,
                new InheritPolicy(mask, white, black));
        PacketDistributor.sendToServer(new TransponderPayloads.Save(pos, s.write()));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (tab == Tab.MAIN) {
            g.drawString(font, Component.translatable("radar_fcs.transponder_gui.name"), left + 8, top + 36, 0xE0E0E0, false);
            g.drawString(font, Component.translatable("radar_fcs.transponder_gui.code"), left + 8, top + 60, 0xE0E0E0, false);
            if (mode.usesRadius()) {
                g.drawString(font, Component.translatable(mode == TransponderMode.RADIUS ? "radar_fcs.transponder_gui.radius"
                        : "radar_fcs.transponder_gui.distance"), left + 8, top + 108, 0xE0E0E0, false);
            }
            int y = top + (mode.usesRadius() ? 130 : 106);
            Component where = Component.translatable(onSublevel ? "radar_fcs.transponder_gui.on_sublevel" : "radar_fcs.transponder_gui.on_ground")
                    .withStyle(onSublevel ? ChatFormatting.AQUA : ChatFormatting.GRAY);
            g.drawString(font, where, left + 8, y, 0xFFFFFF, false);
            if (!powered) {
                Component ne = Component.translatable("radar_fcs.transponder_gui.no_energy").withStyle(ChatFormatting.RED);
                g.drawString(font, ne, left + W - 8 - font.width(ne), y, 0xFF5050, false);
            }
            y += 12;
            String hintKey = switch (mode) {
                case SUBLEVEL -> onSublevel ? "radar_fcs.transponder_gui.hint_sublevel" : "radar_fcs.transponder_gui.hint_sublevel_ground";
                case RADIUS -> "radar_fcs.transponder_gui.hint_radius";
                case SUBLEVEL_AND_DISTANCE -> onSublevel ? "radar_fcs.transponder_gui.hint_combined" : "radar_fcs.transponder_gui.hint_combined_ground";
            };
            g.drawWordWrap(font, Component.translatable(hintKey).withStyle(mode == TransponderMode.SUBLEVEL && !onSublevel
                    ? ChatFormatting.GOLD : ChatFormatting.GRAY), left + 8, y, W - 16, 0xA0A0A0);
        } else {
            g.drawString(font, Component.translatable("radar_fcs.transponder_gui.inherit_types"), left + 8, top + 30, 0xE0E0E0, false);
            int hy = top + 42 + 4 * 17 + 4 + 24;
            g.drawString(font, Component.translatable("radar_fcs.transponder_gui.always", white.size(), InheritPolicy.MAX_ENTRIES),
                    left + 8, hy, 0x8CFF9A, false);
            g.drawString(font, Component.translatable("radar_fcs.transponder_gui.never", black.size(), InheritPolicy.MAX_ENTRIES),
                    left + 8 + (W - 16) / 2, hy, 0xFF8C8C, false);
        }
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

    /** Открыть вкладку по номеру (скриншоты для документации). */
    public void showTab(int index) {
        tab = Tab.values()[Math.floorMod(index, Tab.values().length)];
        rebuildWidgets();
    }
}
