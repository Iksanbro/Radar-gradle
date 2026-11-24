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
import dev.radarfcs.block.DockingControllerBlockEntity;
import dev.radarfcs.block.OrbitBeaconBlockEntity;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Docking;
import dev.radarfcs.dock.DockHold;
import dev.radarfcs.dock.DockingRegistry;
import dev.radarfcs.link.LinkRegistry;
import java.util.HashSet;
import java.util.List;
import net.minecraft.world.phys.Vec3;
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
 * аренду регулятора скорости (движение к цели, дальность, высота) и регулятора угловой скорости (рыскание, крен,
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
    // стыковка
    @Nullable private BlockPos dockTarget;
    private boolean dockReserved, wasLocked;
    private Docking.Phase dockPhase = Docking.Phase.TRANSIT;
    private double cruiseY = Double.NaN;
    private long plugKey, departUntil = Long.MIN_VALUE;
    @Nullable private Vec departDir, departTip;
    private double departClear = Docking.APPROACH_DIST;
    /** Без цели: точка, в которой корабль висит (где кончилась цель или стыковка). */
    @Nullable private Vec hold;
    private Set<UUID> dockNets = Set.of();
    private long dockNetsAt = Long.MIN_VALUE / 2;
    /** Компьютер выключен мостом на время стыковки (сохраняется - включить после расстыковки и перезапуска). */
    private boolean poweredDown, unloading;
    @Nullable private Object cachedSub;
    private long cachedSubAt;

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
        if (!unloading && level instanceof ServerLevel sl) restoreComputer(sl);
        release();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        unloading = true;
        release();
        super.onChunkUnloaded();
    }

    @Override
    protected State driveState() {
        return driveState;
    }

    @Override
    protected void drive(ServerLevel level, @Nullable Goal g, boolean dock) {
        FlightControlComputerBlockEntity cc = computer(level);
        long now = level.getGameTime();
        try {
            if (dock) {
                departUntil = Long.MIN_VALUE;
                holdFor = null;
                hold = null;
                if (cc == null) {
                    release();
                    driveState = State.COMPUTER_OFF;
                    return;
                }
                driveState = dockSteer(level, cc, now);
                return;
            }
            // пауза пристыкованного корабля - остаётся пристыкованным (пауза отпускает управление, а не расстыковывает);
            // порт стыковки держат сигналом редстоуна - расстыковка запрещена
            if (wasLocked && (paused || portHeld(level)) && holdDocked(level, now)) {
                if (!paused) driveState = State.DOCK_HELD;
                return;
            }
            endDock(level, now);
            if (now < departUntil && cc != null && departDir != null) {        // отход от порта после расстыковки
                State ds = depart(level, cc);
                if (ds != null) {
                    driveState = ds;
                    return;
                }
            }
            if (g == null && cc != null && !paused && mode != Autopilot.Mode.OFF) {
                driveState = idleSteer(level, cc);                         // цели нет - висеть или орбита
                return;
            }
            hold = null;
            if (g == null || cc == null) {
                release();
                holdFor = null;
                return;
            }
            driveState = steer(level, cc, g);
        } catch (Throwable e) {
            release();
            driveState = State.COMPUTER_OFF;
            long t = System.currentTimeMillis();
            if (t - lastError > 60_000) {                                // API Flight Control изменилось - мост молчит
                lastError = t;
                FcsMod.LOG.warn("[radar_fcs] flight bridge at {}: {}", worldPosition, e.toString());
            }
        }
    }

    /** Компьютер или индикатор сломаны - связь с ними снять (как у блоков самого Flight Control). */
    @Override
    protected void maintainLinks(ServerLevel level) {
        BlockPos c = getLinkedComputerPos();
        if (c != null && level.isLoaded(c) && !(level.getBlockEntity(c) instanceof FlightControlComputerBlockEntity)) {
            release();
            poweredDown = false;
            clearLinkedComputer();
        }
        if (indicatorOffset != null) {
            try {
                validateForwardIndicatorLink();
            } catch (Throwable ignored) {
                // API Flight Control изменилось - связь остаётся
            }
        }
    }

    @Nullable
    private FlightControlComputerBlockEntity computer(ServerLevel level) {
        BlockPos p = getLinkedComputerPos();
        if (p == null || !level.isLoaded(p)) return null;
        return level.getBlockEntity(p) instanceof FlightControlComputerBlockEntity cc && cc.hasReciprocalControllerAt(worldPosition) ? cc : null;
    }

    /** Компьютер в рабочем режиме и знает корабль: снимок сети регуляторов и корабля, иначе null. */
    private record Ready(NavigationControlNetworkSnapshot net, NavigationVehicleStateSnapshot vehicle) {}

    @Nullable
    private Ready ready(ServerLevel level, FlightControlComputerBlockEntity cc) {
        NavigationControlNetworkSnapshot net = cc.snapshotControlNetwork(worldPosition, level.getGameTime());
        NavigationVehicleStateSnapshot vehicle = cc.getNavigationVehicleStateSnapshot();
        if (net.status() != NavigationControlNetworkSnapshot.Status.AVAILABLE || !net.computerWorking() || net.computerMode() != 0
                || net.dimensionId() == null || vehicle == null || vehicle.structureId() == null) return null;
        return new Ready(net, vehicle);
    }

    /** Полёт к цели: скорость и повороты по настройкам моста. */
    private State steer(ServerLevel level, FlightControlComputerBlockEntity cc, Goal g) throws Exception {
        Ready r = ready(level, cc);
        if (r == null) {
            release();
            return State.COMPUTER_OFF;
        }
        // положение корабля, нос и «верх» в мире
        Object sub = subLevel(level);
        Vector3d p = AttitudeTargetUtil.getLogicalPosition(sub);
        Vec ship = new Vec(p.x, p.y, p.z);
        Vec target = g.posAt(level.getGameTime());
        if (!g.id().equals(holdFor)) {                                     // новая цель - запомнить высоту
            holdFor = g.id();
            holdY = p.y;
        }
        Vec v = g.mode() == Autopilot.Mode.OFF ? null : Autopilot.desiredVelocity(g.mode(), g.distance(), g.height(), g.maxSpeed(), ship,
                target, g.vel(), g.mode() == Autopilot.Mode.DISTANCE ? holdY : Double.NaN);
        Vec w = null;
        if (g.turns()) {
            BodyForwardFacing f = forwardFacing();
            Vector3d fw = AttitudeTargetUtil.rotateBodyToWorld(sub, new Vector3d(f.direction().getStepX(), 0, f.direction().getStepZ()));
            Vector3d up = AttitudeTargetUtil.rotateBodyToWorld(sub, new Vector3d(0, 1, 0));
            w = Autopilot.desiredRates(new Vec(fw.x, fw.y, fw.z), new Vec(up.x, up.y, up.z), target.sub(ship),
                    g.yaw(), g.pitch(), g.roll(), g.maxPitch(), g.maxRoll());
        }
        return lease(cc, r, sub, v, w, false);
    }

    // ------------------------------------------------------------- без цели

    /**
     * Цели нет: висеть там, где её не стало (корпус ровно, курс держится), или лететь по орбите вокруг координаты /
     * маяка орбиты сети на пределе скорости моста (нос по ходу, если включено рыскание; крен в поворот, если включён).
     */
    private State idleSteer(ServerLevel level, FlightControlComputerBlockEntity cc) throws Exception {
        Ready r = ready(level, cc);
        if (r == null) {
            release();
            return State.COMPUTER_OFF;
        }
        Object sub = subLevel(level);
        Vector3d p = AttitudeTargetUtil.getLogicalPosition(sub);
        Vec ship = new Vec(p.x, p.y, p.z);
        Vector3d up3 = AttitudeTargetUtil.rotateBodyToWorld(sub, new Vector3d(0, 1, 0));
        Vec up = new Vec(up3.x, up3.y, up3.z);
        Vec center = null, centerVel = Vec.ZERO;
        if (idle == Autopilot.Idle.ORBIT_POINT) {
            center = new Vec(orbitX, orbitY, orbitZ);
        } else if (idle == Autopilot.Idle.ORBIT_BEACON) {
            // маяк своей радарной сети: с кодом моста (без учёта регистра), без кода - первый
            for (BlockPos b : beacons) {
                if (!level.isLoaded(b) || !(level.getBlockEntity(b) instanceof OrbitBeaconBlockEntity ob)) continue;
                if (!orbitCode.isEmpty() && !orbitCode.equalsIgnoreCase(ob.code())) continue;
                Vec3 local = Vec3.atCenterOf(b);
                Vec3 w = SableCompat.toWorld(level, local);
                Vec3 v = SableCompat.pointVelocity(level, SableCompat.containing(level, b), local).scale(20);
                center = new Vec(w.x, w.y, w.z);
                centerVel = new Vec(v.x, v.y, v.z);
                break;
            }
        }
        if (center == null) {                                               // висеть
            if (hold == null) hold = ship;
            State s = lease(cc, r, sub, Docking.toward(ship, hold, maxSpeed), Docking.levelRates(up), false);
            return s != State.ACTIVE ? s : idle == Autopilot.Idle.ORBIT_BEACON ? State.IDLE_NO_BEACON : State.IDLE_HOVER;
        }
        hold = null;
        // по касательной - скорость орбиты, выход на круг и высота - не быстрее предела моста
        Vec v = Autopilot.orbitVelocity(ship, center, centerVel, orbitRadius, orbitHeight, orbitSpeed, maxSpeed, orbitClockwise);
        // нос - по настройке орбиты; крен в поворот (если включён) - только носом по ходу или против хода
        Vec dir = orbitFacing.dir(ship, center, orbitClockwise);
        Vec w;
        if (dir != null) {
            BodyForwardFacing f = forwardFacing();
            Vector3d fw = AttitudeTargetUtil.rotateBodyToWorld(sub, new Vector3d(f.direction().getStepX(), 0, f.direction().getStepZ()));
            boolean bank = roll && orbitFacing.banks();
            w = Autopilot.desiredRates(new Vec(fw.x, fw.y, fw.z), up, dir, true, false, bank, maxPitch, maxRoll);
        } else {
            w = Docking.levelRates(up);
        }
        State s = lease(cc, r, sub, v, w, false);
        return s == State.ACTIVE ? State.IDLE_ORBIT : s;
    }

    // ------------------------------------------------------------- стыковка

    /** Штекер стыковки на этом корабле (первый из сети), иначе null. */
    @Nullable
    private DockingControllerBlockEntity plug(ServerLevel level) {
        for (BlockPos p : plugs) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof DockingControllerBlockEntity d && d.role == Docking.Role.PLUG) return d;
        }
        return null;
    }

    /**
     * Стыковка: штекер корабля к порту с его номером (ближайшему свободному; занят - ждать у точки подхода или не
     * лететь). Пристыкован - все регуляторы движения и стабилизаторы выключены, корабль держит сцепка портов.
     */
    private State dockSteer(ServerLevel level, FlightControlComputerBlockEntity cc, long now) throws Exception {
        DockingControllerBlockEntity plug = plug(level);
        if (plug == null) {
            endDock(level, now);
            release();
            return State.DOCK_NO_PLUG;
        }
        Vec3 lt = plug.localTip();
        Direction f = plug.connectorFacing();
        if (lt == null || f == null) {
            endDock(level, now);
            release();
            return State.DOCK_NO_CONNECTOR;
        }
        var ship = SableCompat.containing(level, worldPosition);
        Vec3 wt = SableCompat.toWorld(level, lt);
        Vec3 wd = SableCompat.dirToWorld(ship, Vec3.atLowerCornerOf(f.getNormal()));
        Vec3 wu = SableCompat.dirToWorld(ship, new Vec3(0, 1, 0));
        Vec tip = new Vec(wt.x, wt.y, wt.z);
        if (Double.isNaN(cruiseY)) cruiseY = tip.y();
        long key = plug.key();
        plugKey = key;
        if (plug.locked()) {                                                // пристыкован: держать порты выдвинутыми
            plug.commandExtend(now);
            if (dockTarget != null) {
                DockingRegistry.reserve(level, dockTarget, key, now, true);
                dockReserved = true;
                DockingRegistry.PortInfo pi = DockingRegistry.get(level, dockTarget);
                if (pi != null) {
                    departDir = pi.facing();
                    departTip = pi.tip();
                    departClear = Math.max(pi.clearance(), plug.approach);
                    summary(pi, tip);
                }
            }
            if (dockTarget == null || departTip == null) {                  // пристыкован не нами (порт неизвестен) - ось штекера
                departDir = new Vec(-wd.x, -wd.y, -wd.z).normalize();
                departTip = tip;
                departClear = plug.approach;
            }
            wasLocked = true;
            // пристыкован: компьютер управления полётом выключен (тяга двигателей Flight Control снята), пушки корабля
            // неактивны (вычислители видят отметку DockHold)
            release();
            if (cc.isWorkingDefaultEnabled()) {
                cc.setWorking(false);
                poweredDown = true;
                setChanged();
            }
            if (ship != null) DockHold.mark(level, ship.getUniqueId(), now);
            return State.DOCKED;
        }
        restoreComputer(level);
        Ready r = ready(level, cc);
        if (r == null) {
            release();
            return State.COMPUTER_OFF;
        }
        Object sub = subLevel(level);
        // сети моста и штекера - раз в секунду (обход всех передатчиков измерения)
        if (now - dockNetsAt >= 20) {
            dockNetsAt = now;
            Set<UUID> nets = new HashSet<>(LinkRegistry.networksOf(level, worldPosition));
            nets.addAll(LinkRegistry.networksOf(level, plug.getBlockPos()));
            dockNets = Set.copyOf(nets);
        }
        Set<UUID> nets = dockNets;
        List<Vec> tips = new java.util.ArrayList<>();
        for (Vec3 t : plug.localTips()) {
            Vec3 w = SableCompat.toWorld(level, t);
            tips.add(new Vec(w.x, w.y, w.z));
        }
        DockingRegistry.Choice ch = DockingRegistry.choose(level, nets, plug.number, ship == null ? null : ship.getUniqueId(), key, tip,
                dockTarget, now, tips.size());
        if (ch == null || (!ch.free() && dockBusy == Docking.Busy.STAY)) {
            endDock(level, now);
            release();
            if (ch != null) summary(ch.port(), tip);
            return ch == null ? State.DOCK_NO_PORT : State.DOCK_PORTS_BUSY;
        }
        BlockPos target = ch.port().pos();
        if (!target.equals(dockTarget)) {                                   // другой порт - подход заново
            if (dockTarget != null && dockReserved) DockingRegistry.release(level, dockTarget, key);
            dockTarget = target;
            dockPhase = Docking.Phase.TRANSIT;
        }
        // дистанция обхода - большая из настроек порта и штекера
        Docking.Port port = ch.port().port().withClearance(Math.max(ch.port().clearance(), plug.approach));
        Docking.Command c = Docking.steer(dockPhase, port, tips, new Vec(wd.x, wd.y, wd.z), new Vec(wu.x, wu.y, wu.z),
                maxSpeed, cruiseY, ch.free());
        dockPhase = c.phase();
        boolean extend = c.phase() == Docking.Phase.FINAL;
        dockReserved = ch.free();
        if (ch.free()) DockingRegistry.reserve(level, target, key, now, extend);
        if (extend) plug.commandExtend(now);
        departDir = ch.port().facing();
        summary(ch.port(), tip);
        State s = lease(cc, r, sub, c.velocity(), c.rates(), false);
        if (s != State.ACTIVE) return s;
        if (!c.compatible()) return State.DOCK_TILT;
        // развёрнут, а порты не встают каждый на свой (другое число или другая расстановка) - предупредить
        if (!c.layoutOk() && (c.phase() == Docking.Phase.ALIGN || c.phase() == Docking.Phase.FINAL)) return State.DOCK_LAYOUT;
        return switch (c.phase()) {
            case TRANSIT, APPROACH -> State.DOCK_TRANSIT;
            case ALIGN -> ch.free() ? State.DOCK_ALIGN : State.DOCK_WAITING;
            case FINAL -> State.DOCK_FINAL;
        };
    }

    private void summary(DockingRegistry.PortInfo p, Vec tip) {
        dockName = String.valueOf(p.number());
        dockDist = p.tip().distance(tip);
    }

    /** Порт, к которому пристыкован корабль, держат сигналом редстоуна (расстыковываться нельзя). */
    private boolean portHeld(ServerLevel level) {
        DockingControllerBlockEntity plug = plug(level);
        if (plug == null) return false;
        Vec3 lt = plug.localTip();
        if (lt == null) return false;
        Vec3 wt = SableCompat.toWorld(level, lt);
        return DockingRegistry.heldDocked(level, dockTarget, new Vec(wt.x, wt.y, wt.z));
    }

    /** Держать сцепку на паузе: порты выдвинуты, порт стыковки занят, корабль отмечен; false - уже не сцеплен. */
    private boolean holdDocked(ServerLevel level, long now) {
        DockingControllerBlockEntity plug = plug(level);
        if (plug == null || !plug.locked()) return false;
        plug.commandExtend(now);
        if (dockTarget != null) DockingRegistry.reserve(level, dockTarget, plug.key(), now, true);
        UUID ship = SableCompat.containingId(level, worldPosition);
        if (ship != null) DockHold.mark(level, ship, now);
        release();
        driveState = State.DOCKED;
        return true;
    }

    /** Компьютер управления полётом выключали на время стыковки - включить снова. */
    private void restoreComputer(ServerLevel level) {
        if (!poweredDown) return;
        BlockPos p = getLinkedComputerPos();
        if (p != null && level.isLoaded(p) && level.getBlockEntity(p) instanceof FlightControlComputerBlockEntity cc) cc.setWorking(true);
        poweredDown = false;
        setChanged();
    }

    /** Стыковка кончилась: порт отпустить; был пристыкован - компьютер включить и отойти от порта. */
    private void endDock(ServerLevel level, long now) {
        dockPhase = Docking.Phase.TRANSIT;
        cruiseY = Double.NaN;
        if (!poweredDown && !wasLocked && dockTarget == null) return;    // стыковки не было - ничего не искать
        restoreComputer(level);
        UUID ship = SableCompat.containingId(level, worldPosition);
        if (wasLocked && ship != null) DockHold.release(level, ship);
        if (dockTarget != null && dockReserved) DockingRegistry.release(level, dockTarget, plugKey);
        if (wasLocked && departDir != null) departUntil = now + Docking.DEPART_TICKS;
        wasLocked = false;
        dockTarget = null;
        dockReserved = false;
        dockPhase = Docking.Phase.TRANSIT;
        cruiseY = Double.NaN;
    }

    /**
     * Отход от порта по его оси, корпус ровно, пока середина портов корабля не выйдет за дистанцию обхода; отошёл
     * (или штекера нет) - обычный полёт.
     */
    @Nullable
    private State depart(ServerLevel level, FlightControlComputerBlockEntity cc) throws Exception {
        Ready r = ready(level, cc);
        if (r == null) {
            release();
            return State.COMPUTER_OFF;
        }
        DockingControllerBlockEntity plug = plug(level);
        Vec3 lt = plug == null ? null : plug.localTip();
        if (lt == null || departTip == null) {
            departUntil = Long.MIN_VALUE;
            return null;
        }
        Vec3 w = SableCompat.toWorld(level, lt);
        Vec v = Docking.depart(new Vec(w.x, w.y, w.z), departTip, departDir, departClear, maxSpeed);
        if (v == null) {                                                    // отошёл на дистанцию обхода
            departUntil = Long.MIN_VALUE;
            return null;
        }
        Object sub = subLevel(level);
        Vector3d up = AttitudeTargetUtil.rotateBodyToWorld(sub, new Vector3d(0, 1, 0));
        State s = lease(cc, r, sub, v, Docking.levelRates(new Vec(up.x, up.y, up.z)), false);
        return s == State.ACTIVE ? State.DOCK_DEPART : s;
    }

    // ------------------------------------------------------------- аренда регуляторов

    /**
     * Как навигационный терминал Flight Control: аренда у компьютера осей регулятора скорости (движение, v - мир, бл/с)
     * и регулятора угловой скорости (повороты, w - мир, рад/с) с их целями; null - этим регулятором не управлять.
     * Остальные регуляторы, которые спорили бы с ними (стабилизаторы ориентации и высоты, регулятор дистанции,
     * терминал мыши, лишние регуляторы скорости), на время аренды выключены. allOff - выключить и регуляторы скорости
     * (корабль пристыкован, его держит сцепка).
     */
    private State lease(FlightControlComputerBlockEntity cc, Ready r, Object sub, @Nullable Vec v, @Nullable Vec w, boolean allOff)
            throws Exception {
        NavigationControlNetworkSnapshot net = r.net();
        Map<NavigationControlDomain, NavigationControlCommand> cmds = new HashMap<>();
        boolean needVel = false, needAng = false;
        if (allOff) {
            for (NavigationControllerSnapshot n : net.controllers()) {
                if (!usable(n)) continue;
                NavigationControllerKind k = n.kind();
                if (k == NavigationControllerKind.BODY_VELOCITY || k == NavigationControllerKind.BODY_ANGULAR_VELOCITY
                        || k == NavigationControllerKind.ALTITUDE_HOLD || k == NavigationControllerKind.ATTITUDE_HOLD
                        || k == NavigationControllerKind.DISTANCE || k == NavigationControllerKind.MOUSE_FLIGHT) {
                    cmds.put(NavigationControlDomain.participation(n.index(), n.nodeIncarnation()), NavigationControlCommand.Participation.REQUEST_OFF);
                }
            }
        } else {
            NavigationControllerSnapshot vel = null, ang = null;
            for (NavigationControllerSnapshot n : net.controllers()) {
                if (!usable(n)) continue;
                if (vel == null && n.kind() == NavigationControllerKind.BODY_VELOCITY) vel = n;
                if (ang == null && n.kind() == NavigationControllerKind.BODY_ANGULAR_VELOCITY) ang = n;
            }
            needVel = v != null && vel == null;
            needAng = w != null && ang == null;
            if (v == null) vel = null;
            if (w == null) ang = null;
            if (vel == null && ang == null) {
                release();
                return needVel && needAng ? State.NEED_COUPLERS : needVel ? State.NEED_VELOCITY : needAng ? State.NEED_ANGULAR : State.ACTIVE;
            }
            if (vel != null) {
                Vector3d vb = new Vector3d(v.x(), v.y(), v.z());
                AttitudeTargetUtil.worldToBody(sub, vb);
                track(cmds, vel, vb, NavigationControlDomain.SemanticAxis.BODY_VELOCITY_X, NavigationControlDomain.SemanticAxis.BODY_VELOCITY_Y,
                        NavigationControlDomain.SemanticAxis.BODY_VELOCITY_Z);
            }
            if (ang != null) {
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
        }
        if (cmds.isEmpty()) {
            release();
            return State.ACTIVE;
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
            identity = new NavigationLeaseIdentity(net.dimensionId(), r.vehicle().structureId(), worldPosition.asLong(), UUID.randomUUID());
            revision = 0;
            NavigationLeaseBroker.BundleResult res = broker.tryAcquireAndPublish(identity, net.topologyEpoch(), want, revision, cmds, now);
            if (!res.succeeded() || res.token() == null) {
                if (res.status() == NavigationLeaseBroker.Status.COMPUTER_INACTIVE) return State.COMPUTER_OFF;
                return State.BUSY;
            }
            token = res.token();
            leased = want;
            epoch = net.topologyEpoch();
            leaseComputer = cc;
        }
        return needVel ? State.NEED_VELOCITY : needAng ? State.NEED_ANGULAR : State.ACTIVE;
    }
    /**
     * Постройка Sable, на которой стоит мост. Поиск у Flight Control идёт рефлексией - на каждый тик дорого, поэтому
     * найденная постройка держится, пока жива (проверка раз в секунду).
     */
    private Object subLevel(ServerLevel level) throws Exception {
        long now = level.getGameTime();
        if (cachedSub == null || now - cachedSubAt >= 20 || cachedSub instanceof dev.ryanhcode.sable.sublevel.SubLevel s && s.isRemoved()) {
            cachedSub = AttitudeTargetUtil.getContainingSubLevel(level, worldPosition, this);
            cachedSubAt = now;
        }
        return cachedSub;
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
        poweredDown = tag.getBoolean("DockPoweredDown");
    }

    @Override
    protected void writeClientExtra(CompoundTag t) {
        writeLinks(t);
    }

    private void writeLinks(CompoundTag tag) {
        if (poweredDown) tag.putBoolean("DockPoweredDown", true);
        if (computerOffset != null) tag.putLong("ComputerOffset", computerOffset.asLong());
        saveForwardIndicatorLink(tag);
        if (forward != null) tag.putString("Forward", forward.serializedName());
    }
}
