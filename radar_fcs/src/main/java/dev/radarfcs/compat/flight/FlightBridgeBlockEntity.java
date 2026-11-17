package dev.radarfcs.compat.flight;

import ace.flight.api.WrenchContributor;
import ace.flight.block.FlightControlComputerBlockEntity;
import ace.flight.block.ForwardIndicatorLinkTarget;
import ace.flight.control.BodyForwardFacing;
import ace.flight.navigation.NavigationControlCommand;
import ace.flight.navigation.NavigationControlDomain;
import ace.flight.navigation.NavigationControlNetworkSnapshot;
import ace.flight.navigation.NavigationControllerKind;
import ace.flight.navigation.NavigationControllerSnapshot;
import ace.flight.navigation.NavigationLeaseBroker;
import ace.flight.navigation.NavigationLeaseIdentity;
import ace.flight.navigation.NavigationVehicleStateSnapshot;
import ace.flight.physics.AttitudeTargetUtil;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.core.Vec;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * Мост Flight Control: связывается с компьютером управления полётом инструментальным пистолетом (как его регуляторы) и
 * с индикатором направления вперёд. Сам силу не отдаёт - как навигационный терминал Flight Control берёт у компьютера
 * аренду регулятора скорости (движение к цели, дальность, высота) и регулятора угловой скорости (рысканье, крен,
 * тангаж) и каждый тик задаёт им цели; спорящие регуляторы на это время выключены. Класс грузится только при
 * установленном Create: Flight Control.
 */
public class FlightBridgeBlockEntity extends AutopilotBlockEntity implements WrenchContributor, ForwardIndicatorLinkTarget {
    private static Field brokerField;
    @Nullable private BlockPos computerOffset;
    @Nullable private BlockPos indicatorOffset;
    @Nullable private BodyForwardFacing forward;
    private long lastError;
    /** Высота, которую держит режим «только дальность»: на ней взяли цель holdFor. */
    @Nullable private String holdFor;
    private double holdY = Double.NaN;
    // аренда регуляторов у компьютера управления полётом
    @Nullable private NavigationLeaseIdentity identity;
    @Nullable private NavigationLeaseBroker.LeaseToken token;
    private Set<NavigationControlDomain> leased = Set.of();
    private long revision, epoch;
    @Nullable private FlightControlComputerBlockEntity leaseComputer;
    private State driveState = State.NO_TARGET;

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
        return false;                                                       // силу и момент дают регуляторы скорости
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

    // ------------------------------------------------------------- управление через навигацию Flight Control

    /** Сам мост силу не отдаёт: корабль ведут регуляторы скорости и угловой скорости, которым мост задаёт цели. */
    @Override
    public void contributeWrench(Object subLevel, RigidBodyHandle handle, double dt, double[] forceOut, double[] torqueOut) {}

    @Override
    public void setRemoved() {
        release();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        release();
        super.onChunkUnloaded();
    }

    @Override
    protected State driveState() {
        return driveState;
    }

    @Override
    protected void drive(ServerLevel level, @Nullable Goal g) {
        FlightControlComputerBlockEntity cc = computer(level);
        if (g == null || cc == null) {
            release();
            holdFor = null;
            return;
        }
        try {
            driveState = steer(level, cc, g);
        } catch (Throwable e) {
            release();
            driveState = State.COMPUTER_OFF;
            long now = System.currentTimeMillis();
            if (now - lastError > 60_000) {                              // API Flight Control изменилось - мост молчит
                lastError = now;
                FcsMod.LOG.warn("[radar_fcs] flight bridge at {}: {}", worldPosition, e.toString());
            }
        }
    }

    @Nullable
    private FlightControlComputerBlockEntity computer(ServerLevel level) {
        BlockPos p = getLinkedComputerPos();
        if (p == null || !level.isLoaded(p)) return null;
        return level.getBlockEntity(p) instanceof FlightControlComputerBlockEntity cc && cc.hasReciprocalControllerAt(worldPosition) ? cc : null;
    }

    /**
     * Как навигационный терминал Flight Control: аренда у компьютера осей регулятора скорости (движение) и регулятора
     * угловой скорости (повороты) с их целями; остальные регуляторы, которые спорили бы с ними (стабилизаторы ориентации
     * и высоты, регулятор дистанции, терминал мыши, лишние регуляторы скорости), на время аренды выключены.
     */
    private State steer(ServerLevel level, FlightControlComputerBlockEntity cc, Goal g) throws Exception {
        NavigationControlNetworkSnapshot net = cc.snapshotControlNetwork(worldPosition, level.getGameTime());
        NavigationVehicleStateSnapshot vehicle = cc.getNavigationVehicleStateSnapshot();
        if (net.status() != NavigationControlNetworkSnapshot.Status.AVAILABLE || !net.computerWorking() || net.computerMode() != 0
                || net.dimensionId() == null || vehicle == null || vehicle.structureId() == null) {
            release();
            return State.COMPUTER_OFF;
        }
        boolean move = g.mode() != Autopilot.Mode.OFF, turn = g.turns();
        NavigationControllerSnapshot vel = null, ang = null;
        for (NavigationControllerSnapshot n : net.controllers()) {
            if (!usable(n)) continue;
            if (vel == null && n.kind() == NavigationControllerKind.BODY_VELOCITY) vel = n;
            if (ang == null && n.kind() == NavigationControllerKind.BODY_ANGULAR_VELOCITY) ang = n;
        }
        boolean needVel = move && vel == null, needAng = turn && ang == null;
        if (!move) vel = null;
        if (!turn) ang = null;
        if (vel == null && ang == null) {
            release();
            return needVel && needAng ? State.NEED_COUPLERS : needVel ? State.NEED_VELOCITY : needAng ? State.NEED_ANGULAR : State.ACTIVE;
        }
        // положение корабля, нос и «верх» в мире
        Object sub = AttitudeTargetUtil.getContainingSubLevel(level, worldPosition, this);
        Vector3d p = AttitudeTargetUtil.getLogicalPosition(sub);
        Vec ship = new Vec(p.x, p.y, p.z);
        Vec target = g.posAt(level.getGameTime());
        if (!g.id().equals(holdFor)) {                                     // новая цель - запомнить высоту
            holdFor = g.id();
            holdY = p.y;
        }
        Map<NavigationControlDomain, NavigationControlCommand> cmds = new HashMap<>();
        if (vel != null) {
            Vec v = Autopilot.desiredVelocity(g.mode(), g.distance(), g.height(), g.maxSpeed(), ship, target, g.vel(),
                    g.mode() == Autopilot.Mode.DISTANCE ? holdY : Double.NaN);
            Vector3d vb = new Vector3d(v.x(), v.y(), v.z());
            AttitudeTargetUtil.worldToBody(sub, vb);
            track(cmds, vel, vb, NavigationControlDomain.SemanticAxis.BODY_VELOCITY_X, NavigationControlDomain.SemanticAxis.BODY_VELOCITY_Y,
                    NavigationControlDomain.SemanticAxis.BODY_VELOCITY_Z);
        }
        if (ang != null) {
            BodyForwardFacing f = forwardFacing();
            Vector3d fw = AttitudeTargetUtil.rotateBodyToWorld(sub, new Vector3d(f.direction().getStepX(), 0, f.direction().getStepZ()));
            Vector3d up = AttitudeTargetUtil.rotateBodyToWorld(sub, new Vector3d(0, 1, 0));
            Vec w = Autopilot.desiredRates(new Vec(fw.x, fw.y, fw.z), new Vec(up.x, up.y, up.z), target.sub(ship),
                    g.yaw(), g.pitch(), g.roll(), g.maxPitch(), g.maxRoll());
            Vector3d wb = new Vector3d(w.x(), w.y(), w.z());
            AttitudeTargetUtil.worldToBody(sub, wb);
            track(cmds, ang, wb, NavigationControlDomain.SemanticAxis.BODY_ANGULAR_VELOCITY_X,
                    NavigationControlDomain.SemanticAxis.BODY_ANGULAR_VELOCITY_Y, NavigationControlDomain.SemanticAxis.BODY_ANGULAR_VELOCITY_Z);
        }
        // кто спорил бы с регуляторами моста - выключить на время аренды
        for (NavigationControllerSnapshot n : net.controllers()) {
            if (n == vel || n == ang || !usable(n)) continue;
            boolean fights = vel != null && (n.kind() == NavigationControllerKind.ALTITUDE_HOLD || n.kind() == NavigationControllerKind.DISTANCE
                    || n.kind() == NavigationControllerKind.BODY_VELOCITY)
                    || ang != null && (n.kind() == NavigationControllerKind.ATTITUDE_HOLD || n.kind() == NavigationControllerKind.MOUSE_FLIGHT
                    || n.kind() == NavigationControllerKind.BODY_ANGULAR_VELOCITY);
            if (fights) cmds.put(NavigationControlDomain.participation(n.index(), n.nodeIncarnation()), NavigationControlCommand.Participation.REQUEST_OFF);
        }
        NavigationLeaseBroker broker = broker(cc);
        long now = System.nanoTime();
        Set<NavigationControlDomain> want = Set.copyOf(cmds.keySet());
        if (token != null && want.equals(leased) && net.topologyEpoch() == epoch && cc == leaseComputer) {
            NavigationLeaseBroker.Status renewed = broker.renew(identity, token, now);
            NavigationLeaseBroker.Status published = renewed == NavigationLeaseBroker.Status.RENEWED
                    ? broker.publish(identity, token, ++revision, cmds, now) : renewed;
            if (published != NavigationLeaseBroker.Status.PUBLISHED) {
                token = null;                                              // перехватили - взять заново в следующий тик
                return State.BUSY;
            }
        } else {
            release();
            identity = new NavigationLeaseIdentity(net.dimensionId(), vehicle.structureId(), worldPosition.asLong(), UUID.randomUUID());
            revision = 0;
            NavigationLeaseBroker.BundleResult r = broker.tryAcquireAndPublish(identity, net.topologyEpoch(), want, revision, cmds, now);
            if (!r.succeeded() || r.token() == null) {
                if (r.status() == NavigationLeaseBroker.Status.COMPUTER_INACTIVE) return State.COMPUTER_OFF;
                return State.BUSY;
            }
            token = r.token();
            leased = want;
            epoch = net.topologyEpoch();
            leaseComputer = cc;
        }
        return needVel ? State.NEED_VELOCITY : needAng ? State.NEED_ANGULAR : State.ACTIVE;
    }

    /** Регулятор связан с компьютером, загружен, умеет участвовать в навигации и не занят редстоун-прокси. */
    private static boolean usable(NavigationControllerSnapshot n) {
        return n.loaded() && n.linkedToComputer() && n.nodeIncarnation() != null && n.capabilities().participation()
                && !n.localAuthority().blocksParticipation();
    }

    /** Включить регулятор и задать цели по его осям (оси, которые держит редстоун-прокси, не трогать). */
    private static void track(Map<NavigationControlDomain, NavigationControlCommand> cmds, NavigationControllerSnapshot n, Vector3d target,
                              NavigationControlDomain.SemanticAxis x, NavigationControlDomain.SemanticAxis y, NavigationControlDomain.SemanticAxis z) {
        cmds.put(NavigationControlDomain.participation(n.index(), n.nodeIncarnation()), NavigationControlCommand.Participation.REQUEST_ON);
        double[] t = {target.x, target.y, target.z};
        NavigationControlDomain.SemanticAxis[] axes = {x, y, z};
        for (int k = 0; k < 3; k++) {
            if (!n.capabilities().supports(axes[k]) || n.localAuthority().blockingTargetAxes().contains(axes[k])) continue;
            cmds.put(NavigationControlDomain.axis(n.index(), n.nodeIncarnation(), axes[k]),
                    NavigationControlCommand.Axis.track(Double.isFinite(t[k]) ? t[k] : 0));
        }
    }

    /**
     * Распределитель аренды компьютера. Компьютер выдаёт аренду через свой API только навигационному терминалу, поэтому
     * мост обращается к распределителю напрямую - тем же путём, что и терминал после проверки.
     */
    public static NavigationLeaseBroker broker(FlightControlComputerBlockEntity cc) throws ReflectiveOperationException {
        if (brokerField == null) {
            Field f = FlightControlComputerBlockEntity.class.getDeclaredField("navigationLeaseBroker");
            f.setAccessible(true);
            brokerField = f;
        }
        return (NavigationLeaseBroker) brokerField.get(cc);
    }

    /** Отдать аренду: регуляторы и выключенные стабилизаторы возвращаются к своим настройкам. */
    private void release() {
        if (token != null && identity != null && leaseComputer != null && !leaseComputer.isRemoved()) {
            try {
                broker(leaseComputer).release(identity, token, System.nanoTime());
            } catch (Throwable ignored) {
                // компьютер сломан или API изменилось - аренда истечёт сама (0.75 с)
            }
        }
        token = null;
        leased = Set.of();
        leaseComputer = null;
        if (driveState == State.ACTIVE) driveState = State.NO_TARGET;
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
