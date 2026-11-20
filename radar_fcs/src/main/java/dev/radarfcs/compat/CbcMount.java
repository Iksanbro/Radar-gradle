package dev.radarfcs.compat;

import dev.radarfcs.FcsConfig;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountInterfaceBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.ItemCannon;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * Станок Create Big Cannons. Работает с любыми пушками на этом станке, включая CBC Advanced Technologies
 * (их контрапшены наследуют AbstractMountedCannonContraption), и компактными станками CBC: Firepower Components
 * (`FpcMount`, только при загруженном моде).
 *
 * Конвенции CBC (проверено по исходникам): cannonYaw совпадает с yaw Minecraft (0 = юг, 90 = запад),
 * cannonPitch > 0 поднимает ствол для любой стороны установки. Фактическое направление ствола
 * берётся из вращения контрапшена - тем же способом CBC считает направление выстрела.
 */
public final class CbcMount {
    public enum Kind {
        /** Большая пушка: выстрел по фронту сигнала. */
        BIG,
        /** Скорострельная (автопушки, ракетные установки): огонь, пока есть сигнал; мощность = темп. */
        RAPID,
        /** Минометы и прочее, что нельзя стрелять с контроллера. */
        UNSUPPORTED
    }

    /**
     * Что нужно от станка: у CBC и у компактных станков CBC: Firepower Components одинаковые смысл углов и сигналов,
     * но это разные классы без общего предка (кроме интерфейса контроллера CBC).
     */
    interface Ops {
        @Nullable PitchOrientedContraptionEntity contraption();
        boolean running();
        /** Текущие углы станка (cannonYaw, cannonPitch). */
        double yaw();
        double pitch();
        /** Записать углы станка и сразу перенести их на контрапшен; true - контрапшен повёрнут самим станком. */
        boolean setAngles(float yaw, float pitch);
        void setDriveLock(double limit);
        void redstone(boolean asm, boolean prevAsm, boolean fire, boolean prevFire, int power);
        void disassemble();
        @Nullable AssemblyException lastException();
        BooleanProperty firePowered();
        BooleanProperty assemblyPowered();
        /** Тип станка для отчётов (пусто - обычный станок CBC). */
        default String prefix() { return ""; }
    }

    /** Станок Create Big Cannons. */
    private record CbcOps(CannonMountBlockEntity be) implements Ops {
        public PitchOrientedContraptionEntity contraption() { return be.getContraption(); }
        public boolean running() { return be.isRunning(); }
        public double yaw() { return be.getYawOffset(0.0F); }
        public double pitch() { return be.getDisplayPitch(); }
        public boolean setAngles(float yaw, float pitch) {
            be.setYaw(yaw);
            be.setPitch(pitch);
            return false;
        }
        public void setDriveLock(double limit) {
            if (be.getYawInterface() instanceof CannonMountInterfaceBlockEntity y) y.setSequencedAngleLimit(limit);
            if (be.getPitchInterface() instanceof CannonMountInterfaceBlockEntity p) p.setSequencedAngleLimit(limit);
        }
        public void redstone(boolean asm, boolean prevAsm, boolean fire, boolean prevFire, int power) {
            be.onRedstoneUpdate(asm, prevAsm, fire, prevFire, power);
        }
        public void disassemble() { be.disassemble(); }
        public AssemblyException lastException() { return be.getLastAssemblyException(); }
        public BooleanProperty firePowered() { return CannonMountBlock.FIRE_POWERED; }
        public BooleanProperty assemblyPowered() { return CannonMountBlock.ASSEMBLY_POWERED; }
    }

    private final SmartBlockEntity be;
    private final ControlPitchContraption ctrl;
    private final Ops ops;

    private CbcMount(SmartBlockEntity be, Ops ops) {
        this.be = be;
        this.ctrl = (ControlPitchContraption) be;
        this.ops = ops;
    }

    /** Станок CBC или компактный станок CBC: Firepower Components (обычный и для автопушек), иначе null. */
    @Nullable
    public static CbcMount of(BlockEntity be) {
        if (be instanceof CannonMountBlockEntity m) return new CbcMount(m, new CbcOps(m));
        if (Mods.FIREPOWER && be instanceof SmartBlockEntity sb && be instanceof ControlPitchContraption) {
            Ops ops = FpcMount.ops(be);
            if (ops != null) return new CbcMount(sb, ops);
        }
        return null;
    }

    public BlockEntity blockEntity() { return be; }

    public BlockPos pos() { return be.getBlockPos(); }

    /**
     * Станок на месте, и его чанк загружен. Мир спрашиваем только про загруженный чанк: {@code getBlockEntity} у чанка,
     * который уже выгружается (тикеты сняты, блоки ещё не убраны), возвращал его к жизни вместе с соседями. В игре чанк
     * дальнего станка из-за этого не выгружался, пока тикает вычислитель; при остановке сервера выгрузка мира
     * зацикливалась - «Сохранение мира» без конца (вычислитель, выгружаясь, отпускал станок из соседнего чанка).
     */
    public boolean isValid() {
        Level l = be.getLevel();
        if (be.isRemoved() || l == null) return false;
        net.minecraft.core.BlockPos p = be.getBlockPos();
        return l.isLoaded(p) && l.getBlockEntity(p) == be;
    }

    @Nullable
    public PitchOrientedContraptionEntity entity() {
        PitchOrientedContraptionEntity e = ops.contraption();
        return e != null && e.isAlive() ? e : null;
    }

    @Nullable
    public AbstractMountedCannonContraption cannon() {
        PitchOrientedContraptionEntity e = entity();
        return e != null && e.getContraption() instanceof AbstractMountedCannonContraption c ? c : null;
    }

    public boolean isAssembled() {
        return ops.running() && cannon() != null;
    }

    public Kind kind() {
        AbstractMountedCannonContraption c = cannon();
        if (c == null) return Kind.UNSUPPORTED;
        if (c instanceof MountedBigCannonContraption big) return big.isDropMortar() ? Kind.UNSUPPORTED : Kind.BIG;
        // средняя пушка Neo Warfare заряжается предметом, но стреляет по фронту сигнала, как большая
        if (NeoWarfare.isMedium(c)) return Kind.BIG;
        if (c instanceof MountedAutocannonContraption || c instanceof ItemCannon) return Kind.RAPID;
        if (Reflect.nameMatches(c.getClass(), FcsConfig.RAPID_CLASS_HINTS.get())) return Kind.RAPID;
        return Kind.BIG;
    }

    /** Короткое имя типа пушки для отчётов. */
    public String typeName() {
        AbstractMountedCannonContraption c = cannon();
        if (c == null) return "-";
        String n = c.getClass().getSimpleName().replace("Mounted", "").replace("Contraption", "");
        if (n.isEmpty()) n = c.getClass().getSimpleName();
        return ops.prefix() + n;
    }

    public boolean canControl() {
        AbstractMountedCannonContraption c = cannon();
        return c != null && c.canBeTurnedByController(ctrl) && c.canBeFiredOnController(ctrl);
    }

    public double maxElevation() {
        PitchOrientedContraptionEntity e = entity();
        return e == null ? 0 : e.maximumElevation();
    }

    public double maxDepression() {
        PitchOrientedContraptionEntity e = entity();
        return e == null ? 0 : e.maximumDepression();
    }

    /** Текущий yaw станка (cannonYaw). */
    public double yaw() { return ops.yaw(); }

    /**
     * «Ноль» курса станка - куда смотрела пушка при сборке: {@code initialOrientation} собранной пушки (так считают
     * нейтраль и CBC, и компактные станки FPC; ствол бывает собран и против HORIZONTAL_FACING станка - у зеркально
     * поставленных станков), без пушки - HORIZONTAL_FACING; NaN - не определить.
     */
    public double baseYaw() {
        AbstractMountedCannonContraption c = cannon();
        if (c != null) {
            Direction o = c.initialOrientation();
            if (o != null && o.getAxis().isHorizontal()) return o.toYRot();
        }
        BlockState s = be.getBlockState();
        if (!s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) return Double.NaN;
        return s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING).toYRot();
    }

    /** Текущий pitch станка (cannonPitch). */
    public double pitch() { return ops.pitch(); }

    /**
     * Блокировка валов: пока СУО управляет, вращение от валов не действует
     * (тот же механизм, что у CC:CBC и у CBC sequenced gearshift).
     */
    public void setDriveLock(boolean locked) {
        ops.setDriveLock(locked ? 0.0 : -1.0);
    }

    /** Скорость поворота от валов, градусов/тик (по формуле CBC). */
    /**
     * Углы станка. Сразу переносятся и на контрапшен (как это делает станок в своём тике): тогда задержка
     * от команды до выстрела не зависит от порядка тиков блоков - автопушка следующим тиком стреляет
     * по этим углам, большая пушка (выстрел сразу по сигналу) - по углам прошлой команды.
     */
    public void setAngles(double yaw, double pitch) {
        double lo = -maxDepression(), hi = maxElevation();
        float y = (float) yaw, p = (float) Math.max(lo, Math.min(hi, pitch));
        if (ops.setAngles(y, p)) return;
        PitchOrientedContraptionEntity e = entity();
        AbstractMountedCannonContraption c = cannon();
        if (e != null && c != null && c.canBeTurnedByController(ctrl)) {
            Direction dir = e.getInitialOrientation();
            boolean flag = (dir.getAxisDirection() == Direction.AxisDirection.POSITIVE) == (dir.getAxis() == Direction.Axis.X);
            e.pitch = p * (flag ? 1.0F : -1.0F);
            e.yaw = y;
        }
    }

    public void sync() {
        be.setChanged();
        be.sendData();
    }

    /** Направление ствола в координатах станка (для станка на корабле - в плот-координатах). */
    @Nullable
    public Vec3 localBarrelDirection() {
        return localBarrelDirection(1.0F);
    }

    /**
     * Направление ствола. partialTicks = 0 - положение на начало тика: по нему CBC считает направление выстрела
     * (toGlobalVector(..., 0)). Большая пушка стреляет сразу по сигналу - значит, по положению на начало тика;
     * автопушка стреляет в тике контрапшена следующего тика - по текущему положению (partialTicks = 1).
     */
    @Nullable
    public Vec3 localBarrelDirection(float partialTicks) {
        PitchOrientedContraptionEntity e = entity();
        AbstractMountedCannonContraption c = cannon();
        if (e == null || c == null) return null;
        Direction o = c.initialOrientation();
        Vec3 d = e.applyRotation(new Vec3(o.getStepX(), o.getStepY(), o.getStepZ()), partialTicks);
        double l = d.length();
        return l < 1e-6 ? null : d.scale(1 / l);
    }

    /** Ось вращения пушки (центр блока-основания контрапшена) в координатах станка. */
    public Vec3 localPivot() {
        PitchOrientedContraptionEntity e = entity();
        if (e == null) return Vec3.atCenterOf(be.getBlockPos()).add(0, 2, 0);
        return e.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 1.0F);
    }

    /**
     * Расстояние от оси вращения до точки появления снаряда вдоль ствола.
     * CBC создаёт снаряд у дульного среза: у больших пушек - в центре последнего блока ствола,
     * у автопушек (и средних пушек Neo Warfare) - на полблока дальше, у роторных пушек Neo Warfare - на 2.75 дальше.
     */
    public double muzzleDistance() {
        AbstractMountedCannonContraption c = cannon();
        if (c == null) return 1;
        Direction o = c.initialOrientation();
        int front = 0;
        for (BlockPos p : c.getBlocks().keySet()) {
            int along = p.getX() * o.getStepX() + p.getY() * o.getStepY() + p.getZ() * o.getStepZ();
            if (along > front) front = along;
        }
        double ahead = NeoWarfare.isRotary(c) ? 2.75 : NeoWarfare.isMedium(c) || kind() == Kind.RAPID ? 0.5 : 0.0;
        return Math.max(0.5, front + ahead);
    }

    /** Собрать пушку так же, как редстоун-сигнал сборки, и сразу вернуть состояние сигнала. */
    public boolean assemble() {
        if (ops.running()) return true;
        Level level = be.getLevel();
        if (level == null) return false;
        BlockState state = be.getBlockState();
        boolean fire = state.hasProperty(ops.firePowered()) && state.getValue(ops.firePowered());
        ops.redstone(true, false, fire, fire, fire ? 15 : 0);
        BlockState now = be.getBlockState();
        if (now.hasProperty(ops.assemblyPowered()) && now.getValue(ops.assemblyPowered())) {
            // без настоящего редстоуна следующее обновление соседей разобрало бы пушку
            level.setBlock(be.getBlockPos(), now.setValue(ops.assemblyPowered(), false), 3);
        }
        return ops.running();
    }

    /** Разобрать пушку, как снятие сигнала сборки (ствол встаёт в исходное положение и становится блоками). */
    public void disassemble() {
        if (!ops.running() && ops.contraption() == null) return;
        ops.disassemble();
        be.sendData();
    }

    /** Почему CBC не смог собрать пушку (для отчёта), или null. */
    @Nullable
    public String assemblyError() {
        var ex = ops.lastException();
        return ex == null ? null : ex.component.getString();
    }

    public boolean firePowered() {
        BlockState s = be.getBlockState();
        return s.hasProperty(ops.firePowered()) && s.getValue(ops.firePowered());
    }

    /**
     * Сигнал огня, как редстоун на стороне огня станка.
     * Большая пушка стреляет на фронте (off -> on); у автопушек мощность сигнала задаёт темп (15 = максимум).
     */
    public void setFire(boolean on, int power) {
        BlockState state = be.getBlockState();
        if (!state.hasProperty(ops.firePowered()) || !state.hasProperty(ops.assemblyPowered())) return;
        boolean asm = state.getValue(ops.assemblyPowered());
        boolean prev = state.getValue(ops.firePowered());
        ops.redstone(asm, asm, on, prev, on ? Math.max(1, Math.min(15, power)) : 0);
    }

    /** Рядом с этим станком стоят автоконтроллеры Create Radars - пушка уже управляется ими. */
    public boolean hasRadarControllers() {
        Level level = be.getLevel();
        if (level == null) return false;
        for (Direction d : Direction.values()) {
            net.minecraft.core.BlockPos p = be.getBlockPos().relative(d);
            if (!level.isLoaded(p)) continue;                          // сосед за краем загруженного - не грузить его проверкой
            BlockState s = level.getBlockState(p);
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
            if ("create_radar".equals(id.getNamespace()) && id.getPath().contains("controller")
                    && !id.getPath().contains("network")) return true;
        }
        return false;
    }

    /** Отпечаток содержимого ствола: меняется при перезарядке другим боеприпасом. */
    public String contentsFingerprint() {
        AbstractMountedCannonContraption c = cannon();
        if (c == null) return "";
        StringBuilder sb = new StringBuilder(c.getClass().getSimpleName());
        Map<BlockPos, StructureBlockInfo> blocks = c.getBlocks();
        sb.append(':').append(blocks.size());
        return sb.toString();
    }
}
