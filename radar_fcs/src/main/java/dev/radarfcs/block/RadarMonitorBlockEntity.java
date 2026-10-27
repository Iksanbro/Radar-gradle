package dev.radarfcs.block;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.Vec;
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
import net.minecraft.world.item.DyeColor;
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
    /** Масштабы экрана, блоков; 0 - по дальности радаров. */
    public static final int[] SCALES = {0, 32, 64, 128, 256, 512, 1024};
    /** Доля полуширины экрана под круг развёртки. */
    public static final double PPI_FRACTION = 0.9;
    private static final int MAX_BLIPS = 64;

    public static final int FLAG_ENGAGED = 1, FLAG_DESIGNATED = 2, FLAG_SHIP = 4, FLAG_FRIENDLY = 8, FLAG_INACTIVE = 16,
            FLAG_HOSTILE = 32;

    /** Фильтры показа (биты маски show). */
    public enum Show { PLAYERS, HOSTILE, PASSIVE, SHIPS, CONTRAPTIONS, PROJECTILES, OTHER, FRIENDLY, INACTIVE;
        public final int bit = 1 << ordinal();
        public static final int ALL = (1 << values().length) - 1;
    }

    /** Отметка на экране: смещение от центра экрана в мире, тип, флаги, id и имя цели, скорость м/с. */
    public record Blip(float dx, float dz, float dy, int kind, int flags, String id, String name, float speed) {}

    /** Сенсор монитора берёт все типы целей (что показывать - решают фильтры). */
    private static final EngageSettings SHOW_ALL = showAllSettings();

    private static EngageSettings showAllSettings() {
        EngageSettings s = new EngageSettings();
        java.util.Arrays.fill(s.enabled, true);
        s.hostileOnly = false;
        return s;
    }

    /** Радар канала на экране. */
    public record Site(float dx, float dz, int type) {}

    private record Target(UUID id, String name, double dx, double dz, double dist) {}

    private DyeColor channel = DyeColor.WHITE;
    @Nullable private UUID owner;
    private String ownerTeam = "";
    private int scaleIndex;
    private int show = Show.ALL;

    // мультиблок
    private BlockPos master;
    private int width = 1, height = 1;

    // картинка (у ведущего)
    private float displayRange = 64;
    private Vec3 center = Vec3.ZERO;
    private int radarCount;
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

    public DyeColor channel() { return channel; }

    public int scaleIndex() { return scaleIndex; }

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
        return m.localTarget != null && m.localUntil >= now ? m.localTarget : null;
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
        // радары Create Radars на линках с любым блоком экрана
        List<BlockEntity> radars = new ArrayList<>();
        for (BlockPos p : wallPositions()) {
            for (BlockPos q : LinkRegistry.linkedTo(level, p)) {
                if (!level.isLoaded(q)) continue;
                BlockEntity be = level.getBlockEntity(q);
                if (be != null && !be.isRemoved() && RadarLink.isRadar(be) && RadarLink.isRunning(be) && !radars.contains(be)) {
                    radars.add(be);
                }
            }
        }
        radarCount = radars.size();
        double reach = 0;
        List<Site> siteList = new ArrayList<>();
        for (BlockEntity r : radars) {
            Vec3 o = SableCompat.toWorld(level, Vec3.atCenterOf(RadarLink.worldPos(r)));
            reach = Math.max(reach, o.subtract(center).horizontalDistance() + RadarLink.range(r));
            siteList.add(new Site((float) (o.x - center.x), (float) (o.z - center.z), 0));
        }
        UUID ship = SableCompat.containingId(level, worldPosition);
        Vec origin = new Vec(center.x, center.y, center.z);
        this.center = center;
        TargetSensor.Context ctx = new TargetSensor.Context(owner, ownerTeam, channel.getId(), ship == null ? Set.of() : Set.of(ship), origin);
        UUID designated = localDesignation(now);
        sensor.sense(level, radars, ctx, now, SHOW_ALL, t -> false, designated == null ? null : designated.toString());
        // назначенная цель пропала (уничтожена, ушла) - назначение снимается
        if (designated != null && sensor.get(designated.toString()) == null && now - localSince > 40) {
            localTarget = null;
            designated = null;
        }
        designatedName = "";
        engaged.values().removeIf(until -> until < now);
        List<Target> tl = new ArrayList<>();
        List<Blip> bl = new ArrayList<>();
        double farthest = 0;
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
            if (t.active || des) farthest = Math.max(farthest, dist);
            if (!t.friendly) tl.add(new Target(id, t.name, dx, dz, dist));
            int flags = (engaged.containsKey(id) ? FLAG_ENGAGED : 0) | (des ? FLAG_DESIGNATED : 0)
                    | (t.kind == Track.Kind.SHIP ? FLAG_SHIP : 0) | (t.friendly ? FLAG_FRIENDLY : 0)
                    | (!t.active && !t.friendly ? FLAG_INACTIVE : 0) | (isThreat(t) ? FLAG_HOSTILE : 0);
            bl.add(new Blip((float) dx, (float) dz, (float) (t.pos.y() - center.y), t.kind.ordinal(), flags, t.id,
                    t.name == null ? "?" : t.name, (float) (t.speedPerTick() * 20)));
        }
        // авто: чтобы поместились все цели, но не дальше, чем видят радары
        double coverage = niceRange(reach);
        displayRange = (float) (scaleIndex != 0 ? SCALES[scaleIndex]
                : farthest > 0 ? Math.min(coverage, niceRange(Math.max(48, farthest * 1.25))) : coverage);
        tl.removeIf(t -> t.dist() > displayRange);
        bl.removeIf(b -> Math.hypot(b.dx(), b.dz()) > displayRange);
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

    /** Мировая точка на экране, где рисуется отметка цели в мировой точке world (для тестов и отладки). */
    public Vec3 screenPointFor(Vec3 world) {
        Direction f = facing(), right = f.getCounterClockWise();
        Vec3 center = screenCenter(level);
        double dx = world.x - center.x, dz = world.z - center.z;
        double u = dx * right.getStepX() + dz * right.getStepZ();
        double v = -(dx * f.getStepX() + dz * f.getStepZ());
        double k = Math.min(width, height) / 2.0 * PPI_FRACTION / Math.max(1, displayRange);
        double x = (width - 1) / 2.0 + u * k, y = (height - 1) / 2.0 + v * k;
        return Vec3.atCenterOf(worldPosition).add(Vec3.atLowerCornerOf(right.getNormal()).scale(x)).add(0, y, 0)
                .add(Vec3.atLowerCornerOf(f.getNormal()).scale(0.5));
    }

    static double niceRange(double reach) {
        double[] steps = {32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024};
        for (double s : steps) if (reach <= s) return s;
        return 1024;
    }

    // ------------------------------------------------------------ интерфейс

    public void setOwner(Player p) {
        owner = p.getUUID();
        Team t = p.getTeam();
        ownerTeam = t != null ? t.getName() : "";
        setChanged();
    }

    public void setChannel(DyeColor c, Player p) {
        RadarMonitorBlockEntity m = masterBe();
        for (RadarMonitorBlockEntity be : List.of(this, m == null ? this : m)) {
            be.channel = c;
            be.setChanged();
        }
        p.displayClientMessage(Component.translatable("radar_fcs.msg.channel",
                Component.translatable("color.minecraft." + c.getName())), true);
    }


    /** Назначить цель по id (экран монитора); null - снять назначение. */
    public void designate(@Nullable UUID id, @Nullable Player p) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        Target t = id == null ? null : m.targets.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (t != null) m.setDesignation(t, now, p);
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

    /** Масштаб с экрана монитора. */
    public void setScale(int index) {
        RadarMonitorBlockEntity m = masterBe();
        if (m == null || !(level instanceof ServerLevel sl)) return;
        m.scaleIndex = Math.floorMod(index, SCALES.length);
        m.setChanged();
        m.refresh(sl, sl.getGameTime());
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
        Direction f = m.facing();
        if (hit.getDirection() != f) return;
        Direction right = f.getCounterClockWise();
        Vec3 rel = hit.getLocation().subtract(Vec3.atCenterOf(m.worldPosition));
        double x = rel.x * right.getStepX() + rel.z * right.getStepZ();
        double y = rel.y;
        double radius = Math.min(m.width, m.height) / 2.0 * PPI_FRACTION;
        double u = (x - (m.width - 1) / 2.0) / radius, v = (y - (m.height - 1) / 2.0) / radius;
        // вверх на экране - от зрителя (против FACING), вправо - вправо от зрителя
        double wx = (right.getStepX() * u - f.getStepX() * v) * m.displayRange;
        double wz = (right.getStepZ() * u - f.getStepZ() * v) * m.displayRange;
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
            // назначение - вычислителям сети этого экрана
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
        t.putInt("Channel", channel.getId());
        t.putInt("Scale", scaleIndex);
        t.putInt("Show", show);
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
                bl.add(c);
            }
            t.put("Blips", bl);
            ListTag sl = new ListTag();
            for (Site s : sites) {
                CompoundTag c = new CompoundTag();
                c.putFloat("x", s.dx());
                c.putFloat("z", s.dz());
                c.putByte("k", (byte) s.type());
                sl.add(c);
            }
            t.put("Sites", sl);
        }
        return t;
    }

    private void readClientData(CompoundTag t) {
        master = BlockPos.of(t.getLong("Master"));
        width = Math.max(1, t.getInt("W"));
        height = Math.max(1, t.getInt("H"));
        displayRange = t.contains("Range") ? t.getFloat("Range") : 64;
        center = new Vec3(t.getDouble("CX"), t.getDouble("CY"), t.getDouble("CZ"));
        designatedName = t.getString("Des");
        show = t.contains("Show") ? t.getInt("Show") : Show.ALL;
        radarCount = t.getInt("Radars");
        List<Blip> bl = new ArrayList<>();
        for (Tag tag : t.getList("Blips", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) tag;
            bl.add(new Blip(c.getFloat("x"), c.getFloat("z"), c.getFloat("y"), c.getByte("k"), c.getByte("f") & 0xFF,
                    c.getString("i"), c.getString("n"), c.getFloat("v")));
        }
        blips = bl;
        List<Site> sl = new ArrayList<>();
        for (Tag tag : t.getList("Sites", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) tag;
            sl.add(new Site(c.getFloat("x"), c.getFloat("z"), c.getByte("k")));
        }
        sites = sl;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Channel", channel.getId());
        tag.putInt("Scale", scaleIndex);
        tag.putInt("Show", show);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("OwnerTeam", ownerTeam);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        channel = DyeColor.byId(tag.getInt("Channel"));
        scaleIndex = Math.floorMod(tag.getInt("Scale"), SCALES.length);
        show = tag.contains("Show") ? tag.getInt("Show") & Show.ALL : Show.ALL;
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
