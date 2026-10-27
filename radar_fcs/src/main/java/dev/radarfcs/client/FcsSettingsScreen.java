package dev.radarfcs.client;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.EngageSettings.Category;
import dev.radarfcs.fcs.SettingsNbt;
import dev.radarfcs.net.FcsSettingsPayload;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Экран настроек вычислителя: режим огня, цели, сброс, приоритет и переключение, состояние.
 * Настройки уходят на сервер кнопкой «Сохранить».
 */
public final class FcsSettingsScreen extends Screen {
    private enum Tab { FIRE, TARGETS, DROP, PICK, STATUS }

    private static final int W = 320, H = 214, ROW = 22;

    private final BlockPos pos;
    private final EngageSettings s;
    private Tab tab = Tab.FIRE;
    private int left, top;

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
            case DROP -> dropTab(y);
            case PICK -> pickTab(y);
            case STATUS -> { }
        }
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.save"), b -> save())
                .bounds(left + W - 168, top + H - 24, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W - 84, top + H - 24, 80, 20).build());
    }

    // ------------------------------------------------------------------ вкладки

    private void fireTab(int y) {
        cycle(left + 150, y, 160, () -> Component.translatable("radar_fcs.gui.fire." + s.fireMode.name().toLowerCase(Locale.ROOT)),
                () -> s.fireMode = next(s.fireMode, EngageSettings.FireMode.values()));
        y += ROW;
        if (s.fireMode == EngageSettings.FireMode.BURST) {
            number(left + 150, y, () -> s.burstLength, v -> s.burstLength = v, 1, 2, 100, null);
            y += ROW;
        }
        if (s.fireMode != EngageSettings.FireMode.AUTO) {
            number(left + 150, y, () -> s.pauseTicks, v -> s.pauseTicks = v, 5, 0, 1200, "radar_fcs.gui.ticks");
        }
    }

    private void targetsTab(int y) {
        for (Category c : Category.values()) {
            int i = c.ordinal();
            Component limit = serverLimit(c);
            Button b = cycle(left + 8, y, 140, () -> {
                        MutableComponent t = check(s.enabled[i], "radar_fcs.gui.type." + c.name().toLowerCase(Locale.ROOT));
                        return limit == null ? t : t.append(Component.literal(" ⚠").withStyle(ChatFormatting.GOLD));
                    }, () -> s.enabled[i] = !s.enabled[i]);
            if (limit != null) b.setTooltip(Tooltip.create(limit));
            number(left + 210, y, () -> s.level[i], v -> s.level[i] = v, 1, 1, EngageSettings.MAX_LEVEL, null);
            y += ROW;
        }
        number(left + 78, y, () -> (int) s.speedMin, v -> s.speedMin = v, 5, 0, 1000, null);
        number(left + 210, y, () -> (int) s.speedMax, v -> s.speedMax = v, 5, 0, 1000, null);
    }

    private void dropTab(int y) {
        cycle(left + 8, y, W - 16, () -> check(s.dropStatic, "radar_fcs.gui.drop_static"), () -> s.dropStatic = !s.dropStatic);
        y += ROW;
        number(left + 210, y, () -> s.staticTicks, v -> s.staticTicks = v, 2, 0, 1200,
                v -> Component.translatable("radar_fcs.gui.seconds_value", String.format(Locale.ROOT, "%.1f", v / 20.0)), true);
        y += ROW;
        cycle(left + 8, y, W - 16, () -> check(s.dropMoving, "radar_fcs.gui.drop_moving"), () -> s.dropMoving = !s.dropMoving);
        y += ROW;
        number(left + 210, y, () -> s.dropAfterShots, v -> s.dropAfterShots = v, 1, 0, 10000, null);
    }

    /** Что из типа целей запрещено конфигом сервера (null - ничего). */
    private static Component serverLimit(Category c) {
        String key = switch (c) {
            case SHIPS -> FcsConfig.TARGET_SHIPS.get() ? null : "radar_fcs.gui.server_forbids";
            case PLAYERS -> FcsConfig.TARGET_PLAYERS.get() ? null : "radar_fcs.gui.server_forbids";
            case MOBS -> !FcsConfig.TARGET_MOBS.get() ? "radar_fcs.gui.server_forbids"
                    : FcsConfig.HOSTILE_ONLY.get() ? "radar_fcs.gui.server_hostile_only" : null;
            case ENTITIES -> !FcsConfig.TARGET_CONTRAPTIONS.get() && !FcsConfig.TARGET_PROJECTILES.get() ? "radar_fcs.gui.server_forbids"
                    : !FcsConfig.TARGET_PROJECTILES.get() ? "radar_fcs.gui.server_no_projectiles"
                    : !FcsConfig.TARGET_CONTRAPTIONS.get() ? "radar_fcs.gui.server_no_contraptions" : null;
        };
        return key == null ? null : Component.translatable(key);
    }

    private void pickTab(int y) {
        cycle(left + 150, y, 160, () -> Component.translatable("radar_fcs.gui.priority." + s.priority.name().toLowerCase(Locale.ROOT)),
                () -> s.priority = next(s.priority, EngageSettings.Priority.values()));
        y += ROW;
        cycle(left + 8, y, 302, () -> check(s.switchByPriority, "radar_fcs.gui.switch_by_priority"),
                () -> s.switchByPriority = !s.switchByPriority);
        y += ROW;
        cycle(left + 150, y, 160, () -> Component.translatable("radar_fcs.gui.switch." + s.switchMode.name().toLowerCase(Locale.ROOT)),
                () -> s.switchMode = next(s.switchMode, EngageSettings.SwitchMode.values()));
        y += ROW;
        if (s.switchMode == EngageSettings.SwitchMode.AFTER_SHOTS) {
            number(left + 150, y, () -> s.switchShots, v -> s.switchShots = v, 1, 1, 10000, null);
        } else if (s.switchMode == EngageSettings.SwitchMode.AFTER_TIME) {
            number(left + 150, y, () -> s.switchSeconds, v -> s.switchSeconds = v, 1, 1, 3600, "radar_fcs.gui.seconds");
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
                    g.drawString(font, Component.translatable("radar_fcs.gui.level"), left + 156, y, 0xA0A0A0, false);
                    y += ROW;
                }
                g.drawString(font, Component.translatable("radar_fcs.gui.speed_from"), left + 8, y, 0xE0E0E0, false);
                g.drawString(font, Component.translatable("radar_fcs.gui.speed_to"), left + 186, y, 0xE0E0E0, false);
                y += ROW;
                hint(g, "radar_fcs.gui.targets_hint", y);
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
                int ly = top + 30;
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
