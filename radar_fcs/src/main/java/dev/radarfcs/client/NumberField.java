package dev.radarfcs.client;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * Числовое поле настроек: значение меняется колесом мыши над полем (и кнопками «−/+» рядом), шаг задаёт step
 * (обычно {@link dev.radarfcs.core.WheelStep}); с Shift шаг в 10 раз больше.
 */
final class NumberField extends AbstractWidget {
    @FunctionalInterface
    interface Step {
        double apply(double value, int dir, boolean shift);
    }

    private final DoubleSupplier get;
    private final DoubleConsumer set;
    private final Step step;
    private final DoubleFunction<Component> format;

    NumberField(int x, int y, int w, int h, DoubleSupplier get, DoubleConsumer set, Step step, DoubleFunction<Component> format) {
        super(x, y, w, h, format.apply(get.getAsDouble()));
        this.get = get;
        this.set = set;
        this.step = step;
        this.format = format;
    }

    void nudge(int dir) {
        set.accept(step.apply(get.getAsDouble(), dir, Screen.hasShiftDown()));
        setMessage(format.apply(get.getAsDouble()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!active || !visible || !isMouseOver(mouseX, mouseY) || scrollY == 0) return false;
        nudge(scrollY > 0 ? 1 : -1);
        return true;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hover = isHovered();
        g.fill(getX(), getY(), getX() + width, getY() + height, hover ? 0xFF26303A : 0xFF181E25);
        g.renderOutline(getX(), getY(), width, height, hover ? 0xFF9CC8F0 : 0xFF4A5866);
        var font = Minecraft.getInstance().font;
        g.drawCenteredString(font, getMessage(), getX() + width / 2, getY() + (height - font.lineHeight) / 2 + 1, 0xFFFFFF);
    }

    @Override
    public void playDownSound(SoundManager sounds) {
        // клик по полю ничего не меняет
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
