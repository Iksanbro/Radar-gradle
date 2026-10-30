package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsDev;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.Vec;
import dev.radarfcs.energy.EnergyBuffer;
import dev.radarfcs.fcs.FcsControl;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.SettingsNbt;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.fcs.TargetSensor;
import dev.radarfcs.link.LinkKind;
import dev.radarfcs.link.LinkRegistry;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
    /** Радары Create Radars на линках. */
    private final List<BlockEntity> radars = new ArrayList<>();
    private int runningRadars;
    private long lastDiscover = Long.MIN_VALUE / 2;
    private boolean hold;
    @Nullable private UUID owner;
    private String ownerTeam = "";
    private Set<UUID> platformShips = Set.of();
    @Nullable private UUID ownShip;
    /** Настройки этого вычислителя (экран настроек). */
    private EngageSettings settings = new EngageSettings();
    /** Настройки с учётом памяти: в списках действуют первые capacity записей. */
    @Nullable private EngageSettings effective;
    /** Записей в каждом списке: 16 + 16 за каждый блок памяти с питанием в сети. */
    private int capacity = MemoryBlockEntity.BASE;
    private final List<BlockPos> memories = new ArrayList<>();
    /** Электричество (FE) и есть ли питание в этом тике. */
    private final EnergyBuffer energy = new EnergyBuffer(1024, this::setChanged);
    private boolean powered = true;
    /** false - пушки разобраны кнопкой «Разобрать» и не собираются автоматически. */
    private boolean assembleWanted = true;
    /** Выстрелы всех пушек по каждой цели и цели, сброшенные после заданного числа выстрелов. */
    private final Map<String, Integer> shotsAt = new java.util.HashMap<>();
    private final Set<String> dropped = new HashSet<>();
    /** Мониторы сети: с них назначение цели, на них - отметки целей под огнём; мониторы Create Radars - выбор цели. */
    private final List<BlockPos> linkedMonitors = new ArrayList<>();
    private final List<BlockPos> crMonitors = new ArrayList<>();

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
        // питание: без электричества вычислитель не видит целей и не управляет пушками
        int usage = FcsConfig.safe(FcsConfig.FCS_USAGE);
        energy.fit(usage);
        boolean p = !EnergyBuffer.required() || energy.consume(usage);
        if (p != powered) {
            powered = p;
            lastSummary = Long.MIN_VALUE / 2;
        }
        if (now % 20 == 0) updateCapacity(level);
        if (!powered) {
            sensor.clear();
            for (MountState m : mounts.values()) FcsControl.cease(level, m, Status.NO_ENERGY);
            if (now - lastSummary >= 20) {
                lastSummary = now;
                updateSummary(level);
            }
            return;
        }
        Vec origin = origin(level);
        // цели - только с работающих радаров Create Radars на линках
        List<BlockEntity> live = new ArrayList<>();
        for (BlockEntity r : radars) if (!r.isRemoved() && RadarLink.isRunning(r)) live.add(r);
        runningRadars = live.size();
        // ручное наведение: цель, выбранная на мониторе сети (своём или Create Radars)
        String des = designation(level, now);
        if (runningRadars == 0 && !FcsDev.radarless && des == null) {
            sensor.clear();
            for (MountState m : mounts.values()) FcsControl.cease(level, m, Status.NO_RADAR);
        } else {
            if (now % FcsConfig.SCAN_INTERVAL.get() == 0) {
                sensor.sense(level, live, context(level), now, sensorSettings(), t -> dropped.contains(t.id), des);
                // цели, которых больше нет, - из учёта выстрелов и сброса
                if (now % 100 == 0) {
                    shotsAt.keySet().removeIf(id -> sensor.get(id) == null);
                    dropped.removeIf(id -> sensor.get(id) == null);
                }
            }
            for (MountState m : mounts.values()) {
                FcsControl.control(level, this, m, sensor, now, hold, effectiveSettings().iffRule(), platformShips, des);
                if (m.targetId != null && (m.status == Status.FIRE || m.status == Status.AIMING)) {
                    UUID tid = uuidOrNull(m.targetId);
                    if (tid != null) {
                        for (BlockPos mp : linkedMonitors) {
                            if (level.isLoaded(mp) && level.getBlockEntity(mp) instanceof RadarMonitorBlockEntity mon) {
                                mon.markEngaged(tid, now + 30);
                            }
                        }
                    }
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
        Vec o = origin(level);
        // от снарядов защищаются вычислитель и его пушки (и свои зоны транспондеров - по правилу кодов)
        List<Vec> guard = new ArrayList<>();
        guard.add(o);
        for (MountState m : mounts.values()) {
            Vec3 w = SableCompat.toWorld(level, Vec3.atCenterOf(m.pos));
            guard.add(new Vec(w.x, w.y, w.z));
        }
        return new TargetSensor.Context(owner, ownerTeam, effectiveSettings().iffRule(), platformShips, o, guard, worldPosition,
                interceptAllowed());
    }

    /** Можно ли сбивать снаряды: установлен CBC Interception (или сервер разрешил без него). */
    public static boolean interceptAllowed() {
        return dev.radarfcs.compat.Interception.available() || !FcsConfig.INTERCEPT_REQUIRES_MOD.get();
    }

    @Nullable
    public UUID owner() { return owner; }

    public String ownerTeam() { return ownerTeam; }

    /** Мировая позиция вычислителя (на корабле - через позу корабля). */
    public Vec origin(Level level) {
        Vec3 w = SableCompat.toWorld(level, Vec3.atCenterOf(worldPosition));
        return new Vec(w.x, w.y, w.z);
    }

    /** Цель, назначенная вручную на мониторе сети: своём радарном или Create Radars (id трека) или null. */
    @Nullable
    private String designation(ServerLevel level, long now) {
        for (BlockPos p : linkedMonitors) {
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
        return null;
    }

    /** Назначенная сейчас цель (для экрана и тестов). */
    @Nullable
    public String currentDesignation() {
        return level instanceof ServerLevel sl ? designation(sl, sl.getGameTime()) : null;
    }

    /** Пересмотреть подключённую аппаратуру в ближайший тик (линк поставлен или снят). */
    public void requestDiscover() {
        lastDiscover = Long.MIN_VALUE / 2;
    }

    /** Подключённая аппаратура - только на радарных линках: станки CBC, радары Create Radars, мониторы. */
    private void discover(ServerLevel level, long now) {
        Set<BlockPos> found = new HashSet<>();
        radars.clear();
        linkedMonitors.clear();
        crMonitors.clear();
        memories.clear();
        for (BlockPos p : LinkRegistry.linkedTo(level, worldPosition)) {
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            LinkKind k = LinkKind.of(be);
            if (k == null) continue;
            switch (k) {
                case MOUNT -> {
                    BlockPos key = p.immutable();
                    found.add(key);
                    mounts.computeIfAbsent(key, MountState::new).mount = CbcMount.of(be);
                }
                case RADAR -> radars.add(be);
                case MONITOR -> {
                    RadarMonitorBlockEntity m = ((RadarMonitorBlockEntity) be).master();
                    if (!linkedMonitors.contains(m.getBlockPos())) linkedMonitors.add(m.getBlockPos());
                }
                case CR_MONITOR -> crMonitors.add(p.immutable());
                case MEMORY -> memories.add(p.immutable());
                default -> { }
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
        updateCapacity(level);
    }

    /** Ёмкость списков: 16 + 16 за каждый блок памяти с питанием в сети. */
    private void updateCapacity(ServerLevel level) {
        int n = 0;
        for (BlockPos p : memories) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof MemoryBlockEntity m && m.powered()) n++;
        }
        int cap = Math.min(EngageSettings.MAX_LIST, MemoryBlockEntity.BASE + MemoryBlockEntity.PER_BLOCK * n);
        if (cap != capacity) {
            capacity = cap;
            effective = null;
            lastSummary = Long.MIN_VALUE / 2;
        }
    }

    /** Записей в каждом списке (белый и чёрный списки целей, коды «свой-чужой»). */
    public int capacity() {
        return capacity;
    }

    /** Настройки, которые действуют: в списках - первые capacity записей. */
    public EngageSettings effectiveSettings() {
        if (effective == null) {
            effective = settings.limited(capacity);
            sensorEffective = effective.sensorUnion();
        }
        return effective;
    }

    /** Для сенсора: типы целей, разрешённые вычислителю или хотя бы одной пушке со своими целями. */
    private EngageSettings sensorSettings() {
        effectiveSettings();
        return sensorEffective;
    }

    @Nullable private EngageSettings sensorEffective;

    public EnergyBuffer energy() {
        return energy;
    }

    public boolean powered() {
        return powered;
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

    /** Видит и меняет ли игрок коды «свой-чужой» этого вычислителя (владелец, его команда, оператор). */
    public boolean canEditIff(Player p) {
        return dev.radarfcs.iff.IffAccess.canEdit(p, owner, ownerTeam);
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

    public EngageSettings settings() {
        return settings;
    }

    /** Новые настройки с экрана (проверяются здесь); p - кому сообщить (null - никому). */
    public void applySettings(EngageSettings s, @Nullable Player p) {
        EngageSettings next = s.copy().sanitize();
        if (p != null && !canEditIff(p)) {                               // чужим коды «свой-чужой» не менять
            next.iffWhitelist = settings.iffWhitelist;
            next.iffCodes.clear();
            next.iffCodes.addAll(settings.iffCodes);
        } else if (p != null && owner == null) {
            setOwner(p);
        }
        settings = next;
        effective = null;
        dropped.clear();
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        if (p != null) p.displayClientMessage(Component.translatable("radar_fcs.msg.settings_saved").withStyle(ChatFormatting.GREEN), true);
    }

    public boolean assembleWanted() {
        return assembleWanted;
    }

    /** Кнопка «Собрать»: собрать все пушки вычислителя сейчас и снова собирать их автоматически. */
    public void assembleAll(@Nullable Player p) {
        assembleWanted = true;
        setChanged();
        int ok = 0, total = 0;
        long now = level == null ? 0 : level.getGameTime();
        for (MountState m : mounts.values()) {
            if (m.mount == null || !m.mount.isValid() || m.status == Status.OTHER_FCS) continue;
            total++;
            if (FcsControl.assemble(m, now)) ok++;
        }
        lastSummary = Long.MIN_VALUE / 2;
        if (p != null) p.displayClientMessage(Component.translatable("radar_fcs.msg.assembled", ok, total)
                .withStyle(ok == total ? ChatFormatting.GREEN : ChatFormatting.GOLD), true);
    }

    /** Кнопка «Разобрать»: разобрать все пушки вычислителя; до «Собрать» он их не собирает. */
    public void disassembleAll(@Nullable Player p) {
        assembleWanted = false;
        setChanged();
        int n = 0;
        for (MountState m : mounts.values()) {
            if (m.status == Status.OTHER_FCS) continue;
            if (FcsControl.disassemble(m)) n++;
        }
        lastSummary = Long.MIN_VALUE / 2;
        if (p != null) p.displayClientMessage(Component.translatable("radar_fcs.msg.disassembled", n).withStyle(ChatFormatting.GRAY), true);
    }

    /** Настройки для всех клиентов: коды «свой-чужой» скрыты (их получает только тот, кому можно, при открытии экрана). */
    public EngageSettings settingsForClients() {
        EngageSettings s = settings.copy();
        s.iffCodes.clear();
        return s;
    }

    /** Выстрел пушки этого вычислителя по цели: учёт для сброса после N выстрелов. */
    public void onShotAt(String targetId) {
        int n = shotsAt.merge(targetId, 1, Integer::sum);
        if (settings.dropAfterShots > 0 && n >= settings.dropAfterShots) dropped.add(targetId);
    }

    public boolean isDropped(String targetId) {
        return dropped.contains(targetId);
    }

    // ---------------------------------------------------------------- сводка

    private void updateSummary(ServerLevel level) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Radars", runningRadars);
        tag.putBoolean("Power", powered);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("EnergyCap", energy.getMaxEnergyStored());
        tag.putInt("Usage", EnergyBuffer.required() ? FcsConfig.safe(FcsConfig.FCS_USAGE) : 0);
        tag.putInt("Capacity", capacity);
        tag.putInt("Memory", memories.size());
        tag.putInt("RadarBlocks", radars.size());
        tag.putBoolean("RadarOk", runningRadars > 0 || FcsDev.radarless);
        tag.putBoolean("Hold", hold);
        tag.putBoolean("IffWhitelist", settings.iffWhitelist);
        tag.putInt("IffCodes", settings.iffCodes.size());
        tag.putInt("Targets", sensor.active().size());
        tag.putInt("Contacts", sensor.usedRadar() ? sensor.contacts() : sensor.all().size());
        tag.putBoolean("Direct", !sensor.usedRadar());
        tag.putBoolean("Intercept", interceptAllowed());
        int incoming = 0;
        for (dev.radarfcs.core.Track t : sensor.active()) if (t.kind == dev.radarfcs.core.Track.Kind.PROJECTILE) incoming++;
        tag.putInt("Incoming", incoming);
        tag.putIntArray("Links", new int[]{mounts.size(), radars.size(), linkedMonitors.size() + crMonitors.size()});
        Set<String> engaged = new HashSet<>();
        ListTag ml = new ListTag();
        for (MountState m : mounts.values()) {
            CompoundTag mt = new CompoundTag();
            mt.putInt("S", m.status.ordinal());
            mt.putString("N", m.mount == null ? "?" : m.mount.typeName());
            mt.putLong("Q", m.pos.asLong());
            if (settings.mountTargets.containsKey(m.pos.asLong())) mt.putBoolean("C", true);
            if (m.targetName != null && (m.status == Status.FIRE || m.status == Status.AIMING || m.status == Status.HOLD
                    || m.status == Status.BLOCKED_FRIENDLY || m.status == Status.BLOCKED_TERRAIN || m.status == Status.NO_AMMO
                    || m.status == Status.NO_POWER || m.status == Status.NO_CONTROLLER || m.status == Status.PAUSE)) {
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
        if (s.contains("Power") && !s.getBoolean("Power")) {
            out.add(Component.translatable("radar_fcs.report.no_energy", s.getInt("Usage")).withStyle(ChatFormatting.RED));
        }
        out.add(Component.translatable("radar_fcs.report.header").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("  "))
                .append(radarOk
                        ? Component.translatable("radar_fcs.report.radar_ok", s.getInt("Radars")).withStyle(ChatFormatting.GREEN)
                        : Component.translatable("radar_fcs.report.radar_missing").withStyle(ChatFormatting.RED)));
        out.add(Component.translatable(s.getBoolean("Hold") ? "radar_fcs.report.hold" : "radar_fcs.report.free")
                .withStyle(s.getBoolean("Hold") ? ChatFormatting.GOLD : ChatFormatting.GREEN)
                .append(Component.literal("  "))
                .append(Component.translatable(s.getBoolean("IffWhitelist") ? "radar_fcs.report.iff_white" : "radar_fcs.report.iff_black",
                        s.getInt("IffCodes")).withStyle(ChatFormatting.GRAY)));
        if (full) out.add(Component.translatable(s.getBoolean("Direct") ? "radar_fcs.report.sensor_direct" : "radar_fcs.report.sensor_radar",
                s.getInt("Contacts")).withStyle(ChatFormatting.DARK_GRAY));
        int[] links = s.getIntArray("Links");
        if (full && links.length == 3) out.add(Component.translatable("radar_fcs.report.links", links[0], links[1], links[2])
                .withStyle(ChatFormatting.AQUA));
        if (full && s.contains("Intercept")) out.add(s.getBoolean("Intercept")
                ? Component.translatable("radar_fcs.report.intercept_on", s.getInt("Incoming")).withStyle(ChatFormatting.GRAY)
                : Component.translatable("radar_fcs.report.intercept_off").withStyle(ChatFormatting.GOLD));
        if (full && s.contains("Capacity")) out.add(Component.translatable("radar_fcs.report.memory", s.getInt("Capacity"),
                s.getInt("Memory")).withStyle(ChatFormatting.GRAY));
        if (full && s.getInt("Usage") > 0) out.add(Component.translatable("radar_fcs.energy.stored", s.getInt("Energy"),
                s.getInt("EnergyCap"), s.getInt("Usage")).withStyle(ChatFormatting.DARK_GRAY));
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
            if (m.getBoolean("C")) line.append(Component.translatable("radar_fcs.report.own_targets").withStyle(ChatFormatting.AQUA));
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

    /** Радары Create Radars на линках. */
    public List<BlockEntity> linkedRadars() {
        return radars;
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
        tag.put("Settings", SettingsNbt.write(settings));
        tag.putBoolean("Assemble", assembleWanted);
        energy.save(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        hold = tag.getBoolean("Hold");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerTeam = tag.getString("OwnerTeam");
        settings = SettingsNbt.read(tag.getCompound("Settings"));
        assembleWanted = !tag.contains("Assemble") || tag.getBoolean("Assemble");
        energy.load(tag);
        effective = null;
        if (tag.contains("Summary", Tag.TAG_COMPOUND)) summary = tag.getCompound("Summary");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        tag.put("Settings", SettingsNbt.write(settingsForClients()));   // без кодов «свой-чужой»
        tag.put("Summary", summary.copy());
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
