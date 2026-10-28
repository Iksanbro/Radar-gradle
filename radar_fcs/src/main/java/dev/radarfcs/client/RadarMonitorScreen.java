package dev.radarfcs.client;

import dev.radarfcs.block.MonitorIcons;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.block.RadarMonitorBlockEntity.Blip;
import dev.radarfcs.block.RadarMonitorBlockEntity.Mark;
import dev.radarfcs.block.RadarMonitorBlockEntity.Show;
import dev.radarfcs.core.TargetCategory;
import dev.radarfcs.core.Track;
import dev.radarfcs.net.MonitorCommandPayload;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Окно радарного монитора (Shift+ПКМ). Вкладки: «Экран» - развёртка как на мониторе (наведение курсора - сведения
 * о цели, клик по отметке - назначить или снять цель), «Цели» - список, «Вид» - фильтры показа, значки по типам
 * (форма, цвет, размер), подписи имён с транспондеров (всегда / при наведении), масштаб.
 */
public final class RadarMonitorScreen extends Screen {
    private enum Tab { SCOPE, LIST, VIEW }

    private static final int W = 420, H = 240, ROW = 12, R = 92;
    private static final int LIST_TOP = 42, LIST_ROWS = (H - LIST_TOP - 34) / ROW;

    private final BlockPos pos;
    private Tab tab = Tab.SCOPE;
    private int left, top, scroll;
    private String state = "";
    @Nullable private Blip hovered;
    @Nullable private Mark hoveredMark;

    public RadarMonitorScreen(BlockPos masterPos) {
        super(Component.translatable("radar_fcs.monitor_gui.title"));
        this.pos = masterPos;
    }

    @Nullable
    private RadarMonitorBlockEntity monitor() {
        return minecraft != null && minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof RadarMonitorBlockEntity m
                ? m : null;
    }

    /** Состояние настроек с сервера: изменилось - виджеты перестраиваются. */
    private static String stateOf(@Nullable RadarMonitorBlockEntity m) {
        if (m == null) return "";
        int[] ic = new int[MonitorIcons.COUNT];
        for (int i = 0; i < ic.length; i++) ic[i] = m.icon(i);
        return m.show() + "/" + m.scaleIndex() + "/" + m.labels() + "/" + Arrays.toString(ic) + "/" + m.designatedName().isEmpty();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        RadarMonitorBlockEntity m = monitor();
        state = stateOf(m);
        int tw = (W - 8) / Tab.values().length;
        for (Tab t : Tab.values()) {
            Button b = addRenderableWidget(Button.builder(Component.translatable("radar_fcs.monitor_gui.tab." + t.name().toLowerCase(Locale.ROOT)),
                    x -> { tab = t; rebuildWidgets(); }).bounds(left + 4 + t.ordinal() * tw, top + 4, tw - 2, 18).build());
            b.active = t != tab;
        }
        if (tab == Tab.SCOPE) {
            int x = left + 2 * R + 20, w = W - 2 * R - 28;
            Button clear = addRenderableWidget(Button.builder(Component.translatable("radar_fcs.monitor_gui.clear"),
                    b -> send(MonitorCommandPayload.CLEAR, 0, "")).bounds(x, top + 30, w, 18).build());
            clear.active = m != null && !m.designatedName().isEmpty();
            scaleButton(m, x, top + 52, w);
            labelsButton(m, x, top + 74, w);
        } else if (tab == Tab.VIEW) {
            viewTab(m);
        }
        addRenderableWidget(Button.builder(Component.translatable("radar_fcs.gui.close"), b -> onClose())
                .bounds(left + W - 84, top + H - 24, 80, 20).build());
    }

    private void scaleButton(@Nullable RadarMonitorBlockEntity m, int x, int y, int w) {
        int i = m == null ? 0 : m.scaleIndex();
        Component t = i == 0 ? Component.translatable("radar_fcs.monitor_gui.scale_auto")
                : Component.translatable("radar_fcs.monitor_gui.scale", RadarMonitorBlockEntity.SCALES[i]);
        addRenderableWidget(Button.builder(t, b -> send(MonitorCommandPayload.SCALE, (i + 1) % RadarMonitorBlockEntity.SCALES.length, ""))
                .bounds(x, y, w, 18).build());
    }

    private void labelsButton(@Nullable RadarMonitorBlockEntity m, int x, int y, int w) {
        boolean always = m != null && m.labels() == RadarMonitorBlockEntity.LABELS_ALWAYS;
        addRenderableWidget(Button.builder(Component.translatable(always ? "radar_fcs.monitor_gui.labels_always" : "radar_fcs.monitor_gui.labels_hover"),
                b -> send(MonitorCommandPayload.LABELS, always ? RadarMonitorBlockEntity.LABELS_HOVER : RadarMonitorBlockEntity.LABELS_ALWAYS, ""))
                .bounds(x, y, w, 18).build());
    }

    /** Строки «Вид»: фильтр типа, значок (форма, цвет, размер); затем «свои», «неактивные», подписи, масштаб. */
    private void viewTab(@Nullable RadarMonitorBlockEntity m) {
        int y = top + 42;
        for (int cat = 0; cat < MonitorIcons.COUNT; cat++) {
            Show filter = cat < TargetCategory.values().length ? Show.values()[cat] : Show.TRANSPONDERS;
            showButton(m, filter, left + 8, y, 140);
            int icon = m == null ? MonitorIcons.defaults()[cat] : m.icon(cat);
            int c = cat;
            MonitorIcons.Shape sh = MonitorIcons.shape(icon);
            int color = MonitorIcons.color(icon), size = MonitorIcons.size(icon);
            addRenderableWidget(Button.builder(Component.translatable("radar_fcs.monitor_gui.shape." + sh.name().toLowerCase(Locale.ROOT)),
                    b -> setIcon(c, MonitorIcons.pack(MonitorIcons.Shape.values()[(sh.ordinal() + 1) % MonitorIcons.Shape.values().length], color, size)))
                    .bounds(left + 170, y, 76, 15).build());
            addRenderableWidget(Button.builder(Component.translatable("radar_fcs.monitor_gui.color." + MonitorIcons.COLOR_KEYS[color])
                                    .withColor(color == 0 ? 0xFFFFFF : MonitorIcons.PALETTE[color] & 0xFFFFFF),
                    b -> setIcon(c, MonitorIcons.pack(sh, (color + 1) % MonitorIcons.PALETTE.length, size)))
                    .bounds(left + 250, y, 86, 15).build());
            addRenderableWidget(Button.builder(Component.translatable("radar_fcs.monitor_gui.size." + size),
                    b -> setIcon(c, MonitorIcons.pack(sh, color, (size + 1) % MonitorIcons.SIZES.length)))
                    .bounds(left + 340, y, 72, 15).build());
            y += 16;
        }
        y += 2;
        showButton(m, Show.FRIENDLY, left + 8, y, 140);
        showButton(m, Show.INACTIVE, left + 152, y, 140);
        y += 20;
        labelsButton(m, left + 8, y, 200);
        scaleButton(m, left + 212, y, 110);
    }

    private void showButton(@Nullable RadarMonitorBlockEntity m, Show s, int x, int y, int w) {
        boolean on = m == null || (m.show() & s.bit) != 0;
        addRenderableWidget(Button.builder(Component.literal(on ? "☑ " : "☐ ").append(Component.translatable(
                                "radar_fcs.monitor_gui.show." + s.name().toLowerCase(Locale.ROOT))).withStyle(on ? ChatFormatting.WHITE : ChatFormatting.GRAY),
                b -> {
                    RadarMonitorBlockEntity mm = monitor();
                    if (mm != null) send(MonitorCommandPayload.SHOW, mm.show() ^ s.bit, "");
                }).bounds(x, y, w, 15).build());
    }

    private void setIcon(int category, int packed) {
        send(MonitorCommandPayload.ICON, category << 16 | packed, "");
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
        String s = stateOf(m);
        if (!s.equals(state)) rebuildWidgets();                      // настройки пришли с сервера
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        RadarMonitorBlockEntity m = monitor();
        if (m == null) return;
        g.drawString(font, Component.translatable("radar_fcs.monitor_gui.targets", m.blips().size(), m.radarCount(),
                Math.round(m.displayRange())), left + 8, top + H - 16, 0x7FD4FF, false);
        switch (tab) {
            case SCOPE -> renderScope(g, m, mouseX, mouseY);
            case LIST -> renderList(g, m, mouseX, mouseY);
            case VIEW -> renderView(g, m);
        }
    }

    /** Мировое смещение -> точка развёртки окна (вверх - от зрителя у монитора, как на экране в мире). */
    private float[] toScope(RadarMonitorBlockEntity m, float dx, float dz) {
        Direction f = m.facing(), right = f.getCounterClockWise();
        float u = dx * right.getStepX() + dz * right.getStepZ();
        float v = -(dx * f.getStepX() + dz * f.getStepZ());
        float k = R / Math.max(1f, m.displayRange());
        float x = u * k, y = v * k;
        if (x * x + y * y > R * R) return null;
        return new float[]{left + 8 + R + x, top + 30 + R - y};
    }

    private void renderScope(GuiGraphics g, RadarMonitorBlockEntity m, int mouseX, int mouseY) {
        int cx = left + 8 + R, cy = top + 30 + R;
        for (float[] sp : MonitorIcons.spans(MonitorIcons.Shape.CIRCLE, 2 * R)) {
            g.fill(cx + Math.round(sp[2] * R), cy - Math.round(sp[1] * R), cx + Math.round(sp[3] * R), cy - Math.round(sp[0] * R), 0xFF0B2412);
        }
        for (int k = 1; k <= 3; k++) circle(g, cx, cy, R * k / 3f, 0xFF40C060);
        g.fill(cx - R, cy, cx + R, cy + 1, 0x6040C060);
        g.fill(cx, cy - R, cx + 1, cy + R, 0x6040C060);
        if (!m.powered()) {
            g.drawCenteredString(font, Component.translatable("radar_fcs.monitor.no_energy_short"), cx, cy - 4, 0xFF5050);
            return;
        }
        for (RadarMonitorBlockEntity.Site s : m.sites()) {
            float[] p = toScope(m, s.dx(), s.dz());
            if (p != null) g.fill((int) p[0] - 2, (int) p[1] - 2, (int) p[0] + 2, (int) p[1] + 2, 0xFF60FF80);
        }
        g.fill(cx - 3, cy, cx + 4, cy + 1, 0xFFFFFFFF);
        g.fill(cx, cy - 3, cx + 1, cy + 4, 0xFFFFFFFF);
        boolean always = m.labels() == RadarMonitorBlockEntity.LABELS_ALWAYS;
        hovered = null;
        hoveredMark = null;
        double best = 7;
        int tIcon = m.icon(MonitorIcons.TRANSPONDER);
        for (Mark mk : m.marks()) {
            float[] p = toScope(m, mk.dx(), mk.dz());
            if (p == null) continue;
            int col = MonitorIcons.color(tIcon) == 0 ? (mk.friendly() ? 0xFF40FF60 : 0xFFFFA020) : MonitorIcons.PALETTE[MonitorIcons.color(tIcon)];
            icon(g, MonitorIcons.shape(tIcon), p[0], p[1], 3.5f * MonitorIcons.SIZES[MonitorIcons.size(tIcon)], col);
            double d = Math.hypot(p[0] - mouseX, p[1] - mouseY);
            if (d < best) {
                best = d;
                hoveredMark = mk;
            }
            if (always && !mk.name().isEmpty()) label(g, mk.name(), p[0] + 6, p[1] - 3, col);
        }
        for (Blip b : m.blips()) {
            float[] p = toScope(m, b.dx(), b.dz());
            if (p == null) continue;
            int ic = m.icon(b.category());
            float sz = 3.5f * MonitorIcons.SIZES[MonitorIcons.size(ic)];
            int col = RadarMonitorRenderer.iconColor(b, ic);
            icon(g, MonitorIcons.shape(ic), p[0], p[1], sz, col);
            if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) {
                g.renderOutline((int) (p[0] - sz - 3), (int) (p[1] - sz - 3), (int) (2 * sz + 7), (int) (2 * sz + 7), 0xFFFFFF40);
            }
            double d = Math.hypot(p[0] - mouseX, p[1] - mouseY);
            if (d < best) {
                best = d;
                hovered = b;
                hoveredMark = null;
            }
            if (always && !b.tag().isEmpty()) label(g, b.tag(), p[0] + sz + 3, p[1] - 3, col);
        }
        // наведённая отметка: рамка, подпись (в режиме «при наведении») и сведения справа
        List<Component> info = List.of();
        if (hovered != null) {
            float[] p = toScope(m, hovered.dx(), hovered.dz());
            if (p != null) g.renderOutline((int) p[0] - 6, (int) p[1] - 6, 13, 13, 0xFFFFFFFF);
            if (!always && !hovered.tag().isEmpty() && p != null) label(g, hovered.tag(), p[0] + 8, p[1] - 3, 0xFFFFFFFF);
            info = describe(m, hovered);
        } else if (hoveredMark != null) {
            float[] p = toScope(m, hoveredMark.dx(), hoveredMark.dz());
            if (p != null) g.renderOutline((int) p[0] - 6, (int) p[1] - 6, 13, 13, 0xFFFFFFFF);
            if (!always && !hoveredMark.name().isEmpty() && p != null) label(g, hoveredMark.name(), p[0] + 8, p[1] - 3, 0xFFFFFFFF);
            info = describeMark(m, hoveredMark);
        } else {
            for (Blip b : m.blips()) if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) info = describe(m, b);
        }
        int x = left + 2 * R + 20, y = top + 100, w = W - 2 * R - 28;
        if (info.isEmpty()) {
            g.drawWordWrap(font, Component.translatable("radar_fcs.monitor_gui.scope_hint").withStyle(ChatFormatting.GRAY), x, y, w, 0xA0A0A0);
        }
        for (Component c : info) {
            for (FormattedCharSequence part : font.split(c, w)) {
                g.drawString(font, part, x, y, 0xFFFFFF, false);
                y += 10;
            }
        }
    }

    private void circle(GuiGraphics g, int cx, int cy, float r, int color) {
        int n = Math.max(24, (int) (r * 3));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** Значок фигуры shape с центром (x, y) и полуразмером size пикселей (y фигуры - вверх). */
    private void icon(GuiGraphics g, MonitorIcons.Shape shape, float x, float y, float size, int color) {
        int rows = Math.max(2, Math.round(2 * size));
        for (float[] sp : MonitorIcons.spans(shape, rows)) {
            int x0 = Math.round(x + sp[2] * size), x1 = Math.round(x + sp[3] * size);
            int y0 = Math.round(y - sp[1] * size), y1 = Math.round(y - sp[0] * size);
            if (x1 <= x0) x1 = x0 + 1;
            if (y1 <= y0) y1 = y0 + 1;
            g.fill(x0, y0, x1, y1, color);
        }
    }

    private void label(GuiGraphics g, String text, float x, float y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.fill(-1, -1, font.width(text) + 1, font.lineHeight, 0xA0000000);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private void renderList(GuiGraphics g, RadarMonitorBlockEntity m, int mouseX, int mouseY) {
        List<Blip> blips = m.blips();
        int lx = left + 8, ly = top + LIST_TOP, lw = W - 16;
        g.fill(lx, ly, lx + lw, ly + LIST_ROWS * ROW, 0x80000000);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, blips.size() - LIST_ROWS)));
        Blip over = null;
        for (int i = 0; i < LIST_ROWS && scroll + i < blips.size(); i++) {
            Blip b = blips.get(scroll + i);
            int y = ly + i * ROW;
            boolean on = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + ROW;
            if ((b.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) g.fill(lx, y, lx + lw, y + ROW, 0x60FFFF40);
            else if (on) g.fill(lx, y, lx + lw, y + ROW, 0x40FFFFFF);
            if (on) over = b;
            int ic = m.icon(b.category());
            icon(g, MonitorIcons.shape(ic), lx + 6, y + 6, 3.5f, RadarMonitorRenderer.iconColor(b, ic));
            g.drawString(font, font.plainSubstrByWidth(b.name(), 150), lx + 14, y + 2, 0xFFFFFF, false);
            Track.Kind[] kinds = Track.Kind.values();
            String kind = b.kind() >= 0 && b.kind() < kinds.length ? kinds[b.kind()].name().toLowerCase(Locale.ROOT) : "external";
            g.drawString(font, Component.translatable("radar_fcs.monitor_gui.kind." + kind), lx + 170, y + 2, 0xA0A0A0, false);
            String dist = Component.translatable("radar_fcs.monitor_gui.dist", String.format(Locale.ROOT, "%.0f", Math.hypot(b.dx(), b.dz())))
                    .getString();
            String spd = Component.translatable("radar_fcs.monitor_gui.speed", String.format(Locale.ROOT, "%.0f", b.speed())).getString();
            g.drawString(font, dist, lx + 300 - font.width(dist), y + 2, 0xC0C0C0, false);
            g.drawString(font, spd, lx + lw - 4 - font.width(spd), y + 2, 0xC0C0C0, false);
        }
        g.drawString(font, Component.translatable("radar_fcs.monitor_gui.list_hint").withStyle(ChatFormatting.GRAY), lx, top + 30, 0xA0A0A0, false);
        if (blips.isEmpty()) {
            g.drawString(font, Component.translatable(m.radarCount() == 0 ? "radar_fcs.monitor_gui.no_radar" : "radar_fcs.monitor_gui.empty")
                    .withStyle(ChatFormatting.GRAY), lx + 6, ly + 6, 0xA0A0A0, false);
        }
        if (over != null) {
            int y = top + H - 46;
            for (Component c : describe(m, over)) {
                g.drawString(font, c, lx, y, 0xFFFFFF, false);
                y += 10;
            }
        }
    }

    private void renderView(GuiGraphics g, RadarMonitorBlockEntity m) {
        g.drawString(font, Component.translatable("radar_fcs.monitor_gui.col_type"), left + 8, top + 30, 0xA0A0A0, false);
        g.drawString(font, Component.translatable("radar_fcs.monitor_gui.col_icon"), left + 170, top + 30, 0xA0A0A0, false);
        g.drawString(font, Component.translatable("radar_fcs.monitor_gui.col_color"), left + 250, top + 30, 0xA0A0A0, false);
        g.drawString(font, Component.translatable("radar_fcs.monitor_gui.col_size"), left + 340, top + 30, 0xA0A0A0, false);
        int y = top + 42;
        for (int cat = 0; cat < MonitorIcons.COUNT; cat++) {
            int ic = m.icon(cat);
            int col = MonitorIcons.color(ic) == 0 ? (cat == MonitorIcons.TRANSPONDER ? 0xFF40FF60 : 0xFFFFA020) : MonitorIcons.PALETTE[MonitorIcons.color(ic)];
            icon(g, MonitorIcons.shape(ic), left + 157, y + 7, 3.5f * MonitorIcons.SIZES[MonitorIcons.size(ic)], col);
            y += 16;
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

    /** Две строки о наземном транспондере: имя и свой ли; координаты и дальность. */
    static List<Component> describeMark(RadarMonitorBlockEntity m, Mark mk) {
        Vec3 c = m.center();
        Component name = mk.name().isEmpty() ? Component.translatable("radar_fcs.monitor_gui.transponder")
                : Component.literal(mk.name());
        Component l1 = name.copy().withStyle(mk.friendly() ? ChatFormatting.GREEN : ChatFormatting.GOLD)
                .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(mk.friendly() ? "radar_fcs.monitor_gui.mark_friendly" : "radar_fcs.monitor_gui.mark_foreign")
                        .withStyle(ChatFormatting.GRAY));
        Component l2 = Component.translatable("radar_fcs.monitor_gui.mark_coords", String.format(Locale.ROOT, "%.0f", c.x + mk.dx()),
                String.format(Locale.ROOT, "%.0f", c.z + mk.dz()), String.format(Locale.ROOT, "%.0f", Math.hypot(mk.dx(), mk.dz())))
                .withStyle(ChatFormatting.WHITE);
        return List.of(l1, l2);
    }

    // ------------------------------------------------------------------ ввод

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        RadarMonitorBlockEntity m = monitor();
        if (m != null && button == 0) {
            Blip target = null;
            if (tab == Tab.SCOPE && Math.hypot(mouseX - (left + 8 + R), mouseY - (top + 30 + R)) <= R) target = hovered;
            if (tab == Tab.LIST) {
                int lx = left + 8, ly = top + LIST_TOP;
                if (mouseX >= lx && mouseX < lx + W - 16 && mouseY >= ly && mouseY < ly + LIST_ROWS * ROW) {
                    int i = scroll + (int) ((mouseY - ly) / ROW);
                    if (i < m.blips().size()) target = m.blips().get(i);
                }
            }
            if (target != null) {
                if ((target.flags() & RadarMonitorBlockEntity.FLAG_DESIGNATED) != 0) send(MonitorCommandPayload.CLEAR, 0, "");
                else if ((target.flags() & RadarMonitorBlockEntity.FLAG_FRIENDLY) == 0) send(MonitorCommandPayload.DESIGNATE, 0, target.id());
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
        if (tab == Tab.LIST) {
            scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
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
