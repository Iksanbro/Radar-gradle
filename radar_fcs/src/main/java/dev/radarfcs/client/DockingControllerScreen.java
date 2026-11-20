package dev.radarfcs.client;

import dev.radarfcs.block.DockingControllerBlockEntity;
import dev.radarfcs.core.Docking;
import dev.radarfcs.net.DockingPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Экран стыковочного контроллера: роль (порт / штекер), номер, связь с портом Simulated и состояние. */
public final class DockingControllerScreen extends Screen {
    private static final int W = 280, H = 190;

    private final BlockPos pos;
    private Docking.Role role;
    private final int number0;
    private final int linked;
    private final DockingControllerBlockEntity.Status status;
    private int left, top;
    private EditBox numberField;

    public DockingControllerScreen(DockingPayloads.Open p) {
        super(Component.translatable("radar_fcs.docking.title"));
        pos = p.pos();
        CompoundTag t = p.data();
        Docking.Role r;
        try {
            r = Docking.Role.valueOf(t.getString("Role"));
        } catch (IllegalArgumentException e) {
            r = Docking.Role.PORT;
        }
        role = r;
        number0 = DockingControllerBlockEntity.cleanNumber(t.getInt("Number"));
        linked = t.getInt("Connectors");
        DockingControllerBlockEntity.Status s;
        try {
            s = DockingControllerBlockEntity.Status.valueOf(t.getString("Status"));
        } catch (IllegalArgumentException e) {
            s = DockingControllerBlockEntity.Status.NO_CONNECTOR;
        }
        status = s;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        addRenderableWidget(Button.builder(roleText(), b -> {
            role = role == Docking.Role.PORT ? Docking.Role.PLUG : Docking.Role.PORT;
            b.setMessage(roleText());
        }).bounds(left + 8, top + 24, W - 16, 18).build());
        String keep = numberField != null ? numberField.getValue() : String.valueOf(number0);
        numberField = new EditBox(font, left + 170, top + 48, W - 178, 16, Component.empty());
        numberField.setMaxLength(4);
        numberField.setFilter(s -> s.matches("[0-9]*"));
        numberField.setValue(keep);
        addRenderableWidget(numberField);
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gun.save"), b -> {
            CompoundTag t = new CompoundTag();
            t.putString("Role", role.name());
            int n;
            try {
                n = Integer.parseInt(numberField.getValue());
            } catch (NumberFormatException e) {
                n = number0;
            }
            t.putInt("Number", DockingControllerBlockEntity.cleanNumber(n));
            PacketDistributor.sendToServer(new DockingPayloads.Save(pos, t));
            onClose();
        }).bounds(left + W / 2 - 104, top + H - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W / 2 + 4, top + H - 26, 100, 20).build());
    }

    private Component roleText() {
        return Component.translatable(role.key()).withStyle(role == Docking.Role.PORT ? ChatFormatting.AQUA : ChatFormatting.GOLD);
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
        g.drawString(font, Component.translatable("radar_fcs.docking.number"), left + 8, top + 52, 0xC0C0C0, false);
        g.drawString(font, linked > 0 ? Component.translatable("radar_fcs.docking.connector_linked", linked)
                        : Component.translatable("radar_fcs.docking.connector_none"),
                left + 8, top + 72, linked > 0 ? 0x7FE07F : 0xE07F7F, false);
        g.drawString(font, Component.translatable(status.key()), left + 8, top + 84, 0xA0A8B0, false);
        g.drawWordWrap(font, Component.translatable(role.key() + ".hint"), left + 8, top + 100, W - 16, 0x8A9098);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
