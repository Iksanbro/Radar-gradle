package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.compat.DockingPorts;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Docking;
import dev.radarfcs.core.Vec;
import dev.radarfcs.dock.DockingRegistry;
import dev.radarfcs.link.LinkRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Стыковочный контроллер: связан инструментальным пистолетом Flight Control с портом Create Simulated
 * (simulated:docking_connector) на той же постройке и входит в радарную сеть через линк. Роль «порт» - сообщает о
 * своём порте в {@link DockingRegistry} (номер, сети, положение) и выдвигает его, когда корабль, занявший порт,
 * подходит. Роль «штекер» - стоит на корабле, мост автопилота этого корабля ведёт штекер к порту с тем же номером и
 * командует выдвинуть порт корабля ({@link #commandExtend}). Сам блок класса Flight Control не касается.
 */
public class DockingControllerBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    /** Порт Simulated - не дальше стольких блоков от контроллера. */
    public static final double LINK_RADIUS = 16;

    /** Сводка для очков инженера и экрана. */
    public enum Status {
        NO_CONNECTOR, IDLE, RESERVED, EXTENDED, LOCKED;

        public String key() {
            return "radar_fcs.docking.status." + name().toLowerCase(Locale.ROOT);
        }
    }

    public Docking.Role role = Docking.Role.PORT;
    public int number = 1;
    /** Дистанция обхода, бл: точка выхода на прямую перед портом; ближе корабль не подходит, пока не выйдет на ось. */
    public double approach = Docking.APPROACH_DIST;
    /** Порты Simulated - смещения от контроллера. */
    private final List<BlockPos> connectorOffsets = new ArrayList<>();
    public static final int MAX_CONNECTORS = 8;

    // сервер
    private long extendUntil = Long.MIN_VALUE;
    private boolean forced, unloading, checked;
    private Set<UUID> networks = Set.of();
    private long firstTick = Long.MIN_VALUE;
    private long lastNetworks = Long.MIN_VALUE / 2, lastPut = Long.MIN_VALUE / 2, lastSync = Long.MIN_VALUE / 2;
    private Status status = Status.NO_CONNECTOR;
    private String lastSummary = "";

    public DockingControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ------------------------------------------------------------- связь с портами Simulated

    /** Порты Simulated контроллера (мир/плот). */
    public List<BlockPos> connectorPositions() {
        List<BlockPos> out = new ArrayList<>(connectorOffsets.size());
        for (BlockPos o : connectorOffsets) out.add(worldPosition.offset(o));
        return out;
    }

    /** Итог добавления порта инструментом. */
    public enum LinkResult { ADDED, REMOVED, FULL, FACING }

    /**
     * Добавить порт Simulated в точке p или убрать, если он уже связан (проверка расстояния и постройки -
     * {@link DockingPorts#linkable}). Все порты контроллера должны смотреть в одну сторону.
     */
    public LinkResult toggleConnector(BlockPos p) {
        BlockPos off = p.subtract(worldPosition);
        if (connectorOffsets.contains(off)) {
            unpower(List.of(p));
            connectorOffsets.remove(off);
            changed();
            return LinkResult.REMOVED;
        }
        if (connectorOffsets.size() >= MAX_CONNECTORS) return LinkResult.FULL;
        Direction f = connectorFacing();
        if (f != null && level != null && DockingPorts.facingAt(level, p) != f) return LinkResult.FACING;
        connectorOffsets.add(off.immutable());
        changed();
        return LinkResult.ADDED;
    }

    /** Снять связь со всеми портами (выдвинутые по команде вернуть к их сигналу). */
    public void clearConnectors() {
        unpower(connectorPositions());
        connectorOffsets.clear();
        forced = false;
        changed();
    }

    private void unpower(List<BlockPos> ps) {
        if (level == null || level.isClientSide || !forced) return;
        for (BlockPos c : ps) if (level.isLoaded(c)) DockingPorts.setPowered(level, c, false);
    }

    /** Порты на месте и смотрящие как первый из них (остальные не в счёт). */
    public List<BlockPos> validConnectors() {
        List<BlockPos> out = new ArrayList<>();
        if (level == null) return out;
        Direction first = null;
        for (BlockPos c : connectorPositions()) {
            Direction f = DockingPorts.facingAt(level, c);
            if (f == null) continue;
            if (first == null) first = f;
            if (f == first) out.add(c);
        }
        return out;
    }

    /** Направление портов (первого на месте), иначе null - портов нет. */
    @Nullable
    public Direction connectorFacing() {
        if (level == null) return null;
        for (BlockPos c : connectorPositions()) {
            Direction f = DockingPorts.facingAt(level, c);
            if (f != null) return f;
        }
        return null;
    }

    /** Концы портов в координатах постройки (плот). */
    public List<Vec3> localTips() {
        Direction f = connectorFacing();
        List<Vec3> out = new ArrayList<>();
        if (f == null) return out;
        for (BlockPos c : validConnectors()) out.add(DockingPorts.localTip(c, f));
        return out;
    }

    /** Середина концов портов (плот); null - портов нет. */
    @Nullable
    public Vec3 localTip() {
        List<Vec3> tips = localTips();
        if (tips.isEmpty()) return null;
        Vec3 sum = Vec3.ZERO;
        for (Vec3 t : tips) sum = sum.add(t);
        return sum.scale(1.0 / tips.size());
    }

    /** Сколько портов сцеплено. */
    public int lockedCount() {
        if (level == null) return 0;
        int n = 0;
        for (BlockPos c : validConnectors()) if (DockingPorts.isLocked(level, c)) n++;
        return n;
    }

    /** Хоть один порт сцеплен. */
    public boolean locked() {
        if (level == null) return false;
        for (BlockPos c : validConnectors()) if (DockingPorts.isLocked(level, c)) return true;
        return false;
    }

    /** Штекер: выдвигать порты корабля ещё пару тиков (мост повторяет команду каждый тик). */
    public void commandExtend(long now) {
        extendUntil = now + 2;
    }

    /** Ключ штекера для брони порта. */
    public long key() {
        return worldPosition.asLong();
    }

    // ------------------------------------------------------------- цикл

    public static void serverTick(Level level, BlockPos pos, BlockState st, DockingControllerBlockEntity be) {
        if (level instanceof ServerLevel sl) be.tick(sl);
    }

    private void tick(ServerLevel level) {
        long now = level.getGameTime();
        Direction facing = connectorFacing();
        boolean extend = false;
        boolean sync = now - lastSync >= 10;
        if (now % 20 == 0) dropBrokenConnectors(level);
        if (facing == null) {
            if (role == Docking.Role.PORT) DockingRegistry.remove(level, worldPosition);
            if (forced) for (BlockPos c : connectorPositions()) DockingPorts.unforce(level, c);
            forced = false;
            status = Status.NO_CONNECTOR;
        } else if (role == Docking.Role.PORT) {
            DockingRegistry.Reservation r = DockingRegistry.reservation(level, worldPosition, now);
            extend = r != null && r.extend();
            if (now - lastNetworks >= 20) {
                lastNetworks = now;
                networks = Set.copyOf(LinkRegistry.networksOf(level, worldPosition));
            }
            // занятый порт (корабль подходит) - положение каждый тик, иначе раз в полсекунды
            if (r != null || now - lastPut >= 10) {
                lastPut = now;
                DockingRegistry.PortInfo info = describe(level, facing);
                if (info != null) DockingRegistry.put(level, info);
            }
            // сцепку (рефлексия по каждому порту) - только для сводки, раз в полсекунды
            if (sync) status = locked() ? Status.LOCKED : extend ? Status.EXTENDED : r != null ? Status.RESERVED : Status.IDLE;
        } else {
            extend = now <= extendUntil;
            if (sync) status = locked() ? Status.LOCKED : extend ? Status.EXTENDED : Status.IDLE;
        }
        // через 2 с после загрузки: порт мог сохраниться выдвинутым по команде, а команды нет - вернуть ему его сигнал
        // (раньше нельзя: пристыкованный корабль расстыковался бы, пока мост не подтвердил стыковку)
        if (firstTick == Long.MIN_VALUE) firstTick = now;
        boolean check = !checked && now - firstTick >= 40;
        if (extend || forced || check) {
            boolean f = false;
            for (BlockPos c : connectorPositions()) f |= DockingPorts.setPowered(level, c, extend);
            forced = f;
        }
        if (check) checked = true;
        if (sync) {
            lastSync = now;
            String summary = status.name() + "|" + connectorOffsets.size();
            if (!summary.equals(lastSummary)) {
                lastSummary = summary;
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    /** Сломанные порты Simulated (чанк загружен, а порта нет) - убрать из связей. */
    private void dropBrokenConnectors(ServerLevel level) {
        boolean changed = false;
        for (java.util.Iterator<BlockPos> it = connectorOffsets.iterator(); it.hasNext(); ) {
            BlockPos p = worldPosition.offset(it.next());
            if (level.isLoaded(p) && !DockingPorts.isConnector(level, p)) {
                DockingPorts.unforce(level, p);
                it.remove();
                changed = true;
            }
        }
        if (changed) changed();
    }

    /** Порт для реестра: мировая середина концов, направление, расстановка и скорость (у порта на корабле - через его позу). */
    @Nullable
    private DockingRegistry.PortInfo describe(ServerLevel level, Direction facing) {
        List<Vec3> tips = localTips();
        if (tips.isEmpty()) return null;
        var ship = SableCompat.containing(level, worldPosition);
        Vec3 tip = localTip();
        Vec3 w = SableCompat.toWorld(level, tip);
        Vec3 f = SableCompat.dirToWorld(ship, Vec3.atLowerCornerOf(facing.getNormal()));
        List<Vec> wt = new ArrayList<>(tips.size());
        for (Vec3 t : tips) {
            Vec3 tw = SableCompat.toWorld(level, t);
            wt.add(new Vec(tw.x, tw.y, tw.z));
        }
        Vec3 v = SableCompat.pointVelocity(level, ship, tip).scale(20);
        return new DockingRegistry.PortInfo(worldPosition, number, networks, new Vec(w.x, w.y, w.z), new Vec(f.x, f.y, f.z).normalize(),
                new Vec(v.x, v.y, v.z), ship == null ? null : ship.getUniqueId(), locked(), true, List.copyOf(wt), approach);
    }

    @Override
    public void onChunkUnloaded() {
        unloading = true;
        if (level != null && !level.isClientSide) {
            if (role == Docking.Role.PORT) DockingRegistry.unloaded(level, worldPosition);
            for (BlockPos c : connectorPositions()) DockingPorts.unforce(level, c);
        }
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide && !unloading) {
            DockingRegistry.remove(level, worldPosition);
            unpower(connectorPositions());
        }
        super.setRemoved();
    }

    // ------------------------------------------------------------- настройки

    public CompoundTag writeSettings() {
        CompoundTag t = new CompoundTag();
        t.putString("Role", role.name());
        t.putInt("Number", number);
        t.putDouble("Approach", approach);
        return t;
    }

    public void readSettings(CompoundTag t) {
        try {
            role = Docking.Role.valueOf(t.getString("Role"));
        } catch (IllegalArgumentException e) {
            role = Docking.Role.PORT;
        }
        number = cleanNumber(t.getInt("Number"));
        approach = Docking.cleanApproach(t.contains("Approach") ? t.getDouble("Approach") : Double.NaN);
    }

    public static int cleanNumber(int n) {
        return Math.max(0, Math.min(Docking.NUMBER_MAX, n));
    }

    /** Новые настройки с экрана: роль сменилась - порт уходит из реестра, выдвинутый порт возвращает свой сигнал. */
    public void applySettings(CompoundTag t) {
        Docking.Role old = role;
        readSettings(t);
        if (level != null && !level.isClientSide) {
            if (role != old) {
                DockingRegistry.remove(level, worldPosition);
                extendUntil = Long.MIN_VALUE;
            }
            if (getBlockState().hasProperty(DockingControllerBlock.PLUG)
                    && getBlockState().getValue(DockingControllerBlock.PLUG) != (role == Docking.Role.PLUG)) {
                level.setBlock(worldPosition, getBlockState().setValue(DockingControllerBlock.PLUG, role == Docking.Role.PLUG), 3);
            }
            lastPut = Long.MIN_VALUE / 2;
        }
        changed();
    }

    public Status status() {
        return status;
    }

    public int connectorCount() {
        return connectorOffsets.size();
    }

    private void changed() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ------------------------------------------------------------- сохранение

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Docking", writeSettings());
        writeConnectors(tag);
    }

    private void writeConnectors(CompoundTag tag) {
        long[] a = new long[connectorOffsets.size()];
        for (int i = 0; i < a.length; i++) a[i] = connectorOffsets.get(i).asLong();
        tag.putLongArray("Connectors", a);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readSettings(tag.getCompound("Docking"));
        connectorOffsets.clear();
        for (long l : tag.getLongArray("Connectors")) if (connectorOffsets.size() < MAX_CONNECTORS) connectorOffsets.add(BlockPos.of(l));
        if (tag.contains("Connector")) connectorOffsets.add(BlockPos.of(tag.getLong("Connector")));   // 1.1-Test: один порт
        if (tag.contains("Status")) {
            try {
                status = Status.valueOf(tag.getString("Status"));
            } catch (IllegalArgumentException e) {
                status = Status.NO_CONNECTOR;
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.put("Docking", writeSettings());
        writeConnectors(t);
        t.putString("Status", status.name());
        return t;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------- очки инженера

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.docking_controller")).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("    ").append(Component.translatable(role.key())).append(" ")
                .append(Component.translatable("radar_fcs.docking.number_short", number)).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.docking.approach_short",
                AutopilotBlockEntity.fmt(approach))).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.docking.connectors", connectorOffsets.size()))
                .withStyle(ChatFormatting.GRAY));
        ChatFormatting c = switch (status) {
            case NO_CONNECTOR -> ChatFormatting.RED;
            case LOCKED -> ChatFormatting.GREEN;
            case EXTENDED, RESERVED -> ChatFormatting.YELLOW;
            default -> ChatFormatting.GRAY;
        };
        tooltip.add(Component.literal("    ").append(Component.translatable(status.key())).withStyle(c));
        return true;
    }
}
