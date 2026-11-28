package dev.radarfcs.client;

import dev.radarfcs.core.EngageSettings.Category;
import dev.radarfcs.net.RocketNosePayloads;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Экран носа ракеты. Тепловая головка: какие типы целей (из списка целей вычислителя) она ищет после пуска.
 * Программируемый нос: режим пуска (автопуск / по редстоуну) и допустимая цель автопуска (любая или один тип).
 */
public final class RocketNoseScreen extends Screen {
    private static final int W = 260;
    private final boolean heat;
    private final BlockPos pos;
    private int mask;
    private int mode, trigger;
    private final String network;
    private int left, top, h;

    public RocketNoseScreen(RocketNosePayloads.OpenHeat p) {
        super(Component.translatable("block.radar_fcs.heat_seeker_nose"));
        heat = true;
        pos = p.pos();
        mask = p.mask();
        network = "";
    }

    public RocketNoseScreen(RocketNosePayloads.OpenNet p) {
        super(Component.translatable("block.radar_fcs.network_nose"));
        heat = false;
        pos = p.pos();
        mode = p.mode();
        trigger = p.trigger();
        network = p.network();
    }

    private static Component typeName(Category c) {
        return Component.translatable("radar_fcs.gui.type_short." + c.name().toLowerCase(Locale.ROOT));
    }

    @Override
    protected void init() {
        Category[] cats = Category.values();
        h = heat ? 58 + ((cats.length + 1) / 2) * 24 + 30 : 150;
        left = (width - W) / 2;
        top = (height - h) / 2;
        clearWidgets();
        if (heat) {
            for (int i = 0; i < cats.length; i++) {
                Category c = cats[i];
                int bit = 1 << c.ordinal();
                int x = left + 8 + (i % 2) * ((W - 20) / 2 + 4), y = top + 40 + (i / 2) * 24;
                addRenderableWidget(Button.builder(toggleLabel(c, (mask & bit) != 0), b -> {
                    mask ^= bit;
                    b.setMessage(toggleLabel(c, (mask & bit) != 0));
                }).bounds(x, y, (W - 20) / 2, 20).build());
            }
        } else {
            addRenderableWidget(Button.builder(modeLabel(), b -> {
                mode = 1 - mode;
                b.setMessage(modeLabel());
            }).bounds(left + 8, top + 50, W - 16, 20).build());
            addRenderableWidget(Button.builder(triggerLabel(), b -> {
                trigger = trigger + 1 >= cats.length ? -1 : trigger + 1;
                b.setMessage(triggerLabel());
            }).bounds(left + 8, top + 76, W - 16, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gun.save"), b -> {
            if (heat) PacketDistributor.sendToServer(new RocketNosePayloads.SaveHeat(pos, mask));
            else PacketDistributor.sendToServer(new RocketNosePayloads.SaveNet(pos, mode, trigger));
            onClose();
        }).bounds(left + W / 2 - 104, top + h - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W / 2 + 4, top + h - 26, 100, 20).build());
    }

    private static Component toggleLabel(Category c, boolean on) {
        return Component.literal(on ? "✔ " : "✖ ").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED).append(typeName(c).copy()
                .withStyle(on ? ChatFormatting.WHITE : ChatFormatting.GRAY));
    }

    private Component modeLabel() {
        return Component.translatable(mode == 0 ? "radar_fcs.rocket_nose.mode_auto" : "radar_fcs.rocket_nose.mode_redstone");
    }

    private Component triggerLabel() {
        Component t = trigger < 0 ? Component.translatable("radar_fcs.rocket_nose.trigger_any") : typeName(Category.values()[trigger]);
        return Component.translatable("radar_fcs.rocket_nose.trigger", t);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + h, 0xE0101418);
        g.renderOutline(left, top, W, h, 0xFF3A4048);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, left + 8, top + 8, 0xFFD27F, false);
        if (heat) {
            g.drawWordWrap(font, Component.translatable("radar_fcs.rocket_nose.heat_hint"), left + 8, top + 22, W - 16, 0x8A9098);
        } else {
            g.drawString(font, network.isEmpty() ? Component.translatable("radar_fcs.rocket_nose.no_network")
                    : Component.translatable("radar_fcs.rocket_nose.network", network), left + 8, top + 24, network.isEmpty() ? 0xFF8060 : 0x80D0FF, false);
            g.drawWordWrap(font, Component.translatable(mode == 0 ? "radar_fcs.rocket_nose.auto_hint" : "radar_fcs.rocket_nose.redstone_hint"),
                    left + 8, top + 102, W - 16, 0x8A9098);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
