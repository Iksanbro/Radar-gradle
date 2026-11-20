package dev.radarfcs.client;

import dev.radarfcs.net.BeaconPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Экран маяка орбиты: код, по которому мост находит маяк. */
public final class OrbitBeaconScreen extends Screen {
    private static final int W = 240, H = 110;
    private final BlockPos pos;
    private final String code0;
    private int left, top;
    private EditBox codeField;

    public OrbitBeaconScreen(BeaconPayloads.Open p) {
        super(Component.translatable("block.radar_fcs.orbit_beacon"));
        pos = p.pos();
        code0 = p.code();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        String keep = codeField != null ? codeField.getValue() : code0;
        codeField = new EditBox(font, left + 8, top + 36, W - 16, 16, Component.empty());
        codeField.setMaxLength(16);
        codeField.setValue(keep);
        addRenderableWidget(codeField);
        setInitialFocus(codeField);
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gun.save"), b -> {
            PacketDistributor.sendToServer(new BeaconPayloads.Save(pos, codeField.getValue()));
            onClose();
        }).bounds(left + W / 2 - 104, top + H - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W / 2 + 4, top + H - 26, 100, 20).build());
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
        g.drawString(font, Component.translatable("radar_fcs.orbit_beacon.code_label"), left + 8, top + 24, 0xC0C0C0, false);
        g.drawWordWrap(font, Component.translatable("radar_fcs.orbit_beacon.hint"), left + 8, top + 56, W - 16, 0x8A9098);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
