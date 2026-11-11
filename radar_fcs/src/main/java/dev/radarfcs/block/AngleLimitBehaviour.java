package dev.radarfcs.block;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.radarfcs.core.AngleLimit;
import java.util.function.BooleanSupplier;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/**
 * Пределы поворота контроллера наводки или привода орудия - ползунок Create (как у торсионной пружины): значок на
 * боковой грани, удерживать ПКМ - доска с двумя рядами, по ряду на направление (курс: ⟲ влево / ⟳ вправо; наклон:
 * ▲ вверх / ▼ вниз). 1..360°, правый край - ∞ (без ограничения, по умолчанию).
 */
public class AngleLimitBehaviour extends ScrollValueBehaviour {
    /** Ряды: 0 - влево (курс) или вверх (наклон), 1 - вправо или вниз. */
    private final int[] limits = {AngleLimit.FREE, AngleLimit.FREE};
    private int lastRow;
    private final BooleanSupplier pitch;
    private final Runnable onChange;

    public AngleLimitBehaviour(SmartBlockEntity be, BooleanSupplier pitch, Runnable onChange) {
        super(Component.translatable("radar_fcs.angle_limit.title"), be, new SideBox());
        this.pitch = pitch;
        this.onChange = onChange;
        between(AngleLimit.MIN, AngleLimit.FREE);
        value = AngleLimit.FREE;
    }

    /** Предел ряда 0: влево (курс) или вверх (наклон), градусы; {@link AngleLimit#FREE} - нет. */
    public int first() {
        return limits[0];
    }

    /** Предел ряда 1: вправо (курс) или вниз (наклон). */
    public int second() {
        return limits[1];
    }

    public boolean limited() {
        return !AngleLimit.free(limits[0]) || !AngleLimit.free(limits[1]);
    }

    /** Задать пределы (игровые тесты, буфер обмена). */
    public void set(int first, int second) {
        int a = AngleLimit.clean(first), b = AngleLimit.clean(second);
        if (a == limits[0] && b == limits[1]) return;
        limits[0] = a;
        limits[1] = b;
        changed();
    }

    private void changed() {
        value = limits[lastRow];
        blockEntity.setChanged();
        blockEntity.sendData();
        onChange.run();
    }

    private Component rowLabel(int row) {
        boolean p = pitch.getAsBoolean();
        String key = p ? (row == 0 ? "up" : "down") : (row == 0 ? "left" : "right");
        return Component.translatable("radar_fcs.angle_limit." + key).withStyle(ChatFormatting.BOLD);
    }

    private static String symbol(boolean pitch, int row) {
        return pitch ? (row == 0 ? "▲" : "▼") : (row == 0 ? "⟲" : "⟳");
    }

    private static String number(int v) {
        return AngleLimit.free(v) ? "∞" : Math.max(AngleLimit.MIN, v) + "°";
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(label, AngleLimit.FREE, 45, ImmutableList.of(rowLabel(0), rowLabel(1)),
                new ValueSettingsFormatter(this::formatSettings));
    }

    public MutableComponent formatSettings(ValueSettings s) {
        return Component.literal(number(s.value()));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings s, boolean ctrlDown) {
        int row = Math.max(0, Math.min(1, s.row()));
        int v = AngleLimit.clean(s.value());
        if (row == lastRow && limits[row] == v) return;
        lastRow = row;
        limits[row] = v;
        changed();
        playFeedbackSound(this);
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(lastRow, limits[lastRow]);
    }

    /** Текст на значке: оба предела. */
    @Override
    public String formatValue() {
        boolean p = pitch.getAsBoolean();
        return symbol(p, 0) + number(limits[0]) + " " + symbol(p, 1) + number(limits[1]);
    }

    @Override
    public boolean writeToClipboard(@NotNull HolderLookup.Provider registries, CompoundTag tag, Direction side) {
        tag.putIntArray("AngleLimits", limits.clone());
        return true;
    }

    @Override
    public boolean readFromClipboard(@NotNull HolderLookup.Provider registries, CompoundTag tag, Player player, Direction side, boolean simulate) {
        int[] l = tag.getIntArray("AngleLimits");
        if (l.length != 2) return false;
        if (!simulate) set(l[0], l[1]);
        return true;
    }

    @Override
    public String getClipboardKey() {
        return "AngleLimits";
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        nbt.putIntArray("AngleLimits", limits.clone());
        nbt.putInt("AngleLimitRow", lastRow);
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        int[] l = nbt.getIntArray("AngleLimits");
        if (l.length == 2) {
            limits[0] = AngleLimit.clean(l[0]);
            limits[1] = AngleLimit.clean(l[1]);
        }
        lastRow = Math.max(0, Math.min(1, nbt.getInt("AngleLimitRow")));
        value = limits[lastRow];
    }

    /** Значок - в середине боковой грани (не на гранях с валом). */
    static class SideBox extends ValueBoxTransform.Sided {
        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 8, 15.5);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            return !state.hasProperty(DirectionalKineticBlock.FACING)
                    || direction.getAxis() != state.getValue(DirectionalKineticBlock.FACING).getAxis();
        }
    }
}
