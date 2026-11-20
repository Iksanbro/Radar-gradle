package dev.radarfcs.block;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.radarfcs.core.AngleLimit;
import java.util.function.BooleanSupplier;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Предел поворота в одну сторону - отдельный ползунок Create (как у торсионной пружины): свой значок на боковой грани,
 * удерживать ПКМ - доска 1..360°, правый край - ∞ (без предела, по умолчанию). У блока два таких: ряд 0 - влево (курс)
 * или вверх (наклон), ряд 1 - вправо или вниз; значки - слева и справа на грани (у наклона - сверху и снизу).
 */
public class AngleLimitBehaviour extends ScrollValueBehaviour {
    public static final BehaviourType<AngleLimitBehaviour> FIRST = new BehaviourType<>("radar_fcs_angle_limit_first");
    public static final BehaviourType<AngleLimitBehaviour> SECOND = new BehaviourType<>("radar_fcs_angle_limit_second");

    private final int row;
    private final BooleanSupplier pitch;
    private final Runnable onChange;

    public AngleLimitBehaviour(SmartBlockEntity be, int row, BooleanSupplier pitch, Runnable onChange) {
        super(Component.translatable("radar_fcs.angle_limit.title"), be, new SideBox(row, pitch));
        this.row = row;
        this.pitch = pitch;
        this.onChange = onChange;
        between(AngleLimit.MIN, AngleLimit.FREE);
        value = AngleLimit.FREE;
        withCallback(v -> onChange.run());
    }

    public int limit() {
        return value;
    }

    /** Задать предел (игровые тесты, буфер обмена). */
    public void set(int v) {
        setValue(AngleLimit.clean(v));
    }

    private String symbol() {
        return pitch.getAsBoolean() ? (row == 0 ? "▲" : "▼") : (row == 0 ? "◀" : "▶");
    }

    private String key() {
        return pitch.getAsBoolean() ? (row == 0 ? "up" : "down") : (row == 0 ? "left" : "right");
    }

    private Component title() {
        return Component.translatable("radar_fcs.angle_limit.title_dir", Component.translatable("radar_fcs.angle_limit." + key()));
    }

    private static String number(int v) {
        return AngleLimit.free(v) ? "∞" : Math.max(AngleLimit.MIN, v) + "°";
    }

    /** Подпись обновляется по режиму привода (курс/наклон) - её читает подсказка Create при наведении. */
    @Override
    public boolean isActive() {
        label = title();
        return super.isActive();
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(title(), AngleLimit.FREE, 45,
                ImmutableList.of(Component.translatable("radar_fcs.angle_limit." + key()).withStyle(ChatFormatting.BOLD)),
                new ValueSettingsFormatter(this::formatSettings));
    }

    public MutableComponent formatSettings(ValueSettings s) {
        return Component.literal(number(s.value()));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings s, boolean ctrlDown) {
        int v = AngleLimit.clean(s.value());
        if (v == value) return;
        setValue(v);
        playFeedbackSound(this);
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, value);
    }

    @Override
    public String formatValue() {
        return symbol() + number(value);
    }

    @Override
    public BehaviourType<?> getType() {
        return row == 0 ? FIRST : SECOND;
    }

    /** Свой номер в пакете Create (два ползунка на одном блоке). */
    @Override
    public int netId() {
        return 7300 + row;
    }

    @Override
    public String getClipboardKey() {
        return "AngleLimit" + row;
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        nbt.putInt("AngleLimit" + row, value);
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        String key = "AngleLimit" + row;
        if (nbt.contains(key)) {
            value = AngleLimit.clean(nbt.getInt(key));
        } else {
            int[] old = nbt.getIntArray("AngleLimits");                 // 2.3.0: оба предела одним значком
            value = old.length == 2 ? AngleLimit.clean(old[row]) : AngleLimit.FREE;
        }
    }

    /** Значок на боковой грани (не на гранях с валом): ряд 0 - левее (выше у наклона), ряд 1 - правее (ниже). */
    static class SideBox extends ValueBoxTransform.Sided {
        private final int row;
        private final BooleanSupplier pitch;

        SideBox(int row, BooleanSupplier pitch) {
            this.row = row;
            this.pitch = pitch;
        }

        @Override
        protected Vec3 getSouthLocation() {
            if (pitch.getAsBoolean()) return VecHelper.voxelSpace(8, row == 0 ? 12 : 4, 15.5);
            return VecHelper.voxelSpace(row == 0 ? 4 : 12, 8, 15.5);
        }

        @Override
        public float getScale() {
            return 0.4F;
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            return !state.hasProperty(DirectionalKineticBlock.FACING)
                    || direction.getAxis() != state.getValue(DirectionalKineticBlock.FACING).getAxis();
        }
    }
}
