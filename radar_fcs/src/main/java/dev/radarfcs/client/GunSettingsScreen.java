package dev.radarfcs.client;

import dev.radarfcs.fcs.GunSettings;
import dev.radarfcs.net.GunPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Настройки орудия (Shift+ПКМ по орудийному линку): имя, тип, участие в автоприцеливании и в стрельбе. */
public final class GunSettingsScreen extends Screen {
    private static final int W = 300, H = 178;

    private final BlockPos link;
    private final String network;
    private GunSettings.Type type;
    private boolean autoAim, fire;
    private final String initialName;
    private int left, top;
    private EditBox name;
    private Button typeButton, aimButton, fireButton;

    public GunSettingsScreen(GunPayloads.Open p) {
        super(Component.translatable("radar_fcs.gun.title"));
        link = p.link();
        network = p.network();
        GunSettings s = GunSettings.read(p.settings());
        type = s.type;
        autoAim = s.autoAim;
        fire = s.fire;
        initialName = s.name;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        String keep = name != null ? name.getValue() : initialName;
        name = new EditBox(font, left + 110, top + 26, W - 120, 16, Component.translatable("radar_fcs.gun.name"));
        name.setMaxLength(GunSettings.NAME_MAX);
        name.setValue(keep);
        addRenderableWidget(name);
        typeButton = addRenderableWidget(Button.builder(typeLabel(), b -> {
            GunSettings.Type[] v = GunSettings.Type.values();
            type = v[(type.ordinal() + 1) % v.length];
            b.setMessage(typeLabel());
        }).bounds(left + 110, top + 48, W - 120, 18).build());
        aimButton = addRenderableWidget(Button.builder(onOff(autoAim), b -> {
            autoAim = !autoAim;
            b.setMessage(onOff(autoAim));
        }).bounds(left + 110, top + 70, W - 120, 18).build());
        fireButton = addRenderableWidget(Button.builder(onOff(fire), b -> {
            fire = !fire;
            b.setMessage(onOff(fire));
        }).bounds(left + 110, top + 92, W - 120, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gun.save"), b -> {
            PacketDistributor.sendToServer(new GunPayloads.Save(link, new GunSettings(type, autoAim, fire, name.getValue(), 0).write()));
            onClose();
        }).bounds(left + W / 2 - 104, top + H - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W / 2 + 4, top + H - 26, 100, 20).build());
    }

    private Component typeLabel() {
        return Component.translatable(type.key());
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
        g.drawString(font, Component.translatable("radar_fcs.gun.header", network), left + 8, top + 8, 0xFFD27F, false);
        g.drawString(font, Component.translatable("radar_fcs.gun.name"), left + 8, top + 30, 0xC0C0C0, false);
        g.drawString(font, Component.translatable("radar_fcs.gun.type"), left + 8, top + 53, 0xC0C0C0, false);
        g.drawString(font, Component.translatable("radar_fcs.gun.auto_aim"), left + 8, top + 75, 0xC0C0C0, false);
        g.drawString(font, Component.translatable("radar_fcs.gun.fire"), left + 8, top + 97, 0xC0C0C0, false);
        g.drawWordWrap(font, Component.translatable(type.key() + ".hint"), left + 8, top + 116, W - 16, 0x8A9098);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
