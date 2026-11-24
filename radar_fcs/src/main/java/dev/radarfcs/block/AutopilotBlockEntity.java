package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.core.Docking;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.IffRule;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.Vec;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.fcs.TargetSensor;
import dev.radarfcs.link.LinkKind;
import dev.radarfcs.link.LinkRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

/**
 * Мост автопилота (часть без классов Flight Control): цель радарной сети - назначенная на мониторе сети (своём или
 * Create Radars), иначе та, по которой ведёт огонь вычислитель сети, - и настройки (поворот корпуса, режим дальности,
 * дальность, высота над целью, предел скорости). Наследник в {@code compat.flight} отдаёт по ним силу и момент
 * компьютеру управления полётом Flight Control.
 */
public abstract class AutopilotBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    /** Состояние для очков инженера. */
    public enum State {
        ACTIVE, PAUSED, NO_SHIP, NO_COMPUTER, NO_TARGET,
        /** Компьютер управления полётом выключен, в режиме отладки или ещё не знает корабль. */
        COMPUTER_OFF,
        /** Нужен регулятор скорости (движение) / угловой скорости (повороты) / оба, связанные с компьютером и с вращением. */
        NEED_VELOCITY, NEED_ANGULAR, NEED_COUPLERS,
        /** Регуляторы заняты другим автопилотом (навигационный терминал) или их держит редстоун-прокси. */
        BUSY,
        /** Стыковка: нет штекера в сети на этом корабле / штекер не связан с портом Simulated. */
        DOCK_NO_PLUG, DOCK_NO_CONNECTOR,
        /** Стыковка: портов с номером штекера в сети нет / все заняты (режим «не лететь»). */
        DOCK_NO_PORT, DOCK_PORTS_BUSY,
        /** Стыковка: летит к порту / разворачивается у точки подхода / ждёт у занятого порта / ближний подход. */
        DOCK_TRANSIT, DOCK_ALIGN, DOCK_WAITING, DOCK_FINAL,
        /** Стыковка: штекер так не встанет к порту (наклон порта не тот), / пристыкован / отходит от порта. */
        DOCK_TILT, DOCKED, DOCK_DEPART,
        /** Стыковка: расстановка портов корабля не совпадает с расстановкой портов стыковки (сядет не на все). */
        DOCK_LAYOUT,
        /** Пристыкован, расстыковка запрещена: порт держат сигналом редстоуна. */
        DOCK_HELD,
        /** Цели нет: висит на месте / летит по орбите / орбита вокруг маяка, а маяка в сети нет (висит). */
        IDLE_HOVER, IDLE_ORBIT, IDLE_NO_BEACON;

        public String key() {
            return "radar_fcs.autopilot.state." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Цель и настройки для физики (читается в шаге физики Sable, поэтому неизменяемая запись в volatile-поле):
     * мировое положение и скорость цели (бл/с) на игровой тик tick.
     */
    public record Goal(String id, Vec pos, Vec vel, long tick, boolean yaw, boolean pitch, boolean roll, double maxPitch, double maxRoll,
                       Autopilot.Mode mode, double distance, double height, double maxSpeed) {
        /** Мост поворачивает корпус (хоть одна ось). */
        public boolean turns() {
            return yaw || pitch || roll;
        }

        /** Положение цели на момент time (игровые тики, дробные). */
        public Vec posAt(double time) {
            return pos.addScaled(vel, (time - tick) / 20.0);
        }
    }

    private static final EngageSettings ALL = allTargets();

    /** Все типы целей, кроме снарядов и ракет: мосту они не нужны, а их прогноз полёта дорог. */
    private static EngageSettings allTargets() {
        EngageSettings s = new EngageSettings();
        java.util.Arrays.fill(s.enabled, true);
        s.enabled[EngageSettings.Category.SHELLS.ordinal()] = false;
        s.enabled[EngageSettings.Category.ROCKETS.ordinal()] = false;
        s.hostileOnly = false;
        return s;
    }

    // настройки (экран моста)
    /** Оси ведения цели: рыскание (курс), тангаж (нос на угол места цели), крен (в сторону поворота). */
    public boolean heading = true, pitch, roll;
    public double maxPitch = Autopilot.PITCH_DEFAULT, maxRoll = Autopilot.ROLL_DEFAULT;
    public Autopilot.Mode mode = Autopilot.Mode.DISTANCE;
    public double distance = 48, height = 16, maxSpeed = 20;
    /** Когда лететь на стыковку и что делать, если порты заняты. */
    public Docking.Trigger dockTrigger = Docking.Trigger.OFF;
    public Docking.Busy dockBusy = Docking.Busy.WAIT;
    /** Без цели: висеть или орбита (центр - координата или маяк орбиты сети), радиус, высота над центром, направление. */
    public Autopilot.Idle idle = Autopilot.Idle.HOVER;
    public double orbitX, orbitY = 64, orbitZ, orbitRadius = 48, orbitHeight = 24;
    public boolean orbitClockwise;
    /** Код маяка орбиты (пусто - первый маяк своей радарной сети). */
    public String orbitCode = "";
    /** Скорость по орбите, бл/с, и куда смотрит нос на орбите. */
    public double orbitSpeed = 10;
    public Autopilot.OrbitFacing orbitFacing = Autopilot.OrbitFacing.ALONG;

    @Nullable private UUID owner;
    private String ownerTeam = "";

    private final TargetSensor sensor = new TargetSensor();
    private final List<BlockEntity> radars = new ArrayList<>();
    private final List<BlockPos> monitors = new ArrayList<>(), crMonitors = new ArrayList<>(), computers = new ArrayList<>();
    private long lastDiscover = Long.MIN_VALUE / 2;
    @Nullable private volatile Goal goal;
    /** Штекеры стыковки сети на этом корабле. */
    protected final List<BlockPos> plugs = new ArrayList<>();
    /** Маяки орбиты сети. */
    protected final List<BlockPos> beacons = new ArrayList<>();
    /** Пауза по сигналу редстоуна (в этот тик). */
    protected boolean paused;
    /** С какого тика нет цели (для стыковки «нет цели»). */
    private long noGoalSince = Long.MIN_VALUE;
    /** Мост ведёт корабль на стыковку (или он пристыкован). */
    protected boolean docking;
    // сводка стыковки (сервер -> клиент): порт и расстояние
    protected String dockName = "";
    protected double dockDist = -1;

    // сводка (сервер -> клиент)
    protected State state = State.NO_TARGET;
    private String targetName = "";
    private double targetDistance = -1;
    private long lastSync = Long.MIN_VALUE / 2;
    private String lastSummary = "";

    protected AutopilotBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    // ------------------------------------------------------------- наследнику

    /** Связан с компьютером управления полётом. */
    public abstract boolean computerLinked();

    /** Связан с индикатором направления вперёд (иначе «вперёд» - лицевая сторона моста). */
    public abstract boolean indicatorLinked();

    /**
     * Каждый тик на сервере: вести корабль на стыковку (dock), иначе к цели (goal) или отпустить управление (null - нет
     * цели или пауза).
     */
    protected abstract void drive(ServerLevel level, @Nullable Goal goal, boolean dock);

    /** Состояние управления, когда цель есть (связь с компьютером уже проверена). */
    protected abstract State driveState();

    /** Цель и настройки или null (нет цели, мост выключен). */
    @Nullable
    public Goal goal() {
        return goal;
    }

    // ------------------------------------------------------------- цикл

    public static void serverTick(Level level, BlockPos pos, BlockState st, AutopilotBlockEntity be) {
        if (level instanceof ServerLevel sl) be.tick(sl);
    }

    private void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastDiscover >= 20) {
            lastDiscover = now;
            discover(level);
            maintainLinks(level);
        }
        boolean signal = level.hasNeighborSignal(worldPosition);
        // стыковка по редстоуну: сигнал - на стыковку, а не пауза
        paused = signal && dockTrigger != Docking.Trigger.REDSTONE;
        if (now % 2 == 0) sense(level, now, paused);
        // пауза отсчёт «нет цели» не сбрасывает: пристыкованный корабль после паузы не расстыковывается на 2 с
        if (goal != null) noGoalSince = Long.MIN_VALUE;
        else if (noGoalSince == Long.MIN_VALUE) noGoalSince = now;
        docking = switch (dockTrigger) {
            case REDSTONE -> signal;
            case NO_TARGET -> !paused && goal == null && noGoalSince != Long.MIN_VALUE && now - noGoalSince >= Docking.NO_TARGET_DELAY;
            default -> false;
        };
        if (!docking) {
            dockName = "";
            dockDist = -1;
        }
        drive(level, paused ? null : goal, docking);
        if (now - lastSync >= 10) {
            lastSync = now;
            // клиенту (очки инженера) - только когда сводка изменилась; состояние не сохраняется - чанк не трогаем
            state = computeState(level, paused);
            String summary = state + "|" + targetName + "|" + Math.round(targetDistance) + "|" + dockName + "|" + Math.round(dockDist);
            if (!summary.equals(lastSummary)) {
                lastSummary = summary;
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    private State computeState(ServerLevel level, boolean paused) {
        if (paused) return State.PAUSED;
        if (SableCompat.containingId(level, worldPosition) == null) return State.NO_SHIP;
        if (!computerLinked()) return State.NO_COMPUTER;
        if (docking) return driveState();
        // без цели мост держит корабль на месте или ведёт по орбите (режим «только поворот» - не управляет)
        if (goal == null && mode == Autopilot.Mode.OFF) return State.NO_TARGET;
        return driveState();
    }

    /** Аппаратура сети: радары Create Radars, мониторы (свои и CR), вычислители. */
    private void discover(ServerLevel level) {
        radars.clear();
        monitors.clear();
        crMonitors.clear();
        computers.clear();
        plugs.clear();
        beacons.clear();
        UUID ship = SableCompat.containingId(level, worldPosition);
        for (BlockPos p : LinkRegistry.linkedTo(level, worldPosition)) {
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            LinkKind k = LinkKind.of(be);
            if (k == null) continue;
            switch (k) {
                case RADAR -> radars.add(be);
                case MONITOR -> monitors.add(p);
                case CR_MONITOR -> crMonitors.add(p);
                case FCS -> computers.add(p);
                case BEACON -> beacons.add(p);
                case DOCKING -> {
                    // штекер на этом же корабле
                    if (be instanceof DockingControllerBlockEntity d && d.role == Docking.Role.PLUG && ship != null
                            && ship.equals(SableCompat.containingId(level, p))) plugs.add(p);
                }
                default -> { }
            }
        }
    }

    /** Пересмотреть аппаратуру сети в ближайший тик (линк поставлен или снят). */
    public void requestDiscover() {
        lastDiscover = Long.MIN_VALUE / 2;
    }

    private void sense(ServerLevel level, long now, boolean paused) {
        String id = paused ? null : targetId(level, now);
        // цели нет (ничего не назначено, вычислители не стреляют) - сенсор не нужен: другие объекты корабль не ведут
        if (id == null) {
            sensor.clear();
            setGoal(null, "", -1);
            return;
        }
        List<BlockEntity> live = new ArrayList<>();
        for (BlockEntity r : radars) if (!r.isRemoved() && RadarLink.isRunning(r)) live.add(r);
        UUID ship = SableCompat.containingId(level, worldPosition);
        Vec3 o = SableCompat.toWorld(level, Vec3.atCenterOf(worldPosition));
        Vec origin = new Vec(o.x, o.y, o.z);
        IffRule iff = IffRule.DEFAULT;
        for (BlockPos p : computers) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof FireControlBlockEntity f) {
                iff = f.effectiveSettings().iffRule();
                break;
            }
        }
        TargetSensor.Context ctx = new TargetSensor.Context(owner, ownerTeam, iff, ship == null ? Set.of() : Set.of(ship), origin);
        sensor.sense(level, live, ctx, now, ALL, t -> false, id);
        Track t = id == null ? null : sensor.get(id);
        // за снарядом (вычислитель сети его перехватывает) корабль не гоняем
        if (t != null && t.kind == Track.Kind.PROJECTILE) t = null;
        if (t == null || (ship != null && id.equals(ship.toString()))) {
            setGoal(null, "", -1);
            return;
        }
        Vec vel = t.vel.scale(20);                                          // бл/тик -> бл/с
        setGoal(new Goal(id, t.pos, vel, now, heading, pitch, roll, maxPitch, maxRoll, mode, distance, height, maxSpeed), t.name == null ? "?" : t.name,
                t.pos.sub(origin).horizontalLength());
    }

    private void setGoal(@Nullable Goal g, String name, double dist) {
        goal = g;
        targetName = name;
        targetDistance = dist;
    }

    /**
     * Цель моста: назначенная на мониторе сети (свой радарный - первым, затем Create Radars), иначе цель, которую
     * ведёт или по которой стреляет вычислитель сети; null - цели нет.
     */
    @Nullable
    private String targetId(ServerLevel level, long now) {
        for (BlockPos p : monitors) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof RadarMonitorBlockEntity m) {
                UUID d = m.localDesignation(now);
                if (d != null) return d.toString();
            }
        }
        for (BlockPos p : crMonitors) {
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            String sel = be == null ? null : RadarLink.monitorSelection(be);
            if (sel != null) return sel;
        }
        for (BlockPos p : computers) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof FireControlBlockEntity f)) continue;
            String des = f.currentDesignation();
            if (des != null) return des;
            for (MountState m : f.mountStates()) {
                if (m.targetId != null && (m.status == Status.FIRE || m.status == Status.AIMING)) return m.targetId;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- настройки

    public void setOwner(Player p) {
        owner = p.getUUID();
        Team t = p.getTeam();
        ownerTeam = t != null ? t.getName() : "";
        setChanged();
    }

    public CompoundTag writeSettings() {
        CompoundTag t = new CompoundTag();
        t.putBoolean("Heading", heading);
        t.putBoolean("Pitch", pitch);
        t.putBoolean("Roll", roll);
        t.putDouble("MaxPitch", maxPitch);
        t.putDouble("MaxRoll", maxRoll);
        t.putString("Mode", mode.name());
        t.putDouble("Distance", distance);
        t.putDouble("Height", height);
        t.putDouble("MaxSpeed", maxSpeed);
        t.putString("Dock", dockTrigger.name());
        t.putString("DockBusy", dockBusy.name());
        t.putString("Idle", idle.name());
        t.putDouble("OrbitX", orbitX);
        t.putDouble("OrbitY", orbitY);
        t.putDouble("OrbitZ", orbitZ);
        t.putDouble("OrbitRadius", orbitRadius);
        t.putDouble("OrbitHeight", orbitHeight);
        t.putBoolean("OrbitCW", orbitClockwise);
        t.putString("OrbitCode", orbitCode);
        t.putDouble("OrbitSpeed", orbitSpeed);
        t.putString("OrbitFacing", orbitFacing.name());
        return t;
    }

    public void readSettings(CompoundTag t) {
        heading = !t.contains("Heading") || t.getBoolean("Heading");
        pitch = t.getBoolean("Pitch");
        roll = t.getBoolean("Roll");
        maxPitch = Autopilot.cleanPitch(t.contains("MaxPitch") ? t.getDouble("MaxPitch") : Double.NaN);
        maxRoll = Autopilot.cleanRoll(t.contains("MaxRoll") ? t.getDouble("MaxRoll") : Double.NaN);
        try {
            mode = Autopilot.Mode.valueOf(t.getString("Mode"));
        } catch (IllegalArgumentException e) {
            mode = Autopilot.Mode.DISTANCE;
        }
        distance = Autopilot.cleanDistance(t.contains("Distance") ? t.getDouble("Distance") : Double.NaN);
        height = Autopilot.cleanHeight(t.contains("Height") ? t.getDouble("Height") : Double.NaN);
        maxSpeed = Autopilot.cleanSpeed(t.contains("MaxSpeed") ? t.getDouble("MaxSpeed") : Double.NaN);
        dockTrigger = enumOr(Docking.Trigger.class, t.getString("Dock"), Docking.Trigger.OFF);
        dockBusy = enumOr(Docking.Busy.class, t.getString("DockBusy"), Docking.Busy.WAIT);
        idle = enumOr(Autopilot.Idle.class, t.getString("Idle"), Autopilot.Idle.HOVER);
        orbitX = Autopilot.cleanCoord(t.getDouble("OrbitX"));
        orbitY = t.contains("OrbitY") ? Autopilot.cleanCoord(t.getDouble("OrbitY")) : 64;
        orbitZ = Autopilot.cleanCoord(t.getDouble("OrbitZ"));
        orbitRadius = Autopilot.cleanRadius(t.contains("OrbitRadius") ? t.getDouble("OrbitRadius") : Double.NaN);
        orbitHeight = Autopilot.cleanHeight(t.contains("OrbitHeight") ? t.getDouble("OrbitHeight") : 24);
        orbitClockwise = t.getBoolean("OrbitCW");
        orbitCode = OrbitBeaconBlockEntity.cleanCode(t.getString("OrbitCode"));
        orbitSpeed = Autopilot.cleanSpeed(t.contains("OrbitSpeed") ? t.getDouble("OrbitSpeed") : 10);
        orbitFacing = enumOr(Autopilot.OrbitFacing.class, t.getString("OrbitFacing"), Autopilot.OrbitFacing.ALONG);
    }

    public static <E extends Enum<E>> E enumOr(Class<E> c, String name, E fallback) {
        try {
            return Enum.valueOf(c, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Новые настройки с экрана (значения чистятся). */
    public void applySettings(CompoundTag t) {
        readSettings(t);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public State state() {
        return state;
    }

    public String targetName() {
        return targetName;
    }

    // ------------------------------------------------------------- сохранение

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Autopilot", writeSettings());
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("OwnerTeam", ownerTeam);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readSettings(tag.getCompound("Autopilot"));
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerTeam = tag.getString("OwnerTeam");
        if (tag.contains("State")) {                                        // сводка с сервера
            try {
                state = State.valueOf(tag.getString("State"));
            } catch (IllegalArgumentException e) {
                state = State.NO_TARGET;
            }
            targetName = tag.getString("TargetName");
            targetDistance = tag.getDouble("TargetDist");
            dockName = tag.getString("DockName");
            dockDist = tag.getDouble("DockDist");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.put("Autopilot", writeSettings());
        t.putString("State", state.name());
        t.putString("TargetName", targetName);
        t.putDouble("TargetDist", targetDistance);
        t.putString("DockName", dockName);
        t.putDouble("DockDist", dockDist);
        writeClientExtra(t);
        return t;
    }

    /** Раз в секунду: связи с оборудованием, которого больше нет (сломано), снять. */
    protected void maintainLinks(ServerLevel level) {}

    /** Наследник добавляет в данные клиента своё (связи с Flight Control). */
    protected void writeClientExtra(CompoundTag t) {}

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------- очки инженера

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.flight_bridge")).withStyle(ChatFormatting.GOLD));
        ChatFormatting c = state == State.ACTIVE || state == State.DOCKED ? ChatFormatting.GREEN
                : state == State.NO_TARGET || state == State.IDLE_HOVER || state == State.IDLE_ORBIT ? ChatFormatting.GRAY
                : state.name().startsWith("DOCK_") && state != State.DOCK_NO_PLUG && state != State.DOCK_NO_CONNECTOR
                && state != State.DOCK_NO_PORT && state != State.DOCK_TILT && state != State.DOCK_LAYOUT ? ChatFormatting.AQUA : ChatFormatting.RED;
        tooltip.add(Component.literal("    ").append(Component.translatable(state.key())).withStyle(c));
        if (!dockName.isEmpty()) {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.autopilot.dock_port", dockName,
                    String.format(Locale.ROOT, "%.0f", dockDist))).withStyle(ChatFormatting.AQUA));
        }
        if (!targetName.isEmpty()) {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.autopilot.target", targetName,
                    String.format(Locale.ROOT, "%.0f", targetDistance))).withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.autopilot.axes")).append(": ")
                .append(axis("yaw", heading)).append(" ").append(axis("roll", roll)).append(" ").append(axis("pitch", pitch))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("    ").append(Component.translatable(mode.key())).withStyle(ChatFormatting.GRAY));
        if (mode != Autopilot.Mode.OFF) {
            tooltip.add(Component.literal("    ").append(Component.translatable(mode == Autopilot.Mode.DISTANCE_ALTITUDE
                    ? "radar_fcs.autopilot.summary_alt" : "radar_fcs.autopilot.summary",
                    fmt(distance), fmt(height), fmt(maxSpeed))).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (mode != Autopilot.Mode.OFF) {
            tooltip.add(Component.literal("    ").append(Component.translatable(idle.key())).withStyle(ChatFormatting.DARK_AQUA));
        }
        if (dockTrigger != Docking.Trigger.OFF) {
            tooltip.add(Component.literal("    ").append(Component.translatable(dockTrigger.key())).withStyle(ChatFormatting.DARK_AQUA));
        }
        tooltip.add(Component.literal("    ").append(Component.translatable(indicatorLinked()
                ? "radar_fcs.autopilot.indicator_linked" : "radar_fcs.autopilot.indicator_none"))
                .withStyle(indicatorLinked() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_GRAY));
        return true;
    }

    private static Component axis(String name, boolean on) {
        return Component.translatable("radar_fcs.autopilot.axis." + name).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY);
    }

    public static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
