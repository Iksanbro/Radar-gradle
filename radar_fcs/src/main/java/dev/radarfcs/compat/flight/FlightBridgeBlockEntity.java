package dev.radarfcs.compat.flight;

import ace.flight.api.WrenchContributor;
import ace.flight.block.ForwardIndicatorLinkTarget;
import ace.flight.control.BodyForwardFacing;
import ace.flight.physics.AttitudeTargetUtil;
import ace.flight.physics.FlightPhysicsController;
import ace.flight.physics.SableVelocitySemantics;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.core.Vec;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * Мост Flight Control: участник компьютера управления полётом (как стабилизаторы Flight Control - связывается с ним
 * инструментальным пистолетом) и цель индикатора направления вперёд. Каждый шаг физики отдаёт компьютеру момент
 * (поворот корпуса к цели по включённым осям: рысканье, крен, тангаж) и силу (дальность до цели, высота над целью) в осях корабля; компьютер раздаёт их
 * двигателям. Класс грузится только при установленном Create: Flight Control.
 */
public class FlightBridgeBlockEntity extends AutopilotBlockEntity implements WrenchContributor, ForwardIndicatorLinkTarget,
        BlockEntitySubLevelActor {
    @Nullable private BlockPos computerOffset;
    @Nullable private BlockPos indicatorOffset;
    @Nullable private BodyForwardFacing forward;
    private long lastError;
    /** Высота, которую держит режим «только дальность»: на ней взяли цель holdFor. */
    @Nullable private volatile String holdFor;
    private volatile double holdY = Double.NaN;

    public FlightBridgeBlockEntity(BlockPos pos, BlockState state) {
        super(FlightBridge.BE.get(), pos, state);
    }

    // ------------------------------------------------------------- Flight Control: компьютер

    @Override
    public void setLinkedComputer(BlockPos contributorPos, BlockPos computerPos) {
        computerOffset = computerPos.subtract(contributorPos);
        changed();
    }

    @Override
    public void clearLinkedComputer() {
        computerOffset = null;
        changed();
    }

    @Override
    public boolean isLinkedTo(BlockPos computerPos) {
        return computerOffset != null && worldPosition.offset(computerOffset).equals(computerPos);
    }

    @Nullable
    @Override
    public BlockPos getLinkedComputerPos() {
        return computerOffset == null ? null : worldPosition.offset(computerOffset);
    }

    @Override
    public boolean isContributing() {
        Goal g = goal();
        return g != null && !g.direct() && (g.turns() || g.mode() != Autopilot.Mode.OFF);
    }

    @Override
    public boolean computerLinked() {
        return computerOffset != null;
    }

    // ------------------------------------------------------------- Flight Control: индикатор направления

    @Nullable
    @Override
    public BlockPos getForwardIndicatorOffset() {
        return indicatorOffset;
    }

    @Override
    public void setForwardIndicatorOffset(@Nullable BlockPos offset) {
        indicatorOffset = offset;
    }

    @Nullable
    @Override
    public BodyForwardFacing getBodyForwardFacing() {
        return forwardFacing();
    }

    @Override
    public boolean applyForwardIndicatorFacing(BodyForwardFacing facing) {
        BodyForwardFacing f = facing != null ? facing : BodyForwardFacing.NORTH;
        if (f == forward) return false;
        forward = f;
        return true;
    }

    @Override
    public void onForwardIndicatorLinkChanged() {
        changed();
    }

    @Override
    public boolean indicatorLinked() {
        return indicatorOffset != null;
    }

    /** «Вперёд» корабля в его осях: по индикатору направления, без него - лицевая сторона моста. */
    public BodyForwardFacing forwardFacing() {
        if (indicatorOffset != null && forward != null) return forward;
        Direction d = getBlockState().hasProperty(HorizontalDirectionalBlock.FACING)
                ? getBlockState().getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
        return BodyForwardFacing.fromDirection(d);
    }

    private void changed() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ------------------------------------------------------------- шаг физики

    @Override
    public void contributeWrench(Object subLevel, RigidBodyHandle handle, double dt, double[] forceOut, double[] torqueOut) {
        Goal g = goal();
        if (g == null) {
            holdFor = null;
            return;
        }
        if (!g.direct()) wrench(g, subLevel, handle, dt, forceOut, torqueOut, false);
    }

    /** Прямое управление: Sable зовёт это у блоков постройки на каждом шаге физики - мост сам толкает корабль. */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double dt) {
        Goal g = goal();
        if (g == null) {
            holdFor = null;
            return;
        }
        if (!g.direct() || handle == null || !(dt > 1e-9)) return;
        double[] f = new double[3], t = new double[3];
        if (wrench(g, subLevel, handle, dt, f, t, true)) {
            handle.applyLinearAndAngularImpulse(new Vector3d(f[0] * dt, f[1] * dt, f[2] * dt), new Vector3d(t[0] * dt, t[1] * dt, t[2] * dt), true);
        }
    }

    /**
     * Сила и момент моста в осях корабля: поворот корпуса по включённым осям, дальность и высота (в режиме «только
     * дальность» - высота, на которой взяли эту цель). gravity - ещё и держать вес корабля (прямое управление; компьютер
     * управления полётом учитывает тяжесть сам).
     */
    private boolean wrench(Goal g, Object subLevel, RigidBodyHandle handle, double dt, double[] forceOut, double[] torqueOut, boolean gravity) {
        if (level == null || handle == null) return false;
        try {
            Vector3d pos = AttitudeTargetUtil.getLogicalPosition(subLevel);
            Vec ship = new Vec(pos.x, pos.y, pos.z);
            Vec target = g.posAt(level.getGameTime());
            if (!g.id().equals(holdFor)) {                                 // новая цель - запомнить высоту
                holdFor = g.id();
                holdY = pos.y;
            }
            if (g.turns()) {
                BodyForwardFacing f = forwardFacing();
                Vector3d fw = AttitudeTargetUtil.rotateBodyToWorld(subLevel, new Vector3d(f.direction().getStepX(), 0, f.direction().getStepZ()));
                Vector3d up = AttitudeTargetUtil.rotateBodyToWorld(subLevel, new Vector3d(0, 1, 0));
                Vector3d w = handle.getAngularVelocity(new Vector3d());
                Vec a = Autopilot.attitudeAccel(new Vec(fw.x, fw.y, fw.z), new Vec(up.x, up.y, up.z), new Vec(w.x, w.y, w.z),
                        target.sub(ship), g.yaw(), g.pitch(), g.roll(), g.maxPitch(), g.maxRoll());
                Vector3d alpha = new Vector3d(a.x(), a.y(), a.z());
                AttitudeTargetUtil.worldToBody(subLevel, alpha);
                Matrix3d inertia = FlightPhysicsController.getInertiaTensor(subLevel);
                Vector3d torque = inertia.transform(alpha, new Vector3d());
                if (torque.isFinite()) {
                    torqueOut[0] += torque.x;
                    torqueOut[1] += torque.y;
                    torqueOut[2] += torque.z;
                }
            }
            double mass = FlightPhysicsController.getMass(subLevel);
            Vector3d force = new Vector3d();
            if (g.mode() != Autopilot.Mode.OFF) {
                Vector3d v = SableVelocitySemantics.controlLinearVelocityWorld(subLevel, handle, dt);
                Vec a = Autopilot.accel(g.mode(), g.distance(), g.height(), g.maxSpeed(), ship, new Vec(v.x, v.y, v.z), target, g.vel(),
                        g.mode() == Autopilot.Mode.DISTANCE ? holdY : Double.NaN);
                force.add(a.x() * mass, a.y() * mass, a.z() * mass);
            }
            if (gravity) force.sub(FlightPhysicsController.getGravityWorld(subLevel, 9.81).mul(mass));
            AttitudeTargetUtil.worldToBody(subLevel, force);
            if (force.isFinite()) {
                forceOut[0] += force.x;
                forceOut[1] += force.y;
                forceOut[2] += force.z;
            }
            return true;
        } catch (Throwable e) {
            // API Flight Control или Sable изменилось - мост молчит (предупреждение раз в минуту)
            long now = System.currentTimeMillis();
            if (now - lastError > 60_000) {
                lastError = now;
                FcsMod.LOG.warn("[radar_fcs] flight bridge at {}: {}", worldPosition, e.toString());
            }
            return false;
        }
    }

    // ------------------------------------------------------------- сохранение

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeLinks(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        computerOffset = tag.contains("ComputerOffset") ? BlockPos.of(tag.getLong("ComputerOffset")) : null;
        loadForwardIndicatorLink(tag);
        forward = tag.contains("Forward") ? BodyForwardFacing.fromSerializedName(tag.getString("Forward")) : null;
    }

    @Override
    protected void writeClientExtra(CompoundTag t) {
        writeLinks(t);
    }

    private void writeLinks(CompoundTag tag) {
        if (computerOffset != null) tag.putLong("ComputerOffset", computerOffset.asLong());
        saveForwardIndicatorLink(tag);
        if (forward != null) tag.putString("Forward", forward.serializedName());
    }
}
