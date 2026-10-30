package dev.radarfcs.client;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.EngageSettings.Category;
import dev.radarfcs.core.WheelStep;
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
    private enum Tab { FIRE, TARGETS, LISTS, IFF, INTERCEPT, DROP, PICK, STATUS }

    private static final int W = 480, H = 240, ROW = 22, LIST_ROW = 14, CODE_COLS = 4, LIST_ROWS = 9, IFF_ROWS = 6;

    private final BlockPos pos;
    private final EngageSettings s;
    private Tab tab = Tab.FIRE;
    private int left, top;
    /** Набранная запись списка и код «свой-чужой» (переживают перестройку виджетов). */
    private String listInput = "", codeInput = "";
    /** Прокрутка: белый список, чёрный список, коды «свой-чужой». */
    private final int[] scroll = new int[3];
    /** Видит ли игрок коды «свой-чужой» и может ли их менять (владелец, его команда, оператор). */
    private final boolean iffAccess;

    public FcsSettingsScreen(BlockPos pos, EngageSettings current, boolean iffAccess) {
        super(Component.translatable("radar_fcs.gui.title"));
        this.pos = pos;
        this.s = current.copy();
        this.iffAccess = iffAccess;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        // ширина вкладки - по длине подписи (длинные подписи не обрезаются)
        Tab[] tabs = Tab.values();
        int[] tw = new int[tabs.length];
        int sum = 0;
        for (Tab t : tabs) sum += tw[t.ordinal()] = font.width(Component.translatable("radar_fcs.gui.tab." + t.name().toLowerCase(Locale.ROOT))) + 10;
        double k = (W - 8.0 - 2 * tabs.length) / sum;
        int x0 = left + 4;
        for (Tab t : tabs) {
            int w = t.ordinal() == tabs.length - 1 ? left + W - 4 - x0 - 2 : (int) Math.floor(tw[t.ordinal()] * k);
            Button b = addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.tab." + t.name().toLowerCase(Locale.ROOT)),
                    x -> { tab = t; rebuildWidgets(); }).bounds(x0, top + 4, w, 18).build());
            b.active = t != tab;
            x0 += w + 2;
        }
        int y = top + 30;
        switch (tab) {
            case FIRE -> fireTab(y);
            case TARGETS -> targetsTab(y);
            case LISTS -> listsTab(y);
            case IFF -> iffTab(y);
            case INTERCEPT -> interceptTab(y);
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
            intNumber(left + 190, y, () -> s.burstLength, v -> s.burstLength = v, 2, 100, v -> Component.literal(Integer.toString(v)));
            y += ROW;
        }
        if (s.fireMode != EngageSettings.FireMode.AUTO) {
            seconds(left + 190, y, () -> s.pauseTicks, v -> s.pauseTicks = v, 60, true, "radar_fcs.gui.off");
        }
    }

    /** Столбцы строки типа цели: переключатель, уровень, скорость от, скорость до. */
    private static final int COL_TOGGLE_W = 148, COL_LEVEL = 162, COL_FROM = 264, COL_TO = 366, HEADER = 12;

    private void targetsTab(int y) {
        y += HEADER;
        for (Category c : Category.values()) {
            if (c.intercept()) continue;                            // снаряды и ракеты - на вкладке «Перехват»
            categoryRow(c, y);
            y += ROW;
        }
        cycle(left + 8, y, W - 16, () -> check(s.hostileOnly, "radar_fcs.gui.hostile_only"), () -> s.hostileOnly = !s.hostileOnly);
    }

    /** Строка типа цели: брать ли, уровень приоритета, окно скорости (своё у каждого типа). */
    private void categoryRow(Category c, int y) {
        int i = c.ordinal();
        Component limit = serverLimit(c);
        Button b = cycle(left + 8, y, COL_TOGGLE_W, () -> {
            MutableComponent t = check(s.enabled[i], "radar_fcs.gui.type." + c.name().toLowerCase(Locale.ROOT));
            return limit == null ? t : t.append(Component.literal(" ⚠").withStyle(ChatFormatting.GOLD));
        }, () -> s.enabled[i] = !s.enabled[i]);
        if (limit != null) b.setTooltip(Tooltip.create(limit));
        intNumber(left + COL_LEVEL, y, () -> s.level[i], v -> s.level[i] = v, 1, EngageSettings.MAX_LEVEL,
                v -> Component.literal(Integer.toString(v)));
        speed(left + COL_FROM, y, s.speedMin, i);
        speed(left + COL_TO, y, s.speedMax, i);
    }

    /** Заголовки столбцов строк типов целей. */
    private void categoryHeader(GuiGraphics g, int y) {
        g.drawCenteredString(font, Component.translatable("radar_fcs.gui.level"), left + COL_LEVEL + 48, y, 0xA0A0A0);
        g.drawCenteredString(font, Component.translatable("radar_fcs.gui.col_speed_from"), left + COL_FROM + 48, y, 0xA0A0A0);
        g.drawCenteredString(font, Component.translatable("radar_fcs.gui.col_speed_to"), left + COL_TO + 48, y, 0xA0A0A0);
    }

    /**
     * Перехват снарядов: какие типы (снаряды больших пушек, ракеты) и их уровни приоритета, какие из них брать
     * (угрожающие своим, приближающиеся, все чужие), радиус угрозы, порядок (первым - кто раньше долетит).
     */
    private void interceptTab(int y) {
        y += HEADER;
        for (Category c : new Category[]{Category.SHELLS, Category.ROCKETS}) {
            categoryRow(c, y);
            y += ROW;
        }
        cycle(left + 190, y, W - 198, () -> Component.translatable("radar_fcs.gui.threat." + s.threat.name().toLowerCase(Locale.ROOT)),
                () -> s.threat = next(s.threat, EngageSettings.Threat.values()));
        y += ROW;
        if (s.threat == EngageSettings.Threat.THREATENING) {
            intNumber(left + 190, y, () -> s.threatRadius, v -> s.threatRadius = v, 1, EngageSettings.MAX_THREAT_RADIUS,
                    v -> Component.literal(v + " ").append(Component.translatable("radar_fcs.gui.blocks")));
            y += ROW;
        }
        cycle(left + 8, y, W - 16, () -> check(s.impactFirst, "radar_fcs.gui.impact_first"), () -> s.impactFirst = !s.impactFirst);
    }

    /** Есть ли на сервере мод перехвата (из сводки блока); неизвестно - true. */
    private boolean interceptAvailable() {
        if (minecraft != null && minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof FireControlBlockEntity be
                && be.summary().contains("Intercept")) return be.summary().getBoolean("Intercept");
        return true;
    }

    /** Белый и чёрный списки: поле ввода, кнопки «в белый» / «в чёрный», записи (клик - убрать). */
    private void listsTab(int y) {
        int cap = capacity();
        EditBox box = new EditBox(font, left + 8, y, 190, 20, Component.translatable("radar_fcs.gui.list_entry"));
        box.setMaxLength(EngageSettings.MAX_ENTRY);
        box.setValue(listInput);
        box.setResponder(v -> listInput = v);
        box.setHint(Component.translatable("radar_fcs.gui.list_entry_hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setTooltip(Tooltip.create(Component.translatable("radar_fcs.gui.lists_hint")));
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.to_whitelist"), b -> addEntry(s.whitelist, s.blacklist))
                .bounds(left + 202, y, 94, 20).build()).active = s.whitelist.size() < cap;
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.to_blacklist"), b -> addEntry(s.blacklist, s.whitelist))
                .bounds(left + 300, y, 92, 20).build()).active = s.blacklist.size() < cap;
        y += 38;
        entries(s.whitelist, 0, left + 8, y, 0xFF8CFF9A, cap);
        entries(s.blacklist, 1, left + 204, y, 0xFFFF8C8C, cap);
    }

    /** Видимые записи списка (колесо мыши - прокрутка); записи сверх памяти серые и не действуют. */
    private void entries(List<String> list, int which, int x, int y, int color, int cap) {
        scroll[which] = Math.max(0, Math.min(scroll[which], Math.max(0, list.size() - LIST_ROWS)));
        for (int r = 0; r < LIST_ROWS && scroll[which] + r < list.size(); r++) {
            int k = scroll[which] + r;
            boolean active = k < cap;
            Button b = addRenderableWidget(Button.builder(Component.literal("✕ ").withStyle(ChatFormatting.GRAY)
                            .append(active ? Component.literal(list.get(k)).withColor(color & 0xFFFFFF)
                                    : Component.literal(list.get(k)).withStyle(ChatFormatting.DARK_GRAY)),
                    x0 -> { list.remove(k); rebuildWidgets(); }).bounds(x, y + r * LIST_ROW, 188, LIST_ROW - 1).build());
            b.setTooltip(Tooltip.create(Component.translatable(active ? "radar_fcs.gui.list_remove" : "radar_fcs.gui.list_no_memory")));
        }
    }

    private void addEntry(List<String> to, List<String> other) {
        String e = listInput.trim();
        if (e.isEmpty() || to.size() >= capacity()) return;
        other.removeIf(x -> x.equalsIgnoreCase(e));                       // запись - только в одном списке
        if (to.stream().noneMatch(x -> x.equalsIgnoreCase(e))) to.add(e);
        listInput = "";
        rebuildWidgets();
    }

    /** Записей в каждом списке: 16 + 16 за блок памяти с питанием в сети вычислителя (из сводки блока). */
    private int capacity() {
        if (minecraft != null && minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof FireControlBlockEntity be
                && be.summary().contains("Capacity")) return be.summary().getInt("Capacity");
        return dev.radarfcs.block.MemoryBlockEntity.BASE;
    }

    /** Свой-чужой: режим (белый / чёрный список кодов транспондеров), поле кода, коды (клик - убрать). */
    private void iffTab(int y) {
        int cap = capacity();
        Button mode = cycle(left + 8, y, W - 16, () -> Component.translatable(s.iffWhitelist ? "radar_fcs.gui.iff_mode_white"
                : "radar_fcs.gui.iff_mode_black"), () -> s.iffWhitelist = !s.iffWhitelist);
        mode.active = iffAccess;
        y += ROW + 4;
        EditBox box = new EditBox(font, left + 8, y, 120, 20, Component.translatable("radar_fcs.gui.iff_code"));
        box.setMaxLength(EngageSettings.CODE_LENGTH);
        box.setFilter(v -> v.chars().allMatch(ch -> ch >= '0' && ch <= '9'));
        box.setValue(codeInput);
        box.setResponder(v -> codeInput = v);
        box.setHint(Component.translatable("radar_fcs.gui.iff_code_hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setEditable(iffAccess);
        addRenderableWidget(box);
        Button add = addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.iff_add"), b -> {
            String c = EngageSettings.cleanCode(codeInput);
            if (!c.isEmpty() && !s.iffCodes.contains(c) && s.iffCodes.size() < capacity()) s.iffCodes.add(c);
            codeInput = "";
            rebuildWidgets();
        }).bounds(left + 132, y, 90, 20).build());
        add.active = iffAccess && s.iffCodes.size() < cap;
        y += 26;
        int cw = (W - 16) / CODE_COLS;
        int rows = (s.iffCodes.size() + CODE_COLS - 1) / CODE_COLS;
        scroll[2] = Math.max(0, Math.min(scroll[2], Math.max(0, rows - IFF_ROWS)));
        for (int r = 0; r < IFF_ROWS; r++) {
            for (int c = 0; c < CODE_COLS; c++) {
                int k = (scroll[2] + r) * CODE_COLS + c;
                if (k >= s.iffCodes.size()) break;
                boolean active = k < cap;
                Button b = addRenderableWidget(Button.builder(Component.literal("✕ ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(s.iffCodes.get(k)).withStyle(!active ? ChatFormatting.DARK_GRAY
                                        : s.iffWhitelist ? ChatFormatting.GREEN : ChatFormatting.RED)),
                        x0 -> { s.iffCodes.remove(k); rebuildWidgets(); })
                        .bounds(left + 8 + c * cw, y + r * LIST_ROW, cw - 4, LIST_ROW - 1).build());
                b.active = iffAccess;
                b.setTooltip(Tooltip.create(Component.translatable(active ? "radar_fcs.gui.list_remove" : "radar_fcs.gui.list_no_memory")));
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        int which = -1;
        if (tab == Tab.LISTS && mouseY >= top + 68) which = mouseX < left + 200 ? 0 : 1;
        else if (tab == Tab.IFF && mouseY >= top + 82) which = 2;
        if (which >= 0) {
            scroll[which] = Math.max(0, scroll[which] - (int) Math.signum(dy) * (which == 2 ? 1 : 3));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
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
        seconds(left + 240, y, () -> s.staticTicks, v -> s.staticTicks = v, 60, true, null);
        y += ROW;
        cycle(left + 8, y, W - 16, () -> check(s.dropMoving, "radar_fcs.gui.drop_moving"), () -> s.dropMoving = !s.dropMoving);
        y += ROW;
        intNumber(left + 240, y, () -> s.dropAfterShots, v -> s.dropAfterShots = v, 0, 10000,
                v -> v == 0 ? Component.translatable("radar_fcs.gui.off") : Component.literal(Integer.toString(v)));
    }

    /** Что из типа целей запрещено конфигом сервера (null - ничего). */
    private static Component serverLimit(Category c) {
        String key = switch (c) {
            case SHIPS -> FcsConfig.TARGET_SHIPS.get() ? null : "radar_fcs.gui.server_forbids";
            case PLAYERS -> FcsConfig.TARGET_PLAYERS.get() ? null : "radar_fcs.gui.server_forbids";
            case MOBS -> FcsConfig.TARGET_MOBS.get() ? null : "radar_fcs.gui.server_forbids";
            case ENTITIES -> !FcsConfig.TARGET_CONTRAPTIONS.get() && !FcsConfig.TARGET_OTHER.get()
                    ? "radar_fcs.gui.server_forbids"
                    : !FcsConfig.TARGET_CONTRAPTIONS.get() ? "radar_fcs.gui.server_no_contraptions"
                    : !FcsConfig.TARGET_OTHER.get() ? "radar_fcs.gui.server_no_other" : null;
            case SHELLS, ROCKETS -> FcsConfig.TARGET_PROJECTILES.get() ? null : "radar_fcs.gui.server_no_projectiles";
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
            intNumber(left + 190, y, () -> s.switchShots, v -> s.switchShots = v, 1, 10000, v -> Component.literal(Integer.toString(v)));
        } else if (s.switchMode == EngageSettings.SwitchMode.AFTER_TIME) {
            seconds(left + 190, y, () -> s.switchTicks, v -> s.switchTicks = v, 3600, false, null);
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
                categoryHeader(g, top + 32);
                y = top + 30 + HEADER + 5 * ROW + 6;
                hint(g, "radar_fcs.gui.targets_hint", y);
            }
            case LISTS -> {
                int hy = top + 56, cap = capacity();
                g.drawString(font, Component.translatable("radar_fcs.gui.whitelist", s.whitelist.size(), cap), left + 8, hy, 0x8CFF9A, false);
                g.drawString(font, Component.translatable("radar_fcs.gui.blacklist", s.blacklist.size(), cap), left + 204, hy, 0xFF8C8C, false);
                if (s.whitelist.size() > LIST_ROWS || s.blacklist.size() > LIST_ROWS) {
                    Component sc = Component.translatable("radar_fcs.gui.scroll");
                    g.drawString(font, sc, left + W - 8 - font.width(sc), top + H - 18, 0x808080, false);
                }
                int rows = Math.max(s.whitelist.size(), s.blacklist.size());
                if (rows <= 6) hint(g, "radar_fcs.gui.lists_hint", top + 68 + rows * LIST_ROW + 4);
            }
            case IFF -> {
                g.drawString(font, Component.translatable("radar_fcs.gui.iff_count", s.iffCodes.size(), capacity()),
                        left + 232, top + 62, 0xA0A0A0, false);
                int rows = Math.min(IFF_ROWS, (s.iffCodes.size() + CODE_COLS - 1) / CODE_COLS);
                if ((s.iffCodes.size() + CODE_COLS - 1) / CODE_COLS > IFF_ROWS) {
                    Component sc = Component.translatable("radar_fcs.gui.scroll");
                    g.drawString(font, sc, left + W - 8 - font.width(sc), top + 62, 0x808080, false);
                }
                int hy = top + 82 + rows * LIST_ROW + 6;
                if (!iffAccess) {
                    g.drawWordWrap(font, Component.translatable("radar_fcs.gui.iff_hidden").withStyle(ChatFormatting.GOLD), left + 8, hy,
                            W - 16, 0xFFAA00);
                    hy += 22;
                }
                hint(g, s.iffWhitelist ? "radar_fcs.gui.iff_hint_white" : "radar_fcs.gui.iff_hint_black", hy);
            }
            case INTERCEPT -> {
                categoryHeader(g, top + 32);
                y += HEADER + 2 * ROW;
                label(g, "radar_fcs.gui.threat", y);
                y += ROW;
                if (s.threat == EngageSettings.Threat.THREATENING) { label(g, "radar_fcs.gui.threat_radius", y); y += ROW; }
                y += ROW + 4;
                if (!interceptAvailable()) {
                    g.drawWordWrap(font, Component.translatable("radar_fcs.gui.intercept_no_mod").withStyle(ChatFormatting.GOLD), left + 8, y,
                            W - 16, 0xFFAA00);
                    y += 22;
                }
                hint(g, "radar_fcs.gui.intercept_hint." + s.threat.name().toLowerCase(Locale.ROOT), y);
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

    /**
     * Число: «−» поле «+». Поле крутится колесом мыши; шаг - {@link WheelStep}: дробные - до 1 по 0.1, дальше по 1;
     * целые - по 1; с Shift - в 10 раз больше.
     */
    private NumberField field(int x, int y, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set,
                              NumberField.Step step, java.util.function.DoubleFunction<Component> format) {
        NumberField f = new NumberField(x + 22, y, 52, 20, get, set, step, format);
        f.setTooltip(Tooltip.create(Component.translatable("radar_fcs.gui.wheel_hint")));
        addRenderableWidget(Button.builder(Component.literal("−"), b -> f.nudge(-1)).bounds(x, y, 20, 20).build());
        addRenderableWidget(f);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> f.nudge(1)).bounds(x + 76, y, 20, 20).build());
        return f;
    }

    /** Целое число min..max. */
    private void intNumber(int x, int y, IntSupplier get, IntConsumer set, int min, int max, java.util.function.IntFunction<Component> format) {
        field(x, y, get::getAsInt, v -> set.accept((int) Math.round(v)),
                (v, dir, shift) -> WheelStep.integer((int) Math.round(v), dir, shift, min, max), v -> format.apply((int) Math.round(v)));
    }

    /** Скорость окна типа цели, м/с: от 0.1, ниже - «нет» (без ограничения). */
    private void speed(int x, int y, double[] arr, int i) {
        field(x, y, () -> arr[i], v -> arr[i] = v,
                (v, dir, shift) -> WheelStep.fractional(v, dir, shift, 0.1, EngageSettings.MAX_SPEED, true),
                v -> v <= 0 ? Component.translatable("radar_fcs.gui.off") : Component.literal(num(v)));
    }

    /**
     * Время в секундах (хранится в тиках): от 0.1 с до maxSeconds.
     * @param zeroKey подпись нуля (0 допустим), null - «0 с»; zero = false - 0 недопустим
     */
    private void seconds(int x, int y, IntSupplier ticks, IntConsumer setTicks, double maxSeconds, boolean zero, String zeroKey) {
        field(x, y, () -> ticks.getAsInt() / 20.0, v -> setTicks.accept((int) Math.round(v * 20)),
                (v, dir, shift) -> WheelStep.fractional(v, dir, shift, 0.1, maxSeconds, zero),
                v -> v <= 0 && zeroKey != null ? Component.translatable(zeroKey)
                        : Component.translatable("radar_fcs.gui.seconds_value", num(v)));
    }

    /** Число с десятыми (целое - без дробной части); в русском - с запятой. */
    private static String num(double v) {
        double r = WheelStep.round(v);
        String t = r == Math.rint(r) ? Long.toString((long) r) : String.format(Locale.ROOT, "%.1f", r);
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.options != null && mc.options.languageCode.startsWith("ru") ? t.replace('.', ',') : t;
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
        if (s.iffCodes.isEmpty()) {
            s.iffWhitelist = true;
            s.iffCodes.addAll(List.of("1234", "2077", "31337", "4096", "777"));
        }
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
