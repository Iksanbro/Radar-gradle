package dev.radarfcs.client;

import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity.Blip;
import dev.radarfcs.block.RadarMonitorBlockEntity.Show;
import dev.radarfcs.core.Track;
import dev.radarfcs.net.MonitorCommandPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Экран радарного монитора (Shift+ПКМ): список целей с отметкой свой / чужой / неактивный, назначение цели кликом
 * (ручное наведение вычислителей сети), снятие назначения, масштаб и фильтры показа.
 */
public final class RadarMonitorScreen extends Screen {
    private static final int W = 380, H = 240, ROW = 12, LIST_X = 8, LIST_W = 232, LIST_TOP = 30;
    private static final int ROWS = (H - LIST_TOP - 34) / ROW;

    private final BlockPos pos;
    private int left, top, scroll;
    private Button clear, scale;
    private final List<Button> showButtons = new ArrayList<>();

    public RadarMonitorScreen(BlockPos masterPos) {
        super(Component.translatable("radar_fcs.monitor_gui.title"));
        this.pos = masterPos;
    }

    @Nullable
    private RadarMonitorBlockEntity monitor() {
        return minecraft != null && minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof RadarMonitorBlockEntity m
                ? m : null;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        RadarMonitorBlockEntity m = monitor();
        int x = left + LIST_X + LIST_W + 8, w = W - LIST_X - LIST_W - 16, y = top + LIST_TOP;
        clear = addRenderableWidget(Button.builder(Component.translatable("radar_fcs.monitor_gui.clear"),
                b -> send(MonitorCommandPayload.CLEAR, 0, "")).bounds(x, y, w, 18).build());
        y += 20;
        scale = addRenderableWidget(Button.builder(scaleText(m), b -> {
            RadarMonitorBlockEntity mm = monitor();
            if (mm != null) send(MonitorCommandPayload.SCALE, (mm.scaleIndex() + 1) % RadarMonitorBlockEntity.SCALES.length, "");
        }).bounds(x, y, w, 18).build());
        y += 24;
        showButtons.clear();
        for (Show s : Show.values()) {
            showButtons.add(addRenderableWidget(Button.builder(showText(m, s), b -> {
                RadarMonitorBlockEntity mm = monitor();
                if (mm != null) send(MonitorCommandPayload.SHOW, mm.show() ^ s.bit, "");
            }).bounds(x, y, w, 14).build()));
            y += 15;
        }
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W - 84, top + H - 24, 80, 20).build());
    }

    private static Component scaleText(@Nullable RadarMonitorBlockEntity m) {
        int i = m == null ? 0 : m.scaleIndex();
        return i == 0 ? Component.translatable("radar_fcs.monitor_gui.scale_auto")
                : Component.translatable("radar_fcs.monitor_gui.scale", RadarMonitorBlockEntity.SCALES[i]);
    }

    private static Component showText(@Nullable RadarMonitorBlockEntity m, Show s) {
        boolean on = m == null || (m.show() & s.bit) != 0;
        return Component.literal(on ? "☑ " : "☐ ").append(Component.translatable("radar_fcs.monitor_gui.show." + s.name().toLowerCase(Locale.ROOT)))
                .withStyle(on ? ChatFormatting.WHITE : ChatFormatting.GRAY);
    }

    private void send(int action, int value, String id) {
        PacketDistributor.sendToServer(new MonitorCommandPayload(pos, action, value, id));
    }

    @Override
    public void tick() {
        RadarMonitorBlockEntity m = monitor();
        if (m == null) {
            onClose();
            return;
        }
        // состояние приходит с сервера в обновлении блока - подписи кнопок обновляются по нему
        clear.active = !m.designatedName().isEmpty();
        scale.setMessage(scaleText(m));
        for (int i = 0; i < showButtons.size(); i++) showButtons.get(i).setMessage(showText(m, Show.values()[i]));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        RadarMonitorBlockEntity m = monitor();
        if (m == null) return;
        List<Blip> blips = m.blips();
        int lx = left + LIST_X, ly = top + LIST_TOP;
        g.drawString(font, Component.translatable("radar_fcs.monitor_gui.targets", blips.size(), m.radarCount(),
                Math.round(m.displayRange())), lx, top + 18, 0x7FD4FF, false);
        g.fill(lx, ly, lx + LIST_W, ly + ROWS * ROW, 0x80000000);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, blips.size() - ROWS)));
        Blip hovered = null;
        for (int i = 0; i < ROWS && scroll + i < blips.size(); i++) {
            Blip b = blips.get(scroll + i);
            int y = ly + i * ROW;
            boolean over = mouseX >= lx && mouseX < lx + LIST_W && mouseY >= y && mouseY < y + ROW;
            if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) g.fill(lx, y, lx + LIST_W, y + ROW, 0x60FFFF40);
            else if (over) g.fill(lx, y, lx + LIST_W, y + ROW, 0x40FFFFFF);
            if (over) hovered = b;
            int col = RadarMonitorRenderer.blipColor(b);
            g.fill(lx + 2, y + 3, lx + 8, y + 9, col);
            String name = font.plainSubstrByWidth(b.name(), 96);
            g.drawString(font, name, lx + 11, y + 2, 0xFFFFFF, false);
            String dist = Component.translatable("radar_fcs.monitor_gui.dist", String.format(Locale.ROOT, "%.0f", Math.hypot(b.dx(), b.dz())))
                    .getString();
            String spd = Component.translatable("radar_fcs.monitor_gui.speed", String.format(Locale.ROOT, "%.0f", b.speed())).getString();
            g.drawString(font, dist, lx + 150 - font.width(dist), y + 2, 0xC0C0C0, false);
            g.drawString(font, spd, lx + LIST_W - 4 - font.width(spd), y + 2, 0xC0C0C0, false);
        }
        if (blips.isEmpty()) {
            g.drawString(font, Component.translatable(m.radarCount() == 0 ? "radar_fcs.monitor_gui.no_radar" : "radar_fcs.monitor_gui.empty")
                    .withStyle(ChatFormatting.GRAY), lx + 6, ly + 6, 0xA0A0A0, false);
        }
        // строка сведений: цель под мышью, иначе назначенная
        Blip info = hovered;
        if (info == null) for (Blip b : blips) if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) info = b;
        int iy = top + H - 30;
        if (info != null) {
            for (Component c : describe(m, info)) {
                g.drawString(font, c, lx, iy, 0xFFFFFF, false);
                iy += 10;
            }
        } else {
            g.drawWordWrap(font, Component.translatable("radar_fcs.monitor_gui.hint").withStyle(ChatFormatting.GRAY), lx, iy,
                    W - 100, 0xA0A0A0);
        }
    }

    /** Две строки о цели: имя, состояние, тип; координаты, дальность, высота, скорость. */
    static List<Component> describe(RadarMonitorBlockEntity m, Blip b) {
        Vec3 c = m.center();
        String state = (b.flags() & RadarMonitorBlockEntity.FLAG_ENGAGED) != 0 ? "engaged"
                : (b.flags() & RadarMonitorBlockEntity.FLAG_FRIENDLY) != 0 ? "friendly"
                : (b.flags() & RadarMonitorBlockEntity.FLAG_INACTIVE) != 0 ? "inactive"
                : (b.flags() & RadarMonitorBlockEntity.FLAG_HOSTILE) != 0 ? "hostile" : "neutral";
        Track.Kind[] kinds = Track.Kind.values();
        String kind = b.kind() >= 0 && b.kind() < kinds.length ? kinds[b.kind()].name().toLowerCase(Locale.ROOT) : "external";
        Component l1 = Component.literal(b.name()).withColor(RadarMonitorRenderer.blipColor(b) & 0xFFFFFF)
                .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("radar_fcs.monitor_gui.state." + state).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("radar_fcs.monitor_gui.kind." + kind).withStyle(ChatFormatting.GRAY));
        Component l2 = Component.translatable("radar_fcs.monitor_gui.coords",
                String.format(Locale.ROOT, "%.0f", c.x + b.dx()), String.format(Locale.ROOT, "%.0f", c.y + b.dy()),
                String.format(Locale.ROOT, "%.0f", c.z + b.dz()), String.format(Locale.ROOT, "%.0f", Math.hypot(b.dx(), b.dz())),
                String.format(Locale.ROOT, "%+d", Math.round(b.dy())), String.format(Locale.ROOT, "%.1f", b.speed())).withStyle(ChatFormatting.WHITE);
        return List.of(l1, l2);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        RadarMonitorBlockEntity m = monitor();
        int lx = left + LIST_X, ly = top + LIST_TOP;
        if (m != null && button == 0 && mouseX >= lx && mouseX < lx + LIST_W && mouseY >= ly && mouseY < ly + ROWS * ROW) {
            int i = scroll + (int) ((mouseY - ly) / ROW);
            List<Blip> blips = m.blips();
            if (i < blips.size()) {
                Blip b = blips.get(i);
                if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) send(MonitorCommandPayload.CLEAR, 0, "");
                else if ((b.flags() & RadarMonitorBlockEntity.FLAG_FRIENDLY) == 0) send(MonitorCommandPayload.DESIGNATE, 0, b.id());
                playClick();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        scroll = Math.max(0, scroll - (int) Math.signum(dy));
        return true;
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
