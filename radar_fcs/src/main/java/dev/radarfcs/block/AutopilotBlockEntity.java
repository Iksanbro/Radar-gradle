package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Autopilot;
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
        ACTIVE, PAUSED, NO_SHIP, NO_COMPUTER, NO_TARGET;

        public String key() {
            return "radar_fcs.autopilot.state." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Цель и настройки для физики (читается в шаге физики Sable, поэтому неизменяемая запись в volatile-поле):
     * мировое положение и скорость цели (бл/с) на игровой тик tick.
     */
    public record Goal(Vec pos, Vec vel, long tick, boolean heading, Autopilot.Mode mode, double distance, double height,
                       double maxSpeed) {
        /** Положение цели на момент time (игровые тики, дробные). */
        public Vec posAt(double time) {
            return pos.addScaled(vel, (time - tick) / 20.0);
        }
    }

    private static final EngageSettings ALL = allTargets();

    private static EngageSettings allTargets() {
        EngageSettings s = new EngageSettings();
        java.util.Arrays.fill(s.enabled, true);
        s.hostileOnly = false;
        s.threat = EngageSettings.Threat.ALL;
        return s;
    }

    // настройки (экран моста)
    public boolean heading = true;
    public Autopilot.Mode mode = Autopilot.Mode.DISTANCE;
    public double distance = 48, height = 16, maxSpeed = 20;

    @Nullable private UUID owner;
    private String ownerTeam = "";

    private final TargetSensor sensor = new TargetSensor();
    private final List<BlockEntity> radars = new ArrayList<>();
    private final List<BlockPos> monitors = new ArrayList<>(), crMonitors = new ArrayList<>(), computers = new ArrayList<>();
    private long lastDiscover = Long.MIN_VALUE / 2;
    @Nullable private volatile Goal goal;

    // сводка (сервер -> клиент)
    protected State state = State.NO_TARGET;
    private String targetName = "";
    private double targetDistance = -1;
    private long lastSync = Long.MIN_VALUE / 2;

    protected AutopilotBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    // ------------------------------------------------------------- наследнику

    /** Связан с компьютером управления полётом. */
    public abstract boolean computerLinked();

    /** Связан с индикатором направления вперёд (иначе «вперёд» - лицевая сторона моста). */
    public abstract boolean indicatorLinked();

    /** Цель и настройки для физики или null (нет цели, мост выключен). */
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
        }
        if (now % 2 == 0) sense(level, now);
        if (now - lastSync >= 10) {
            lastSync = now;
            State s = computeState(level);
            if (s != state || now % 20 == 0) {
                state = s;
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    private State computeState(ServerLevel level) {
        if (level.hasNeighborSignal(worldPosition)) return State.PAUSED;
        if (SableCompat.containingId(level, worldPosition) == null) return State.NO_SHIP;
        if (!computerLinked()) return State.NO_COMPUTER;
        if (goal == null) return State.NO_TARGET;
        return State.ACTIVE;
    }

    /** Аппаратура сети: радары Create Radars, мониторы (свои и CR), вычислители. */
    private void discover(ServerLevel level) {
        radars.clear();
        monitors.clear();
        crMonitors.clear();
        computers.clear();
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
                default -> { }
            }
        }
    }

    /** Пересмотреть аппаратуру сети в ближайший тик (линк поставлен или снят). */
    public void requestDiscover() {
        lastDiscover = Long.MIN_VALUE / 2;
    }

    private void sense(ServerLevel level, long now) {
        String id = targetId(level, now);
        if (id == null || level.hasNeighborSignal(worldPosition)) {
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
        Track t = sensor.get(id);
        if (t == null || (ship != null && id.equals(ship.toString()))) {
            setGoal(null, "", -1);
            return;
        }
        Vec vel = t.vel.scale(20);                                          // бл/тик -> бл/с
        setGoal(new Goal(t.pos, vel, now, heading, mode, distance, height, maxSpeed), t.name == null ? "?" : t.name,
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
        t.putString("Mode", mode.name());
        t.putDouble("Distance", distance);
        t.putDouble("Height", height);
        t.putDouble("MaxSpeed", maxSpeed);
        return t;
    }

    public void readSettings(CompoundTag t) {
        heading = !t.contains("Heading") || t.getBoolean("Heading");
        try {
            mode = Autopilot.Mode.valueOf(t.getString("Mode"));
        } catch (IllegalArgumentException e) {
            mode = Autopilot.Mode.DISTANCE;
        }
        distance = Autopilot.cleanDistance(t.contains("Distance") ? t.getDouble("Distance") : Double.NaN);
        height = Autopilot.cleanHeight(t.contains("Height") ? t.getDouble("Height") : Double.NaN);
        maxSpeed = Autopilot.cleanSpeed(t.contains("MaxSpeed") ? t.getDouble("MaxSpeed") : Double.NaN);
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
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.put("Autopilot", writeSettings());
        t.putString("State", state.name());
        t.putString("TargetName", targetName);
        t.putDouble("TargetDist", targetDistance);
        writeClientExtra(t);
        return t;
    }

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
        ChatFormatting c = state == State.ACTIVE ? ChatFormatting.GREEN : state == State.NO_TARGET ? ChatFormatting.GRAY : ChatFormatting.RED;
        tooltip.add(Component.literal("    ").append(Component.translatable(state.key())).withStyle(c));
        if (!targetName.isEmpty()) {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.autopilot.target", targetName,
                    String.format(Locale.ROOT, "%.0f", targetDistance))).withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.autopilot.heading"))
                .append(": ").append(Component.translatable(heading ? "radar_fcs.gun.on" : "radar_fcs.gun.off")).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("    ").append(Component.translatable(mode.key())).withStyle(ChatFormatting.GRAY));
        if (mode != Autopilot.Mode.OFF) {
            tooltip.add(Component.literal("    ").append(Component.translatable(mode == Autopilot.Mode.DISTANCE_ALTITUDE
                    ? "radar_fcs.autopilot.summary_alt" : "radar_fcs.autopilot.summary",
                    fmt(distance), fmt(height), fmt(maxSpeed))).withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.literal("    ").append(Component.translatable(indicatorLinked()
                ? "radar_fcs.autopilot.indicator_linked" : "radar_fcs.autopilot.indicator_none"))
                .withStyle(indicatorLinked() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_GRAY));
        return true;
    }

    public static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
