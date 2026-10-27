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
 * (их контрапшены наследуют AbstractMountedCannonContraption).
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

    private final CannonMountBlockEntity be;

    private CbcMount(CannonMountBlockEntity be) {
        this.be = be;
    }

    @Nullable
    public static CbcMount of(BlockEntity be) {
        return be instanceof CannonMountBlockEntity m ? new CbcMount(m) : null;
    }

    public CannonMountBlockEntity blockEntity() { return be; }

    public BlockPos pos() { return be.getBlockPos(); }

    public boolean isValid() {
        Level l = be.getLevel();
        return !be.isRemoved() && l != null && l.getBlockEntity(be.getBlockPos()) == be;
    }

    @Nullable
    public PitchOrientedContraptionEntity entity() {
        PitchOrientedContraptionEntity e = be.getContraption();
        return e != null && e.isAlive() ? e : null;
    }

    @Nullable
    public AbstractMountedCannonContraption cannon() {
        PitchOrientedContraptionEntity e = entity();
        return e != null && e.getContraption() instanceof AbstractMountedCannonContraption c ? c : null;
    }

    public boolean isAssembled() {
        return be.isRunning() && cannon() != null;
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
        return n.isEmpty() ? c.getClass().getSimpleName() : n;
    }

    public boolean canControl() {
        AbstractMountedCannonContraption c = cannon();
        return c != null && c.canBeTurnedByController(be) && c.canBeFiredOnController(be);
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
    public double yaw() { return be.getYawOffset(0.0F); }

    /** Текущий pitch станка (cannonPitch). */
    public double pitch() { return be.getDisplayPitch(); }

    /**
     * Блокировка валов: пока СУО управляет, вращение от валов не действует
     * (тот же механизм, что у CC:CBC и у CBC sequenced gearshift).
     */
    public void setDriveLock(boolean locked) {
        double limit = locked ? 0.0 : -1.0;
        if (be.getYawInterface() instanceof CannonMountInterfaceBlockEntity y) y.setSequencedAngleLimit(limit);
        if (be.getPitchInterface() instanceof CannonMountInterfaceBlockEntity p) p.setSequencedAngleLimit(limit);
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
        be.setYaw(y);
        be.setPitch(p);
        PitchOrientedContraptionEntity e = entity();
        AbstractMountedCannonContraption c = cannon();
        if (e != null && c != null && c.canBeTurnedByController(be)) {
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
        if (be.isRunning()) return true;
        Level level = be.getLevel();
        if (level == null) return false;
        BlockState state = be.getBlockState();
        boolean fire = state.getValue(CannonMountBlock.FIRE_POWERED);
        be.onRedstoneUpdate(true, false, fire, fire, fire ? 15 : 0);
        BlockState now = be.getBlockState();
        if (now.hasProperty(CannonMountBlock.ASSEMBLY_POWERED) && now.getValue(CannonMountBlock.ASSEMBLY_POWERED)) {
            // без настоящего редстоуна следующее обновление соседей разобрало бы пушку
            level.setBlock(be.getBlockPos(), now.setValue(CannonMountBlock.ASSEMBLY_POWERED, false), 3);
        }
        return be.isRunning();
    }

    /** Почему CBC не смог собрать пушку (для отчёта), или null. */
    @Nullable
    public String assemblyError() {
        var ex = be.getLastAssemblyException();
        return ex == null ? null : ex.component.getString();
    }

    public boolean firePowered() {
        BlockState s = be.getBlockState();
        return s.hasProperty(CannonMountBlock.FIRE_POWERED) && s.getValue(CannonMountBlock.FIRE_POWERED);
    }

    /**
     * Сигнал огня, как редстоун на стороне огня станка.
     * Большая пушка стреляет на фронте (off -> on); у автопушек мощность сигнала задаёт темп (15 = максимум).
     */
    public void setFire(boolean on, int power) {
        BlockState state = be.getBlockState();
        if (!state.hasProperty(CannonMountBlock.FIRE_POWERED)) return;
        boolean asm = state.getValue(CannonMountBlock.ASSEMBLY_POWERED);
        boolean prev = state.getValue(CannonMountBlock.FIRE_POWERED);
        be.onRedstoneUpdate(asm, asm, on, prev, on ? Math.max(1, Math.min(15, power)) : 0);
    }

    /** Рядом с этим станком стоят автоконтроллеры Create Radars - пушка уже управляется ими. */
    public boolean hasRadarControllers() {
        Level level = be.getLevel();
        if (level == null) return false;
        for (Direction d : Direction.values()) {
            BlockState s = level.getBlockState(be.getBlockPos().relative(d));
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
