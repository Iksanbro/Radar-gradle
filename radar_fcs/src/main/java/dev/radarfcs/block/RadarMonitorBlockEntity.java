package dev.radarfcs.block;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.IffRule;
import dev.radarfcs.iff.IffRegistry;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.Vec;
import dev.radarfcs.energy.EnergyBuffer;
import dev.radarfcs.fcs.TargetSensor;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.link.LinkRegistry;
import dev.radarfcs.registry.FcsRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

/**
 * Радарный монитор. Нижний левый блок стены мониторов (если смотреть на экран) - ведущий:
 * он собирает отметки радаров своей сети и рисует общий экран: все цели с отметкой свой / чужой / неактивный,
 * фильтры показа по типам, назначенную цель и цели под огнём. Назначение цели (клик по отметке или экран монитора)
 * берут вычислители сети - ручное наведение.
 */
public class RadarMonitorBlockEntity extends BlockEntity {
    /** Сторона карты (по короткой стороне экрана), блоков: вводится числом в этих пределах. */
    public static final int MIN_SIDE = 16, MAX_SIDE = 4096, DEFAULT_SIDE = 256;
    /** Шаги колеса мыши по стороне карты. */
    public static final int[] SIDE_STEPS = {16, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096};
    /** Старые масштабы (1.2.0, NBT `MapScale` - индекс). */
    private static final int[] OLD_SCALES = {64, 128, 256, 512, 1024, 2048};

    public static int clampSide(int side) {
        return Math.max(MIN_SIDE, Math.min(MAX_SIDE, side));
    }

    /** Следующий шаг колеса от side вверх (dir > 0) или вниз. */
    public static int stepSide(int side, int dir) {
        if (dir > 0) {
            for (int v : SIDE_STEPS) if (v > side) return v;
            return MAX_SIDE;
        }
        for (int i = SIDE_STEPS.length - 1; i >= 0; i--) if (SIDE_STEPS[i] < side) return SIDE_STEPS[i];
        return MIN_SIDE;
    }
    /** Рамка экрана, доля блока. */
    public static final float BEZEL = 1 / 16f;

    /** Блоков экрана на блок мира: половина короткой стороны карты - на половину стороны масштаба. */
    public static double mapK(int w, int h, double halfSide) {
        return (Math.min(w, h) / 2.0 - BEZEL) / Math.max(1, halfSide);
    }

    /**
     * Видна ли на карте точка с экранными смещениями (u вправо, v вверх, блоков мира) от центра монитора.
     */
    public static boolean onMap(int w, int h, double halfSide, double u, double v) {
        double k = mapK(w, h, halfSide);
        return Math.abs(u * k) <= w / 2.0 - BEZEL && Math.abs(v * k) <= h / 2.0 - BEZEL;
    }
    private static final int MAX_BLIPS = 64;

    public static final int FLAG_ENGAGED = 1, FLAG_DESIGNATED = 2, FLAG_SHIP = 4, FLAG_FRIENDLY = 8, FLAG_INACTIVE = 16,
            FLAG_HOSTILE = 32, FLAG_EXCLUDED = 64;

    /** Фильтры показа (биты маски show). */
    public enum Show { PLAYERS, HOSTILE, PASSIVE, SHIPS, CONTRAPTIONS, PROJECTILES, OTHER, FRIENDLY, INACTIVE, TRANSPONDERS,
        RADARS, RADAR_ZONES;
        public final int bit = 1 << ordinal();
        public static final int ALL = (1 << values().length) - 1;
    }

    /** Отметка на экране: смещение от центра экрана в мире, тип, флаги, id и имя цели, скорость м/с. */
    public record Blip(float dx, float dz, float dy, int kind, int flags, String id, String name, float speed, String tag) {
        /** Категория значка (TargetCategory). */
        public int category() {
            Track.Kind[] kinds = Track.Kind.values();
            Track.Kind k = kind >= 0 && kind < kinds.length ? kinds[kind] : Track.Kind.EXTERNAL;
            return dev.radarfcs.core.TargetCategory.of(k, (flags & FLAG_HOSTILE) != 0).ordinal();
        }
    }

    /** Наземный транспондер на экране: смещение, имя, свой ли (по правилу вычислителя сети). */
    public record Mark(float dx, float dz, String name, boolean friendly) {}

    /** Подписи (имена с транспондеров): 0 - при наведении курсора, 1 - всегда. */
    public static final int LABELS_HOVER = 0, LABELS_ALWAYS = 1;

    /** Сенсор монитора берёт все типы целей (что показывать - решают фильтры). */
    private static final EngageSettings SHOW_ALL = showAllSettings();

    private static EngageSettings showAllSettings() {
        EngageSettings s = new EngageSettings();
        java.util.Arrays.fill(s.enabled, true);
        s.hostileOnly = false;
        s.threat = EngageSettings.Threat.ALL;                     // все чужие снаряды - активные отметки
        return s;
    }

    /** Радар канала на экране. */
    /**
     * Радар сети на экране: смещение, его луч - мировой курс angle (градусы, 0 - на юг, 90 - на восток; NaN -
     * неизвестен), ширина fov, скорость поворота speed (градусов за тик) на игровой тик stamp, дальность range.
     */
    public record Site(float dx, float dz, int type, float angle, float fov, float speed, float range, long stamp, long pos) {
        /** Курс луча на момент time (вращающийся радар доворачивается между обновлениями монитора). */
        public float angleAt(double time) {
            return speed == 0 ? angle : (float) ((angle + speed * (time - stamp)) % 360.0);
        }
    }

    private record Target(UUID id, String name, double dx, double dz, double dist) {}

    @Nullable private UUID owner;
    private String ownerTeam = "";
    private int mapSide = DEFAULT_SIDE;
    private int show = Show.ALL;
    private int labels = LABELS_HOVER;
    private int[] icons = MonitorIcons.defaults();
    private List<Mark> marks = List.of();

    // мультиблок
    private BlockPos master;
    private int width = 1, height = 1;

    // картинка (у ведущего)
    private float displayRange = DEFAULT_SIDE / 2f;
    private Vec3 center = Vec3.ZERO;
    private int radarCount;
    /** Электричество (FE): каждый блок стены принимает его, ведущий забирает потребление всей стены. */
    private final EnergyBuffer energy = new EnergyBuffer(32, this::setChanged);
    private boolean powered = true;
    private List<Blip> blips = List.of();
    private List<Site> sites = List.of();
    private List<Target> targets = List.of();
    private CompoundTag lastSent = new CompoundTag();
    private long lastCluster = Long.MIN_VALUE / 2;
    /** Сопровождение целей с радаров на линках (как у вычислителя). */
    private final TargetSensor sensor = new TargetSensor();
    /** Цели под огнём вычислителей на линках: id -> до какого тика. */
    private final Map<UUID, Long> engaged = new HashMap<>();
    /** Цель, назначенная на этом экране (для вычислителей на линках). */
    @Nullable private UUID localTarget;
    private long localUntil, localSince;
    /**
     * Цели, исключённые с этого экрана (нажатие колеса мыши): вычислители сети по ним не стреляют. Исключение
     * снимается повторным нажатием или само, когда цель пропадает с радаров (вышла из зоны - при возвращении огонь снова).
     */
    private final Set<UUID> excluded = new java.util.HashSet<>();
    /** Назначенная цель для клиента (подпись на экране). */
    private String designatedName = "";

    public RadarMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.RADAR_MONITOR_BE.get(), pos, state);
        master = pos;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RadarMonitorBlockEntity be) {
        if (level instanceof ServerLevel sl) be.tick(sl);
    }

    private void tick(ServerLevel level) {
        long now = level.getGameTime();
        energy.fit(FcsConfig.safe(FcsConfig.MONITOR_USAGE));
        if (isMaster()) powerWall(level);
        if (now % 10 != Math.floorMod(worldPosition.hashCode(), 10)) return;
        boolean changed = false;
        if (now - lastCluster >= 20) {
            lastCluster = now;
            changed = updateCluster(level);
        }
        if (isMaster()) refresh(level, now);
        else {
            blips = List.of();
            sites = List.of();
        }
        CompoundTag sent = clientData();
        if (changed || !sent.equals(lastSent)) {
            lastSent = sent;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Ведущий забирает потребление всей стены (за каждый блок) из общего запаса её блоков. */
    private void powerWall(ServerLevel level) {
        if (!EnergyBuffer.required()) {
            powered = true;
            return;
        }
        int usage = FcsConfig.safe(FcsConfig.MONITOR_USAGE);
        List<RadarMonitorBlockEntity> wall = new ArrayList<>();
        for (BlockPos p : wallPositions()) {
            if (level.getBlockEntity(p) instanceof RadarMonitorBlockEntity m) wall.add(m);
        }
        long need = (long) usage * Math.max(1, wall.size()), have = 0;
        for (RadarMonitorBlockEntity m : wall) have += m.energy.getEnergyStored();
        boolean p = have >= need;
        if (p) {
            long left = need;
            for (RadarMonitorBlockEntity m : wall) left -= m.energy.drain((int) Math.min(Integer.MAX_VALUE, left));
        }
        powered = p;
    }

    /** Есть ли питание у экрана (на клиенте - присланное сервером). */
    public boolean powered() {
        return master().powered;
    }

    public EnergyBuffer energy() {
        return energy;
    }

    public Direction facing() {
        return getBlockState().getValue(RadarMonitorBlock.FACING);
    }

    public boolean isMaster() {
        return master.equals(worldPosition);
    }

    public int width() { return width; }

    public int height() { return height; }

    public float displayRange() { return displayRange; }

    public int radarCount() { return radarCount; }

    public List<Blip> blips() { return blips; }

    public List<Site> sites() { return sites; }

    public List<Mark> marks() { return marks; }

    public int labels() { return master().labels; }

    /** Значок типа (MonitorIcons, упакован). */
    public int icon(int category) {
        int[] ic = master().icons;
        return category >= 0 && category < ic.length ? ic[category] : MonitorIcons.defaults()[0];
    }


    /** Сторона карты, блоков. */
    public int mapSide() { return mapSide; }

    public int show() { return show; }

    /** Мировой центр экрана (на клиенте - присланный сервером). */
    public Vec3 center() { return center; }

    public String designatedName() { return designatedName; }

    // ------------------------------------------------------------ мультиблок

    private boolean isMonitor(Level level, BlockPos p, Direction f) {
        BlockState s = level.getBlockState(p);
        return s.getBlock() instanceof RadarMonitorBlock && s.getValue(RadarMonitorBlock.FACING) == f;
    }

    /** Ведущий блок и размер экрана; true - если что-то изменилось. */
    private boolean updateCluster(Level level) {
        Direction f = facing();
        Direction left = f.getClockWise(), right = f.getCounterClockWise();
        int max = FcsConfig.MONITOR_MAX_SIZE.get();
        BlockPos m = worldPosition;
        for (int i = 0; i < 2 * max; i++) {
            if (isMonitor(level, m.relative(left), f)) m = m.relative(left);
            else if (isMonitor(level, m.below(), f)) m = m.below();
            else break;
        }
        int w = 1;
        while (w < max && isMonitor(level, m.relative(right, w), f)) w++;
        int h = 1;
        rows:
        while (h < max) {
            for (int x = 0; x < w; x++) if (!isMonitor(level, m.relative(right, x).above(h), f)) break rows;
            h++;
        }
        // блок вне прямоугольника ведущего - отдельный экран
        int ox = offsetAlong(m, right), oy = worldPosition.getY() - m.getY();
        if (ox < 0 || ox >= w || oy < 0 || oy >= h) {
            m = worldPosition;
            w = 1;
            h = 1;
        }
        if (!m.equals(worldPosition)) {
            w = 1;
            h = 1;
        }
        boolean changed = !m.equals(master) || w != width || h != height;
        master = m;
        width = w;
        height = h;
        return changed;
    }

    private int offsetAlong(BlockPos from, Direction dir) {
        BlockPos d = worldPosition.subtract(from);
        return d.getX() * dir.getStepX() + d.getZ() * dir.getStepZ();
    }

    /** Ведущий блок экрана (у него картинка и назначение цели). */
    public RadarMonitorBlockEntity master() {
        RadarMonitorBlockEntity m = masterBe();
        return m == null ? this : m;
    }

    /** Цель, назначенная на этом экране, или null. */
    @Nullable
    public UUID localDesignation(long now) {
        RadarMonitorBlockEntity m = master();
        return m.powered && m.localTarget != null && m.localUntil >= now ? m.localTarget : null;
    }

    /** Цели, исключённые на этом экране (пусто, если экран без питания). */
    public Set<UUID> exclusions() {
        RadarMonitorBlockEntity m = master();
        return m.powered ? Set.copyOf(m.excluded) : Set.of();
    }

    /** Вычислитель на линке ведёт огонь по цели (отметка на экране). */
    public void markEngaged(UUID target, long until) {
        master().engaged.put(target, until);
    }

    /** Блоки стены экрана (линк может стоять на любом). */
    private List<BlockPos> wallPositions() {
        Direction right = facing().getCounterClockWise();
        List<BlockPos> out = new ArrayList<>();
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) out.add(worldPosition.relative(right, x).above(y));
        return out;
    }

    @Nullable
    private RadarMonitorBlockEntity masterBe() {
        if (isMaster() || level == null) return this;
        return level.getBlockEntity(master) instanceof RadarMonitorBlockEntity m ? m : this;
    }

    /** Мировой центр экрана. */
    private Vec3 screenCenter(Level level) {
        Direction right = facing().getCounterClockWise();
        Vec3 c = Vec3.atCenterOf(worldPosition).add(Vec3.atLowerCornerOf(right.getNormal()).scale((width - 1) / 2.0))
                .add(0, (height - 1) / 2.0, 0);
        return SableCompat.toWorld(level, c);
    }

    // ------------------------------------------------------------- картинка

    private void refresh(ServerLevel level, long now) {
        Vec3 center = screenCenter(level);
        if (!powered) {                                                    // без питания экран погашен
            blips = List.of();
            marks = List.of();
            sites = List.of();
            targets = List.of();
            designatedName = "";
            return;
        }
        // радары Create Radars в сети любого блока экрана; правило «свой-чужой» - первого вычислителя сети
        List<BlockEntity> radars = new ArrayList<>();
        IffRule iff = null;
        for (BlockPos p : wallPositions()) {
            for (BlockPos q : LinkRegistry.linkedTo(level, p)) {
                if (!level.isLoaded(q)) continue;
                BlockEntity be = level.getBlockEntity(q);
                if (be != null && !be.isRemoved() && RadarLink.isRadar(be) && RadarLink.isRunning(be) && !radars.contains(be)) {
                    radars.add(be);
                }
                if (iff == null && be instanceof FireControlBlockEntity fcs) iff = fcs.effectiveSettings().iffRule();
            }
        }
        if (iff == null) iff = IffRule.DEFAULT;
        radarCount = radars.size();
        List<Site> siteList = new ArrayList<>();
        for (BlockEntity r : radars) {
            Vec3 o = SableCompat.toWorld(level, Vec3.atCenterOf(RadarLink.worldPos(r)));
            float speed = RadarLink.sweepSpeed(r);
            siteList.add(new Site((float) (o.x - center.x), (float) (o.z - center.z), 0, RadarLink.angle(r),
                    RadarLink.fov(r, speed != 0), speed, RadarLink.range(r), speed != 0 ? now : 0, r.getBlockPos().asLong()));
        }
        UUID ship = SableCompat.containingId(level, worldPosition);
        Vec origin = new Vec(center.x, center.y, center.z);
        this.center = center;
        TargetSensor.Context ctx = new TargetSensor.Context(owner, ownerTeam, iff, ship == null ? Set.of() : Set.of(ship), origin);
        UUID designated = localDesignation(now);
        sensor.sense(level, radars, ctx, now, SHOW_ALL, t -> false, designated == null ? null : designated.toString());
        // назначенная цель пропала (уничтожена, ушла) - назначение снимается
        if (designated != null && sensor.get(designated.toString()) == null && now - localSince > 40) {
            localTarget = null;
            designated = null;
        }
        // исключение снимается, когда цель пропала с радаров (вышла из зоны, уничтожена)
        excluded.removeIf(id -> sensor.get(id.toString()) == null);
        designatedName = "";
        engaged.values().removeIf(until -> until < now);
        List<Target> tl = new ArrayList<>();
        List<Blip> bl = new ArrayList<>();
        for (Track t : sensor.all()) {
            UUID id;
            try {
                id = UUID.fromString(t.id);
            } catch (IllegalArgumentException e) {
                continue;
            }
            boolean des = id.equals(designated);
            if (des) designatedName = t.name;
            if (!des && !shows(show, t)) continue;
            double dx = t.pos.x() - center.x, dz = t.pos.z() - center.z;
            double dist = Math.hypot(dx, dz);
            if (!t.friendly) tl.add(new Target(id, t.name, dx, dz, dist));
            int flags = (engaged.containsKey(id) ? FLAG_ENGAGED : 0) | (des ? FLAG_DESIGNATED : 0)
                    | (t.kind == Track.Kind.SHIP ? FLAG_SHIP : 0) | (t.friendly ? FLAG_FRIENDLY : 0)
                    | (!t.active && !t.friendly ? FLAG_INACTIVE : 0) | (isThreat(t) ? FLAG_HOSTILE : 0)
                    | (excluded.contains(id) ? FLAG_EXCLUDED : 0);
            bl.add(new Blip((float) dx, (float) dz, (float) (t.pos.y() - center.y), t.kind.ordinal(), flags, t.id,
                    t.name == null ? "?" : t.name, (float) (t.speedPerTick() * 20), t.tagName == null ? "" : t.tagName));
        }
        // наземные транспондеры (метки с именами); транспондеры кораблей подписывают отметку своего корабля
        List<Mark> ml = new ArrayList<>();
        if ((show & Show.TRANSPONDERS.bit) != 0) {
            for (IffRegistry.Info info : IffRegistry.infos(level)) {
                if (info.ship() != null) continue;
                double dx = info.pos().x - center.x, dz = info.pos().z - center.z;
                ml.add(new Mark((float) dx, (float) dz, info.name(), iff.accepts(info.code())));
            }
        }
        // карта: сторона - выбранный масштаб; на экран - только то, что на карте
        displayRange = mapSide / 2f;
        tl.removeIf(t -> !onScreen(t.dx(), t.dz()));
        ml.removeIf(m -> !onScreen(m.dx(), m.dz()));
        marks = ml.size() > MAX_BLIPS ? List.copyOf(ml.subList(0, MAX_BLIPS)) : ml;
        bl.removeIf(b -> !onScreen(b.dx(), b.dz()));
        tl.sort(Comparator.comparingDouble(Target::dist));
        // назначенная и активные - первыми (на экран идёт не больше MAX_BLIPS)
        bl.sort(Comparator.<Blip>comparingInt(b -> (b.flags() & FLAG_DESIGNATED) != 0 ? 0
                        : (b.flags() & (FLAG_INACTIVE | FLAG_FRIENDLY)) == 0 ? 1 : 2)
                .thenComparingDouble(b -> Math.hypot(b.dx(), b.dz())));
        targets = tl;
        blips = bl.size() > MAX_BLIPS ? List.copyOf(bl.subList(0, MAX_BLIPS)) : bl;
        sites = siteList;
    }

    /** Вид цели для фильтра показа. */
    public static Show category(Track.Kind kind, boolean hostile) {
        return switch (kind) {
            case PLAYER -> Show.PLAYERS;
            case MOB -> hostile ? Show.HOSTILE : Show.PASSIVE;
            case SHIP -> Show.SHIPS;
            case CONTRAPTION -> Show.CONTRAPTIONS;
            case PROJECTILE -> Show.PROJECTILES;
            case EXTERNAL -> Show.OTHER;
        };
    }

    static boolean shows(int mask, Track t) {
        if ((mask & category(t.kind, t.hostile).bit) == 0) return false;
        if (t.friendly) return (mask & Show.FRIENDLY.bit) != 0;
        return t.active || (mask & Show.INACTIVE.bit) != 0;
    }

    /** Угроза (рисуется оранжевым): враждебные мобы, игроки, корабли, контрапшены, снаряды; мирные и прочее - голубым. */
    private static boolean isThreat(Track t) {
        return switch (t.kind) {
            case MOB -> t.hostile;
            case EXTERNAL -> false;
            default -> true;
        };
    }

    /** Видно ли на карте этого экрана мировое смещение (dx, dz) от центра монитора. */
    public boolean onScreen(double dx, double dz) {
        Direction f = facing(), right = f.getCounterClockWise();
        double u = dx * right.getStepX() + dz * right.getStepZ();
        double v = -(dx * f.getStepX() + dz * f.getStepZ());
        return onMap(width, height, displayRange, u, v);
    }

    /** Мировая точка на экране, где рисуется отметка цели в мировой точке world (для тестов и отладки). */
    public Vec3 screenPointFor(Vec3 world) {
        Direction f = facing(), right = f.getCounterClockWise();
        Vec3 center = screenCenter(level);
        double dx = world.x - center.x, dz = world.z - center.z;
        double u = dx * right.getStepX() + dz * right.getStepZ();
        double v = -(dx * f.getStepX() + dz * f.getStepZ());
        double k = mapK(width, height, displayRange);
        double x = (width - 1) / 2.0 + u * k, y = (height - 1) / 2.0 + v * k;
        return Vec3.atCenterOf(worldPosition).add(Vec3.atLowerCornerOf(right.getNormal()).scale(x)).add(0, y, 0)
                .add(Vec3.atLowerCornerOf(f.getNormal()).scale(0.5));
    }

    // ------------------------------------------------------------ интерфейс

    public void setOwner(Player p) {
        owner = p.getUUID();
        Team t = p.getTeam();
        ownerTeam = t != null ? t.getName() : "";
        setChanged();
    }


    /** Назначить цель по id (экран монитора); null - снять назначение. */
    public void designate(@Nullable UUID id, @Nullable Player p) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        Target t = id == null ? null : m.targets.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (t != null) {
            m.excluded.remove(t.id());                                     // назначенная цель снова не исключена
            m.setDesignation(t, now, p);
        }
        else {
            m.localTarget = null;
            if (p != null) p.displayClientMessage(Component.translatable("radar_fcs.msg.designation_cleared").withStyle(ChatFormatting.GRAY), true);
        }
        m.refresh(sl, now);
        m.pushToClients(sl);
    }

    private void setDesignation(Target t, long now, @Nullable Player p) {
        localTarget = t.id();
        localSince = now;
        int ticks = FcsConfig.DESIGNATION_TICKS.get();
        localUntil = ticks <= 0 ? Long.MAX_VALUE : now + ticks;
        if (p != null) p.displayClientMessage(Component.translatable("radar_fcs.msg.designated", t.name(),
                String.format(Locale.ROOT, "%.0f", t.dist())).withStyle(ChatFormatting.YELLOW), true);
    }

    /**
     * Исключить цель из огня вычислителей сети или вернуть её (повторное нажатие колеса мыши). Исключённая цель
     * перестаёт быть назначенной.
     */
    public void toggleExclusion(UUID id, @Nullable Player p) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl) || !m.powered) return;
        long now = sl.getGameTime();
        Target t = m.targets.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (m.excluded.remove(id)) {
            if (p != null) p.displayClientMessage(Component.translatable("radar_fcs.msg.exclusion_cleared",
                    t == null ? "?" : t.name()).withStyle(ChatFormatting.GREEN), true);
        } else if (t != null) {
            m.excluded.add(id);
            if (id.equals(m.localTarget)) m.localTarget = null;
            if (p != null) p.displayClientMessage(Component.translatable("radar_fcs.msg.excluded", t.name())
                    .withStyle(ChatFormatting.GOLD), true);
        }
        m.refresh(sl, now);
        m.pushToClients(sl);
    }

    /** Сторона карты с экрана монитора (блоков). */
    public void setSide(int side) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl)) return;
        m.mapSide = clampSide(side);
        m.setChanged();
        m.refresh(sl, sl.getGameTime());
        m.pushToClients(sl);
    }

    /** Значок типа с экрана монитора. */
    public void setIcon(int category, int packed) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl) || category < 0 || category >= MonitorIcons.COUNT) return;
        m.icons[category] = MonitorIcons.pack(MonitorIcons.shape(packed), MonitorIcons.color(packed), MonitorIcons.size(packed));
        m.setChanged();
        m.pushToClients(sl);
    }

    /** Подписи (всегда / при наведении) с экрана монитора. */
    public void setLabels(int mode) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl)) return;
        m.labels = mode == LABELS_ALWAYS ? LABELS_ALWAYS : LABELS_HOVER;
        m.setChanged();
        m.pushToClients(sl);
    }

    /** Фильтры показа с экрана монитора. */
    public void setShow(int mask) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl)) return;
        m.show = mask & Show.ALL;
        m.setChanged();
        m.refresh(sl, sl.getGameTime());
        m.pushToClients(sl);
    }

    private void pushToClients(ServerLevel sl) {
        lastSent = clientData();
        sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Клик по экрану: назначить ближайшую к точке клика цель или снять назначение. */
    public void click(Player p, BlockHitResult hit) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl)) return;
        if (!m.powered) {
            p.displayClientMessage(Component.translatable("radar_fcs.monitor.no_energy").withStyle(ChatFormatting.RED), true);
            return;
        }
        Direction f = m.facing();
        if (hit.getDirection() != f) return;
        Direction right = f.getCounterClockWise();
        Vec3 rel = hit.getLocation().subtract(Vec3.atCenterOf(m.worldPosition));
        double x = rel.x * right.getStepX() + rel.z * right.getStepZ();
        double y = rel.y;
        double k = mapK(m.width, m.height, m.displayRange);
        double u = (x - (m.width - 1) / 2.0) / k, v = (y - (m.height - 1) / 2.0) / k;
        // вверх на экране - от зрителя (против FACING), вправо - вправо от зрителя
        double wx = right.getStepX() * u - f.getStepX() * v;
        double wz = right.getStepZ() * u - f.getStepZ() * v;
        Target best = null;
        double bestD = Math.max(4.0, m.displayRange * 0.1);
        for (Target t : m.targets) {
            double d = Math.hypot(t.dx() - wx, t.dz() - wz);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        long now = sl.getGameTime();
        if (best != null) {
            // назначение - вычислителям сети этого экрана (исключение с цели снимается)
            m.excluded.remove(best.id());
            m.setDesignation(best, now, p);
        } else {
            m.localTarget = null;
            p.displayClientMessage(Component.translatable("radar_fcs.msg.designation_cleared").withStyle(ChatFormatting.GRAY), true);
        }
        m.refresh(sl, now);
        m.pushToClients(sl);
    }

    // ------------------------------------------------------------ данные

    private CompoundTag clientData() {
        CompoundTag t = new CompoundTag();
        t.putLong("Master", master.asLong());
        t.putInt("W", width);
        t.putInt("H", height);
        t.putInt("Side", mapSide);
        t.putInt("Show", show);
        t.putBoolean("Power", powered);
        if (isMaster()) {
            t.putFloat("Range", displayRange);
            t.putDouble("CX", center.x);
            t.putDouble("CY", center.y);
            t.putDouble("CZ", center.z);
            t.putString("Des", designatedName);
            t.putInt("Radars", radarCount);
            ListTag bl = new ListTag();
            for (Blip b : blips) {
                CompoundTag c = new CompoundTag();
                c.putFloat("x", b.dx());
                c.putFloat("z", b.dz());
                c.putFloat("y", b.dy());
                c.putByte("k", (byte) b.kind());
                c.putByte("f", (byte) b.flags());
                c.putString("i", b.id());
                c.putString("n", b.name());
                c.putFloat("v", b.speed());
                if (!b.tag().isEmpty()) c.putString("t", b.tag());
                bl.add(c);
            }
            t.put("Blips", bl);
            ListTag sl = new ListTag();
            for (Site s : sites) {
                CompoundTag c = new CompoundTag();
                c.putFloat("x", s.dx());
                c.putFloat("z", s.dz());
                c.putByte("k", (byte) s.type());
                c.putLong("p", s.pos());
                if (Float.isFinite(s.angle())) {
                    c.putFloat("a", s.angle());
                    c.putFloat("w", s.fov());
                    c.putFloat("r", s.range());
                    if (s.speed() != 0) {
                        c.putFloat("s", s.speed());
                        c.putLong("t", s.stamp());
                    }
                }
                sl.add(c);
            }
            t.put("Sites", sl);
            ListTag mk = new ListTag();
            for (Mark m : marks) {
                CompoundTag c = new CompoundTag();
                c.putFloat("x", m.dx());
                c.putFloat("z", m.dz());
                c.putString("n", m.name());
                c.putBoolean("f", m.friendly());
                mk.add(c);
            }
            t.put("Marks", mk);
            t.putInt("Labels", labels);
            t.putIntArray("Icons", icons);
        }
        return t;
    }

    private void readClientData(CompoundTag t) {
        master = BlockPos.of(t.getLong("Master"));
        width = Math.max(1, t.getInt("W"));
        height = Math.max(1, t.getInt("H"));
        if (t.contains("Side")) mapSide = clampSide(t.getInt("Side"));
        displayRange = t.contains("Range") ? t.getFloat("Range") : mapSide / 2f;
        center = new Vec3(t.getDouble("CX"), t.getDouble("CY"), t.getDouble("CZ"));
        designatedName = t.getString("Des");
        show = t.contains("Show") ? t.getInt("Show") : Show.ALL;
        powered = !t.contains("Power") || t.getBoolean("Power");
        radarCount = t.getInt("Radars");
        List<Blip> bl = new ArrayList<>();
        for (Tag tag : t.getList("Blips", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) tag;
            bl.add(new Blip(c.getFloat("x"), c.getFloat("z"), c.getFloat("y"), c.getByte("k"), c.getByte("f") & 0xFF,
                    c.getString("i"), c.getString("n"), c.getFloat("v"), c.getString("t")));
        }
        blips = bl;
        List<Site> sl = new ArrayList<>();
        for (Tag tag : t.getList("Sites", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) tag;
            sl.add(new Site(c.getFloat("x"), c.getFloat("z"), c.getByte("k"), c.contains("a") ? c.getFloat("a") : Float.NaN,
                    c.getFloat("w"), c.getFloat("s"), c.getFloat("r"), c.getLong("t"), c.getLong("p")));
        }
        sites = sl;
        List<Mark> mk = new ArrayList<>();
        for (Tag tag : t.getList("Marks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) tag;
            mk.add(new Mark(c.getFloat("x"), c.getFloat("z"), c.getString("n"), c.getBoolean("f")));
        }
        marks = mk;
        if (t.contains("Labels")) labels = t.getInt("Labels");
        if (t.contains("Icons")) icons = MonitorIcons.sanitize(t.getIntArray("Icons"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("MapSide", mapSide);
        tag.putInt("Show", show);
        tag.putBoolean("ShowV2", true);
        tag.putBoolean("ShowV3", true);
        tag.putInt("Labels", labels);
        tag.putIntArray("Icons", icons);
        energy.save(tag);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("OwnerTeam", ownerTeam);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // карта: сторона в блоках (1.3); в 1.2.0 - индекс масштаба; у старых мониторов (круг, «авто») - по умолчанию
        mapSide = tag.contains("MapSide") ? clampSide(tag.getInt("MapSide"))
                : tag.contains("MapScale") ? OLD_SCALES[Math.floorMod(tag.getInt("MapScale"), OLD_SCALES.length)] : DEFAULT_SIDE;
        show = tag.contains("Show") ? tag.getInt("Show") & Show.ALL : Show.ALL;
        if (tag.contains("Show") && !tag.getBoolean("ShowV2")) show |= Show.TRANSPONDERS.bit;   // фильтр появился в 0.11
        if (tag.contains("Show") && !tag.getBoolean("ShowV3")) show |= Show.RADARS.bit | Show.RADAR_ZONES.bit;   // 1.2
        labels = tag.getInt("Labels") == LABELS_ALWAYS ? LABELS_ALWAYS : LABELS_HOVER;
        icons = MonitorIcons.sanitize(tag.contains("Icons") ? tag.getIntArray("Icons") : null);
        energy.load(tag);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerTeam = tag.getString("OwnerTeam");
        if (tag.contains("Screen", Tag.TAG_COMPOUND)) readClientData(tag.getCompound("Screen"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        tag.put("Screen", clientData());
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
