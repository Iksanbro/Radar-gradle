package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Привод орудия: выход = вход x {@link #output()} (от -1 до 1). Вычислитель задаёт выход каждый тик; без команды
 * дольше {@link #HOLD_TICKS} выход останавливается сам. Передаточное число до орудия (подшипник, станок CBC, шестерни)
 * неизвестно - его знак и величина выучиваются по движению прицела (ствола): {@link #learn}.
 */
public class GunDriveBlockEntity extends SplitShaftBlockEntity implements IHaveGoggleInformation {
    public static final int HOLD_TICKS = 2;
    /** Начальная оценка: градусов/тик на 1 об/мин выхода - как у подшипника Create (0.3). */
    private static final double DEFAULT_GAIN = 0.3;

    private float output;
    private long commandUntil = Long.MIN_VALUE / 2;
    /** Выученный отклик орудия: градусов/тик поворота на 1 об/мин выхода; знак - направление. */
    private double gainMag = DEFAULT_GAIN;
    private int gainSign = 1, against, stall;
    /** Тиков упора (выход есть, движения нет), после которых привод пробует обратное направление. */
    private static final int STALL_TICKS = 12;
    private float lastOutput;

    public GunDriveBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public Direction facing() {
        return getBlockState().getValue(DirectionalKineticBlock.FACING);
    }

    public boolean pitch() {
        return getBlockState().getValue(GunDriveBlock.PITCH);
    }

    public float output() {
        return output;
    }

    @Override
    public float getRotationSpeedModifier(Direction face) {
        if (hasSource() && face != getSourceFacing()) return output;
        return 1;
    }

    /** Скорость входа, об/мин (0 - нет вращения или перегрузка). */
    public double inputRpm() {
        return isOverStressed() ? 0 : Math.abs(getSpeed());
    }

    /** Оценка отклика: градусов/тик на единицу выхода при текущем входе (со знаком). */
    public double rate() {
        return gainSign * gainMag * inputRpm();
    }

    /** Выход на этот тик (вычислитель). */
    public void command(float out) {
        if (level == null) return;
        commandUntil = level.getGameTime() + HOLD_TICKS;
        apply(out);
    }

    private void apply(float out) {
        float q = Math.max(-1, Math.min(1, Math.round(out * 200) / 200f));
        // мелкие изменения не перестраивают кинетическую сеть; ноль и смена знака - всегда
        if (q == output || (Math.abs(q - output) < 0.02f && q != 0 && Math.signum(q) == Math.signum(output))) return;
        detachKinetics();
        output = q;
        attachKinetics();
        sendData();
    }

    /**
     * Наблюдение: за прошлый тик орудие повернулось вокруг оси этого привода на observedDeg. Выученный отклик
     * сравнивается с выходом прошлого тика; знак меняется после двух подряд движений «наоборот».
     */
    public void learn(double observedDeg) {
        double drive = lastOutput * inputRpm();
        lastOutput = output;
        if (Math.abs(drive) < 0.5) {
            stall = 0;
            return;
        }
        if (Math.abs(observedDeg) < 0.02) {
            // крутим, а орудие стоит: упёрлись (предел наклона станка) - знак, видимо, неверный, пробуем обратный
            if (Math.abs(lastOutput) >= 0.3 && ++stall >= STALL_TICKS) {
                gainSign = -gainSign;
                stall = 0;
            }
            return;
        }
        stall = 0;
        double sample = observedDeg / drive;
        if (Math.signum(sample) != gainSign) {
            if (Math.abs(observedDeg) > 0.05 && ++against >= 2) {
                gainSign = -gainSign;
                gainMag = Math.abs(sample);
                against = 0;
            }
            return;
        }
        against = 0;
        gainMag = Math.max(0.002, Math.min(3, gainMag + 0.3 * (Math.abs(sample) - gainMag)));
    }

    /** Без вычислителя (ушёл, выгружен, орудие простаивает) - выход останавливается. */
    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        if (output != 0 && level.getGameTime() > commandUntil) {
            apply(0);
            lastOutput = 0;
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.gun_drive").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(Component.translatable(pitch() ? "radar_fcs.drive.mode_pitch" : "radar_fcs.drive.mode_yaw")
                .withStyle(ChatFormatting.AQUA)));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.drive.output",
                String.format(Locale.ROOT, "%.0f", output * 100)).withStyle(ChatFormatting.GRAY)));
        if (inputRpm() <= 0) tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.controller.no_rotation")
                .withStyle(ChatFormatting.GOLD)));
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("Output", output);
        if (!clientPacket) {
            tag.putDouble("Gain", gainMag);
            tag.putInt("GainSign", gainSign);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        output = tag.getFloat("Output");
        if (!clientPacket && tag.contains("Gain")) {
            gainMag = tag.getDouble("Gain");
            gainSign = tag.getInt("GainSign") < 0 ? -1 : 1;
        }
    }
}
