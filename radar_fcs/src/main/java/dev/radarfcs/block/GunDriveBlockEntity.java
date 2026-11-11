package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.AngleLimit;
import dev.radarfcs.core.Aim;
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
import net.minecraft.world.phys.Vec3;

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
    /**
     * Пределы поворота (ползунок на боковой грани): курс - влево/вправо от «нуля» (направления орудия в момент, когда
     * ограничение включили), наклон - вверх/вниз от горизонта основания.
     */
    public AngleLimitBehaviour limits;
    /** Ноль курса: угол направления орудия в осях основания (против часовой от юга), NaN - не задан. */
    private double zero = Double.NaN;
    /** Курс от нуля, накопительно (+ влево, против часовой сверху); NaN - не считали. */
    private double turned = Double.NaN;

    public GunDriveBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        behaviours.add(limits = new AngleLimitBehaviour(this, this::pitch, this::limitsChanged));
    }

    /** Ограничение сняли совсем - ноль забывается; включат снова - ноль там, куда смотрит орудие в тот момент. */
    private void limitsChanged() {
        if (!limits.limited()) {
            zero = Double.NaN;
            turned = Double.NaN;
        }
    }

    /**
     * Курс орудия от нуля по его направлению наведения aim (мир), накопительно; + - влево (против часовой сверху),
     * как поворот вокруг «верха» основания. Ноль задаётся при первом вызове.
     */
    public double trackYaw(Vec3 aim) {
        if (level == null) return 0;
        Vec3 l = SableCompat.dirToLocal(SableCompat.containing(level, worldPosition), aim);
        if (l.x * l.x + l.z * l.z < 1e-8) return Double.isNaN(turned) ? 0 : turned;      // смотрит отвесно - курс не определён
        double h = Math.toDegrees(Math.atan2(l.x, l.z));
        if (Double.isNaN(zero)) {
            zero = h;
            turned = 0;
            setChanged();
        }
        double t = Double.isNaN(turned) ? Aim.wrap180(h - zero) : AngleLimit.unwrap(turned, h - zero);
        if (t != turned) {
            turned = t;
            setChanged();
        }
        return t;
    }

    /** Курс направления dir от нуля (без накопления: для проверки, достижимо ли оно), + влево. */
    public double yawOf(Vec3 dir) {
        if (level == null || Double.isNaN(zero)) return Double.NaN;
        Vec3 l = SableCompat.dirToLocal(SableCompat.containing(level, worldPosition), dir);
        if (l.x * l.x + l.z * l.z < 1e-8) return Double.NaN;
        return Aim.wrap180(Math.toDegrees(Math.atan2(l.x, l.z)) - zero);
    }

    public double turned() {
        return turned;
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
        if (limits.limited()) tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.angle_limit.goggles",
                limits.formatValue()).withStyle(ChatFormatting.GRAY)));
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
            if (!Double.isNaN(zero)) tag.putDouble("YawZero", zero);
            if (!Double.isNaN(turned)) tag.putDouble("Turned", turned);
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
        if (!clientPacket) {
            zero = tag.contains("YawZero") ? tag.getDouble("YawZero") : Double.NaN;
            turned = tag.contains("Turned") ? tag.getDouble("Turned") : Double.NaN;
        }
    }
}
