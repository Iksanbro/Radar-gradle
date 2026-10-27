package dev.radarfcs.client;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.EngageSettings.Category;
import dev.radarfcs.fcs.SettingsNbt;
import dev.radarfcs.net.FcsCommandPayload;
import dev.radarfcs.net.FcsSettingsPayload;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Экран настроек вычислителя: режим огня, цели, белый и чёрный списки, сброс, приоритет и переключение, состояние
 * (сборка и разборка пушек). Настройки уходят на сервер кнопкой «Сохранить», команды сборки - сразу.
 */
public final class FcsSettingsScreen extends Screen {
    private enum Tab { FIRE, TARGETS, LISTS, DROP, PICK, STATUS }

    private static final int W = 360, H = 240, ROW = 22, LIST_ROW = 14;

    private final BlockPos pos;
    private final EngageSettings s;
    private Tab tab = Tab.FIRE;
    private int left, top;
    /** Набранная запись списка (переживает перестройку виджетов). */
    private String listInput = "";

    public FcsSettingsScreen(BlockPos pos, EngageSettings current) {
        super(Component.translatable("radar_fcs.gui.title"));
        this.pos = pos;
        this.s = current.copy();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int tw = (W - 8) / Tab.values().length;
        for (Tab t : Tab.values()) {
            Button b = addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.tab." + t.name().toLowerCase(Locale.ROOT)),
                    x -> { tab = t; rebuildWidgets(); }).bounds(left + 4 + t.ordinal() * tw, top + 4, tw - 2, 18).build());
            b.active = t != tab;
        }
        int y = top + 30;
        switch (tab) {
            case FIRE -> fireTab(y);
            case TARGETS -> targetsTab(y);
            case LISTS -> listsTab(y);
            case DROP -> dropTab(y);
            case PICK -> pickTab(y);
            case STATUS -> statusTab(y);
        }
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.save"), b -> save())
                .bounds(left + W - 168, top + H - 24, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W - 84, top + H - 24, 80, 20).build());
    }

    // ------------------------------------------------------------------ вкладки

    private void fireTab(int y) {
        cycle(left + 190, y, 160, () -> Component.translatable("radar_fcs.gui.fire." + s.fireMode.name().toLowerCase(Locale.ROOT)),
                () -> s.fireMode = next(s.fireMode, EngageSettings.FireMode.values()));
        y += ROW;
        if (s.fireMode == EngageSettings.FireMode.BURST) {
            number(left + 190, y, () -> s.burstLength, v -> s.burstLength = v, 1, 2, 100, null);
            y += ROW;
        }
        if (s.fireMode != EngageSettings.FireMode.AUTO) {
            number(left + 190, y, () -> s.pauseTicks, v -> s.pauseTicks = v, 5, 0, 1200, "radar_fcs.gui.ticks");
        }
    }

    private void targetsTab(int y) {
        for (Category c : Category.values()) {
            int i = c.ordinal();
            Component limit = serverLimit(c);
            Button b = cycle(left + 8, y, 160, () -> {
                        MutableComponent t = check(s.enabled[i], "radar_fcs.gui.type." + c.name().toLowerCase(Locale.ROOT));
                        return limit == null ? t : t.append(Component.literal(" ⚠").withStyle(ChatFormatting.GOLD));
                    }, () -> s.enabled[i] = !s.enabled[i]);
            if (limit != null) b.setTooltip(Tooltip.create(limit));
            number(left + 240, y, () -> s.level[i], v -> s.level[i] = v, 1, 1, EngageSettings.MAX_LEVEL, null);
            y += ROW;
        }
        number(left + 78, y, () -> (int) s.speedMin, v -> s.speedMin = v, 5, 0, 1000, null);
        number(left + 240, y, () -> (int) s.speedMax, v -> s.speedMax = v, 5, 0, 1000, null);
        y += ROW;
        cycle(left + 8, y, W - 16, () -> check(s.hostileOnly, "radar_fcs.gui.hostile_only"), () -> s.hostileOnly = !s.hostileOnly);
    }

    /** Белый и чёрный списки: поле ввода, кнопки «в белый» / «в чёрный», записи (клик - убрать). */
    private void listsTab(int y) {
        EditBox box = new EditBox(font, left + 8, y, 170, 20, Component.translatable("radar_fcs.gui.list_entry"));
        box.setMaxLength(EngageSettings.MAX_ENTRY);
        box.setValue(listInput);
        box.setResponder(v -> listInput = v);
        box.setHint(Component.translatable("radar_fcs.gui.list_entry_hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setTooltip(Tooltip.create(Component.translatable("radar_fcs.gui.lists_hint")));
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.to_whitelist"), b -> addEntry(s.whitelist, s.blacklist))
                .bounds(left + 182, y, 84, 20).build()).active = s.whitelist.size() < EngageSettings.MAX_LIST;
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.to_blacklist"), b -> addEntry(s.blacklist, s.whitelist))
                .bounds(left + 268, y, 84, 20).build()).active = s.blacklist.size() < EngageSettings.MAX_LIST;
        y += 38;
        entries(s.whitelist, left + 8, y, 0xFF8CFF9A);
        entries(s.blacklist, left + 182, y, 0xFFFF8C8C);
    }

    private void entries(List<String> list, int x, int y, int color) {
        for (int i = 0; i < list.size(); i++) {
            int k = i;
            Button b = addRenderableWidget(Button.builder(Component.literal("✕ ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(list.get(i)).withColor(color & 0xFFFFFF)),
                    x0 -> { list.remove(k); rebuildWidgets(); }).bounds(x, y + i * LIST_ROW, 170, LIST_ROW - 1).build());
            b.setTooltip(Tooltip.create(Component.translatable("radar_fcs.gui.list_remove")));
        }
    }

    private void addEntry(List<String> to, List<String> other) {
        String e = listInput.trim();
        if (e.isEmpty() || to.size() >= EngageSettings.MAX_LIST) return;
        other.removeIf(x -> x.equalsIgnoreCase(e));                       // запись - только в одном списке
        if (to.stream().noneMatch(x -> x.equalsIgnoreCase(e))) to.add(e);
        listInput = "";
        rebuildWidgets();
    }

    /** Сборка и разборка всех пушек вычислителя - сразу, без «Сохранить». */
    private void statusTab(int y) {
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.assemble"),
                        b -> PacketDistributor.sendToServer(new FcsCommandPayload(pos, FcsCommandPayload.ASSEMBLE)))
                .bounds(left + 8, y, 130, 20).tooltip(Tooltip.create(Component.translatable("radar_fcs.gui.assemble_hint"))).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.disassemble"),
                        b -> PacketDistributor.sendToServer(new FcsCommandPayload(pos, FcsCommandPayload.DISASSEMBLE)))
                .bounds(left + 142, y, 130, 20).tooltip(Tooltip.create(Component.translatable("radar_fcs.gui.disassemble_hint"))).build());
    }

    private void dropTab(int y) {
        cycle(left + 8, y, W - 16, () -> check(s.dropStatic, "radar_fcs.gui.drop_static"), () -> s.dropStatic = !s.dropStatic);
        y += ROW;
        number(left + 240, y, () -> s.staticTicks, v -> s.staticTicks = v, 2, 0, 1200,
                v -> Component.translatable("radar_fcs.gui.seconds_value", String.format(Locale.ROOT, "%.1f", v / 20.0)), true);
        y += ROW;
        cycle(left + 8, y, W - 16, () -> check(s.dropMoving, "radar_fcs.gui.drop_moving"), () -> s.dropMoving = !s.dropMoving);
        y += ROW;
        number(left + 240, y, () -> s.dropAfterShots, v -> s.dropAfterShots = v, 1, 0, 10000, null);
    }

    /** Что из типа целей запрещено конфигом сервера (null - ничего). */
    private static Component serverLimit(Category c) {
        String key = switch (c) {
            case SHIPS -> FcsConfig.TARGET_SHIPS.get() ? null : "radar_fcs.gui.server_forbids";
            case PLAYERS -> FcsConfig.TARGET_PLAYERS.get() ? null : "radar_fcs.gui.server_forbids";
            case MOBS -> FcsConfig.TARGET_MOBS.get() ? null : "radar_fcs.gui.server_forbids";
            case ENTITIES -> !FcsConfig.TARGET_CONTRAPTIONS.get() && !FcsConfig.TARGET_PROJECTILES.get() && !FcsConfig.TARGET_OTHER.get()
                    ? "radar_fcs.gui.server_forbids"
                    : !FcsConfig.TARGET_PROJECTILES.get() ? "radar_fcs.gui.server_no_projectiles"
                    : !FcsConfig.TARGET_CONTRAPTIONS.get() ? "radar_fcs.gui.server_no_contraptions"
                    : !FcsConfig.TARGET_OTHER.get() ? "radar_fcs.gui.server_no_other" : null;
        };
        return key == null ? null : Component.translatable(key);
    }

    private void pickTab(int y) {
        cycle(left + 190, y, 160, () -> Component.translatable("radar_fcs.gui.priority." + s.priority.name().toLowerCase(Locale.ROOT)),
                () -> s.priority = next(s.priority, EngageSettings.Priority.values()));
        y += ROW;
        cycle(left + 8, y, W - 16, () -> check(s.switchByPriority, "radar_fcs.gui.switch_by_priority"),
                () -> s.switchByPriority = !s.switchByPriority);
        y += ROW;
        cycle(left + 190, y, 160, () -> Component.translatable("radar_fcs.gui.switch." + s.switchMode.name().toLowerCase(Locale.ROOT)),
                () -> s.switchMode = next(s.switchMode, EngageSettings.SwitchMode.values()));
        y += ROW;
        if (s.switchMode == EngageSettings.SwitchMode.AFTER_SHOTS) {
            number(left + 190, y, () -> s.switchShots, v -> s.switchShots = v, 1, 1, 10000, null);
        } else if (s.switchMode == EngageSettings.SwitchMode.AFTER_TIME) {
            number(left + 190, y, () -> s.switchSeconds, v -> s.switchSeconds = v, 1, 1, 3600, "radar_fcs.gui.seconds");
        }
    }

    // ------------------------------------------------------------------ подписи

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = top + 36;
        switch (tab) {
            case FIRE -> {
                label(g, "radar_fcs.gui.fire_mode", y);
                y += ROW;
                if (s.fireMode == EngageSettings.FireMode.BURST) { label(g, "radar_fcs.gui.burst_length", y); y += ROW; }
                if (s.fireMode != EngageSettings.FireMode.AUTO) { label(g, "radar_fcs.gui.pause", y); y += ROW; }
                hint(g, "radar_fcs.gui.fire_hint." + s.fireMode.name().toLowerCase(Locale.ROOT), y + 6);
            }
            case TARGETS -> {
                for (int i = 0; i < EngageSettings.CATEGORIES; i++) {
                    g.drawString(font, Component.translatable("radar_fcs.gui.level"), left + 186, y, 0xA0A0A0, false);
                    y += ROW;
                }
                g.drawString(font, Component.translatable("radar_fcs.gui.speed_from"), left + 8, y, 0xE0E0E0, false);
                g.drawString(font, Component.translatable("radar_fcs.gui.speed_to"), left + 216, y, 0xE0E0E0, false);
                y += 2 * ROW;
                hint(g, "radar_fcs.gui.targets_hint", y);
            }
            case LISTS -> {
                int hy = top + 56;
                g.drawString(font, Component.translatable("radar_fcs.gui.whitelist", s.whitelist.size(), EngageSettings.MAX_LIST),
                        left + 8, hy, 0x8CFF9A, false);
                g.drawString(font, Component.translatable("radar_fcs.gui.blacklist", s.blacklist.size(), EngageSettings.MAX_LIST),
                        left + 182, hy, 0xFF8C8C, false);
                int rows = Math.max(s.whitelist.size(), s.blacklist.size());
                if (rows <= 6) hint(g, "radar_fcs.gui.lists_hint", top + 68 + rows * LIST_ROW + 4);
            }
            case DROP -> {
                y += ROW;
                label(g, "radar_fcs.gui.static_time", y);
                y += 2 * ROW;
                label(g, "radar_fcs.gui.drop_after", y);
                y += ROW;
                hint(g, "radar_fcs.gui.drop_hint", y + 2);
            }
            case PICK -> {
                label(g, "radar_fcs.gui.priority", y);
                y += 2 * ROW;
                label(g, "radar_fcs.gui.switch", y);
                y += ROW;
                if (s.switchMode == EngageSettings.SwitchMode.AFTER_SHOTS) label(g, "radar_fcs.gui.switch_shots", y);
                else if (s.switchMode == EngageSettings.SwitchMode.AFTER_TIME) label(g, "radar_fcs.gui.switch_seconds", y);
                y += ROW;
                hint(g, "radar_fcs.gui.pick_hint", y + 2);
            }
            case STATUS -> {
                List<Component> lines = minecraft != null && minecraft.level != null
                        && minecraft.level.getBlockEntity(pos) instanceof FireControlBlockEntity be
                        ? FireControlBlockEntity.summaryLines(be.summary(), true) : List.of();
                int ly = top + 56;
                outer:
                for (Component c : lines) {
                    for (FormattedCharSequence part : font.split(c, W - 16)) {
                        if (ly > top + H - 36) break outer;
                        g.drawString(font, part, left + 8, ly, 0xFFFFFF, false);
                        ly += 10;
                    }
                }
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + H, 0xE0101418);
        g.renderOutline(left, top, W, H, 0xFF5A6A7A);
        g.drawCenteredString(font, title, width / 2, top - 12, 0x7FD4FF);
    }

    private void label(GuiGraphics g, String key, int y) {
        g.drawString(font, Component.translatable(key), left + 8, y, 0xE0E0E0, false);
    }

    private void hint(GuiGraphics g, String key, int y) {
        g.drawWordWrap(font, Component.translatable(key).withStyle(ChatFormatting.GRAY), left + 8, y, W - 16, 0xA0A0A0);
    }

    private static MutableComponent check(boolean on, String key) {
        return Component.literal(on ? "☑ " : "☐ ").append(Component.translatable(key));
    }

    // ------------------------------------------------------------------ элементы

    private Button cycle(int x, int y, int w, Supplier<Component> text, Runnable onPress) {
        return addRenderableWidget(Button.builder(text.get(), b -> {
            onPress.run();
            rebuildWidgets();                                      // зависящие от выбора поля появляются/исчезают
        }).bounds(x, y, w, 20).build());
    }

    /** Число: «−» значение «+», с Shift шаг x10. */
    private void number(int x, int y, IntSupplier get, IntConsumer set, int step, int min, int max, String unitKey) {
        number(x, y, get, set, step, min, max, v -> numberText(v, min, unitKey), true);
    }

    private void number(int x, int y, IntSupplier get, IntConsumer set, int step, int min, int max,
                        java.util.function.IntFunction<Component> format, boolean formatted) {
        Button value = Button.builder(format.apply(get.getAsInt()), b -> { }).bounds(x + 22, y, 56, 20).build();
        value.active = false;
        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            set.accept(Math.max(min, get.getAsInt() - step * (hasShiftDown() ? 10 : 1)));
            value.setMessage(format.apply(get.getAsInt()));
        }).bounds(x, y, 20, 20).build());
        addRenderableWidget(value);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            set.accept(Math.min(max, get.getAsInt() + step * (hasShiftDown() ? 10 : 1)));
            value.setMessage(format.apply(get.getAsInt()));
        }).bounds(x + 80, y, 20, 20).build());
    }

    /** 0 у полей «без ограничения» (скорость, сброс) пишется как «нет». */
    private static Component numberText(int v, int min, String unitKey) {
        if (v == 0 && min == 0 && unitKey == null) return Component.translatable("radar_fcs.gui.off");
        return unitKey == null ? Component.literal(Integer.toString(v)) : Component.literal(v + " ").append(Component.translatable(unitKey));
    }

    private static <E extends Enum<E>> E next(E cur, E[] all) {
        return all[(cur.ordinal() + 1) % all.length];
    }

    private void save() {
        PacketDistributor.sendToServer(new FcsSettingsPayload(pos, SettingsNbt.write(s.sanitize())));
        onClose();
    }

    /** Примеры записей списков (скриншоты для документации). */
    public void fillListsForScreenshot() {
        if (s.whitelist.isEmpty()) s.whitelist.addAll(List.of("minecraft:pillager", "#minecraft:raiders"));
        if (s.blacklist.isEmpty()) s.blacklist.addAll(List.of("minecraft:villager", "Steve", "create:*"));
    }

    /** Открыть вкладку по номеру (скриншоты для документации). */
    public void showTab(int index) {
        tab = Tab.values()[Math.floorMod(index, Tab.values().length)];
        rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
