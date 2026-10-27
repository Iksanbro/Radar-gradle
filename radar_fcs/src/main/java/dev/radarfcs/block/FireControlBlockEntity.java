package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.Vec;
import dev.radarfcs.fcs.FcsControl;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.fcs.TargetSensor;
import dev.radarfcs.radar.RadarEmitter;
import dev.radarfcs.radar.RadarNetwork;
import dev.radarfcs.registry.FcsRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

/**
 * Вычислитель управления огнём.
 * Цикл: обнаружение станков и радаров (plug & play) -> сенсор и треки -> для каждой пушки
 * выбор ближайшей цели -> баллистическое решение с упреждением -> привод с ограничением скорости
 * и мёртвой зоной -> огонь, только если фактический ствол в допуске и траектория безопасна.
 */
public class FireControlBlockEntity extends BlockEntity implements IHaveGoggleInformation {

    private final TargetSensor sensor = new TargetSensor();
    private final Map<BlockPos, MountState> mounts = new LinkedHashMap<>();
    private final List<BlockEntity> radars = new ArrayList<>();
    private List<RadarEmitter> ownRadars = List.of();
    private int runningRadars;
    private long lastDiscover = Long.MIN_VALUE / 2;
    private boolean hold;
    @Nullable private UUID owner;
    private String ownerTeam = "";
    private DyeColor channel = DyeColor.WHITE;
    private Set<UUID> platformShips = Set.of();
    @Nullable private UUID ownShip;

    // сводка для клиента (очки инженера) и Display Link
    private CompoundTag summary = new CompoundTag();
    private long lastSummary = Long.MIN_VALUE / 2;

    public FireControlBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.FIRE_CONTROL_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FireControlBlockEntity be) {
        if (level instanceof ServerLevel sl) be.tick(sl);
    }

    // ---------------------------------------------------------------- цикл

    private void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastDiscover >= FcsConfig.DISCOVER_INTERVAL.get()) {
            lastDiscover = now;
            discover(level, now);
        }
        Vec origin = origin(level);
        // свои радары канала (сеть) и радары Create Radars рядом
        ownRadars = RadarNetwork.linked(level, new Vec3(origin.x(), origin.y(), origin.z()), channel.getId(),
                FcsConfig.NETWORK_RANGE.get());
        runningRadars = ownRadars.size();
        for (BlockEntity r : radars) if (!r.isRemoved() && RadarLink.isRunning(r)) runningRadars++;
        if (FcsConfig.REQUIRE_RADAR.get() && runningRadars == 0) {
            sensor.clear();
            for (MountState m : mounts.values()) FcsControl.cease(level, m, Status.NO_RADAR);
        } else {
            if (now % FcsConfig.SCAN_INTERVAL.get() == 0) {
                List<BlockEntity> live = new ArrayList<>();
                for (BlockEntity r : radars) if (!r.isRemoved() && RadarLink.isRunning(r)) live.add(r);
                sensor.sense(level, live, ownRadars, context(level), now);
            }
            UUID designated = RadarNetwork.designated(level, channel.getId(), now);
            String des = designated == null ? null : designated.toString();
            for (MountState m : mounts.values()) {
                FcsControl.control(level, this, m, sensor, now, hold, channel.getId(), platformShips, des);
                if (m.targetId != null && (m.status == Status.FIRE || m.status == Status.AIMING)) {
                    UUID tid = uuidOrNull(m.targetId);
                    if (tid != null) RadarNetwork.markEngaged(level, channel.getId(), tid, now + 30);
                }
            }
            if (now % 10 == 0) FcsControl.visuals(level, sensor, mounts.values(), origin);
        }
        if (now - lastSummary >= 20) {
            lastSummary = now;
            updateSummary(level);
        }
    }

    @Nullable
    private static UUID uuidOrNull(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private TargetSensor.Context context(ServerLevel level) {
        return new TargetSensor.Context(owner, ownerTeam, channel.getId(), platformShips, origin(level));
    }

    /** Мировая позиция вычислителя (на корабле - через позу корабля). */
    public Vec origin(Level level) {
        Vec3 w = SableCompat.toWorld(level, Vec3.atCenterOf(worldPosition));
        return new Vec(w.x, w.y, w.z);
    }

    /** Plug & play: станки CBC и радары в радиусе, по картам block entity загруженных чанков. */
    private void discover(ServerLevel level, long now) {
        int r = FcsConfig.LINK_RADIUS.get();
        double r2 = (double) r * r;
        Set<BlockPos> found = new HashSet<>();
        radars.clear();
        int cx0 = (worldPosition.getX() - r) >> 4, cx1 = (worldPosition.getX() + r) >> 4;
        int cz0 = (worldPosition.getZ() - r) >> 4, cz1 = (worldPosition.getZ() + r) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity other : List.copyOf(chunk.getBlockEntities().values())) {
                    BlockPos p = other.getBlockPos();
                    if (other == this || other.isRemoved() || p.distSqr(worldPosition) > r2) continue;
                    if (RadarLink.isRadar(other)) {
                        radars.add(other);
                        continue;
                    }
                    CbcMount d = CbcMount.of(other);
                    if (d != null) {
                        BlockPos key = p.immutable();
                        found.add(key);
                        mounts.computeIfAbsent(key, MountState::new).mount = d;
                    }
                }
            }
        }
        Iterator<Map.Entry<BlockPos, MountState>> it = mounts.entrySet().iterator();
        while (it.hasNext()) {
            MountState m = it.next().getValue();
            if (!found.contains(m.pos)) {
                FcsControl.release(level, this, m);
                it.remove();
            }
        }
        // корабли, на которых стоит сама батарея: их корпус - не цель и не помеха
        Set<UUID> ships = new HashSet<>();
        ownShip = SableCompat.containingId(level, worldPosition);
        if (ownShip != null) ships.add(ownShip);
        for (MountState m : mounts.values()) {
            UUID s = SableCompat.containingId(level, m.pos);
            if (s != null) ships.add(s);
        }
        platformShips = Set.copyOf(ships);
    }

    public void releaseAll() {
        if (level instanceof ServerLevel sl) {
            for (MountState m : mounts.values()) FcsControl.release(sl, this, m);
        }
        mounts.clear();
    }

    @Override
    public void setRemoved() {
        releaseAll();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        releaseAll();
        super.onChunkUnloaded();
    }

    // ------------------------------------------------------------ интерфейс

    public void setOwner(Player p) {
        owner = p.getUUID();
        Team t = p.getTeam();
        ownerTeam = t != null ? t.getName() : "";
        setChanged();
    }

    public void setChannel(DyeColor c, Player p) {
        channel = c;
        setChanged();
        p.displayClientMessage(Component.translatable("radar_fcs.msg.channel",
                Component.translatable("color.minecraft." + c.getName())), true);
    }

    public void toggleHold(Player p) {
        hold = !hold;
        setChanged();
        p.displayClientMessage(Component.translatable(hold ? "radar_fcs.msg.hold" : "radar_fcs.msg.free")
                .withStyle(hold ? ChatFormatting.GOLD : ChatFormatting.GREEN), true);
    }

    public int activeTargetCount() {
        return summary.getInt("Targets");
    }

    public void report(Player p) {
        for (Component line : summaryLines(summary, true)) p.sendSystemMessage(line);
    }

    // ---------------------------------------------------------------- сводка

    private void updateSummary(ServerLevel level) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Radars", runningRadars);
        tag.putInt("RadarBlocks", radars.size() + ownRadars.size());
        tag.putBoolean("RadarOk", !FcsConfig.REQUIRE_RADAR.get() || runningRadars > 0);
        tag.putBoolean("Hold", hold);
        tag.putInt("Channel", channel.getId());
        tag.putInt("Targets", sensor.active().size());
        tag.putInt("Contacts", sensor.usedRadar() ? sensor.contacts() : sensor.all().size());
        tag.putBoolean("Direct", !sensor.usedRadar());
        Set<String> engaged = new HashSet<>();
        ListTag ml = new ListTag();
        for (MountState m : mounts.values()) {
            CompoundTag mt = new CompoundTag();
            mt.putInt("S", m.status.ordinal());
            mt.putString("N", m.mount == null ? "?" : m.mount.typeName());
            if (m.targetName != null && (m.status == Status.FIRE || m.status == Status.AIMING || m.status == Status.HOLD
                    || m.status == Status.BLOCKED_FRIENDLY || m.status == Status.BLOCKED_TERRAIN || m.status == Status.NO_AMMO
                    || m.status == Status.NO_POWER)) {
                mt.putString("T", m.targetName);
                mt.putFloat("D", (float) m.distance);
                mt.putFloat("E", (float) m.aimError);
                if (Double.isFinite(m.predictedMiss)) mt.putFloat("M", (float) m.predictedMiss);
                if (m.targetId != null) engaged.add(m.targetId);
            }
            // разброс CBC: отклонение направления до 0.0172*spread рад, т.е. примерно spread градусов
            if (m.estimate != null && Double.isFinite(m.estimate.spread())) mt.putFloat("P", (float) (m.estimate.spread() * 0.987));
            mt.putInt("L", m.correction.shots());
            if (m.note != null) mt.putString("X", m.note);
            ml.add(mt);
        }
        tag.put("Mounts", ml);
        Vec o = origin(level);
        ListTag tl = new ListTag();
        int n = 0;
        for (Track t : sensor.active()) {
            if (n++ >= 8) break;
            CompoundTag tt = new CompoundTag();
            tt.putString("N", t.name);
            tt.putFloat("D", (float) t.pos.distance(o));
            tt.putFloat("V", (float) (t.speedPerTick() * 20));
            tt.putBoolean("E", engaged.contains(t.id));
            tt.putString("K", t.kind.name());
            tl.add(tt);
        }
        tag.put("List", tl);
        boolean changed = !tag.equals(summary);
        summary = tag;
        boolean eng = !engaged.isEmpty();
        BlockState st = getBlockState();
        if (st.hasProperty(FireControlBlock.ENGAGED) && st.getValue(FireControlBlock.ENGAGED) != eng) {
            level.setBlock(worldPosition, st.setValue(FireControlBlock.ENGAGED, eng), 3);
        } else if (changed) {
            level.sendBlockUpdated(worldPosition, st, st, 3);
        }
        if (changed) level.updateNeighbourForOutputSignal(worldPosition, st.getBlock());
    }

    /** Строки сводки (общие для чата, очков и Display Link). */
    public static List<Component> summaryLines(CompoundTag s, boolean full) {
        List<Component> out = new ArrayList<>();
        boolean radarOk = s.getBoolean("RadarOk");
        DyeColor ch = DyeColor.byId(s.getInt("Channel"));
        out.add(Component.translatable("radar_fcs.report.header").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("  "))
                .append(radarOk
                        ? Component.translatable("radar_fcs.report.radar_ok", s.getInt("Radars")).withStyle(ChatFormatting.GREEN)
                        : Component.translatable("radar_fcs.report.radar_missing").withStyle(ChatFormatting.RED)));
        out.add(Component.translatable(s.getBoolean("Hold") ? "radar_fcs.report.hold" : "radar_fcs.report.free")
                .withStyle(s.getBoolean("Hold") ? ChatFormatting.GOLD : ChatFormatting.GREEN)
                .append(Component.literal("  "))
                .append(Component.translatable("radar_fcs.report.channel", Component.translatable("color.minecraft." + ch.getName()))
                        .withStyle(ChatFormatting.GRAY)));
        if (full) out.add(Component.translatable(s.getBoolean("Direct") ? "radar_fcs.report.sensor_direct" : "radar_fcs.report.sensor_radar",
                s.getInt("Contacts")).withStyle(ChatFormatting.DARK_GRAY));
        ListTag ml = s.getList("Mounts", Tag.TAG_COMPOUND);
        out.add(Component.translatable("radar_fcs.report.guns", ml.size()).withStyle(ChatFormatting.WHITE));
        for (int i = 0; i < ml.size(); i++) {
            CompoundTag m = ml.getCompound(i);
            Status st = Status.byOrdinal(m.getInt("S"));
            MutableComponent line = Component.literal(" " + m.getString("N") + ": ").withStyle(ChatFormatting.GRAY)
                    .append(Component.translatable(st.key()).withStyle(st.color));
            if (m.contains("T")) {
                line.append(Component.translatable("radar_fcs.report.engaging", m.getString("T"),
                        String.format(Locale.ROOT, "%.0f", m.getFloat("D"))).withStyle(ChatFormatting.WHITE));
                if (full && m.contains("M")) line.append(Component.translatable("radar_fcs.report.miss",
                        String.format(Locale.ROOT, "%.1f", m.getFloat("M"))).withStyle(ChatFormatting.DARK_GRAY));
            }
            if (m.contains("X")) line.append(Component.literal(" (" + m.getString("X") + ")").withStyle(ChatFormatting.DARK_GRAY));
            if (full && m.contains("P") && m.getFloat("P") >= 0.5f) line.append(Component.translatable("radar_fcs.report.spread",
                    String.format(Locale.ROOT, "%.1f", m.getFloat("P"))).withStyle(m.getFloat("P") >= 2f ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
            if (full && m.getInt("L") > 0) line.append(Component.translatable("radar_fcs.report.learned", m.getInt("L"))
                    .withStyle(ChatFormatting.DARK_GRAY));
            out.add(line);
        }
        ListTag tl = s.getList("List", Tag.TAG_COMPOUND);
        if (tl.isEmpty()) {
            out.add(Component.translatable("radar_fcs.report.no_targets").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            out.add(Component.translatable("radar_fcs.report.targets", s.getInt("Targets")).withStyle(ChatFormatting.WHITE));
            for (int i = 0; i < tl.size(); i++) {
                CompoundTag t = tl.getCompound(i);
                boolean e = t.getBoolean("E");
                out.add(Component.translatable("radar_fcs.report.target", e ? "◎" : "●", t.getString("N"),
                        String.format(Locale.ROOT, "%.0f", t.getFloat("D")), String.format(Locale.ROOT, "%.1f", t.getFloat("V")))
                        .withStyle(e ? ChatFormatting.RED : ChatFormatting.GOLD));
            }
        }
        return out;
    }

    public CompoundTag summary() {
        return summary;
    }

    /** Для тестов и отладки. */
    public List<MountState> mountStates() {
        return List.copyOf(mounts.values());
    }

    public TargetSensor sensor() {
        return sensor;
    }

    public List<RadarEmitter> linkedRadars() {
        return ownRadars;
    }

    public DyeColor channel() {
        return channel;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        for (Component c : summaryLines(summary, isPlayerSneaking)) tooltip.add(Component.literal("    ").append(c));
        return true;
    }

    // ------------------------------------------------------------ сохранение

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Hold", hold);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("OwnerTeam", ownerTeam);
        tag.putInt("Channel", channel.getId());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        hold = tag.getBoolean("Hold");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerTeam = tag.getString("OwnerTeam");
        channel = DyeColor.byId(tag.getInt("Channel"));
        if (tag.contains("Summary", Tag.TAG_COMPOUND)) summary = tag.getCompound("Summary");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        tag.put("Summary", summary.copy());
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
