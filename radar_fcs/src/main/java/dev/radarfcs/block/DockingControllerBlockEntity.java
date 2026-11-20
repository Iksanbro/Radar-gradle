package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.compat.DockingPorts;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Docking;
import dev.radarfcs.core.Vec;
import dev.radarfcs.dock.DockingRegistry;
import dev.radarfcs.link.LinkRegistry;
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
    @Nullable private BlockPos connectorOffset;

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

    // ------------------------------------------------------------- связь с портом Simulated

    @Nullable
    public BlockPos connectorPos() {
        return connectorOffset == null ? null : worldPosition.offset(connectorOffset);
    }

    /** Связать с портом Simulated в точке p (проверка - {@link DockingPorts#linkable}). */
    public void linkConnector(@Nullable BlockPos p) {
        if (level != null && !level.isClientSide && forced && connectorPos() != null) DockingPorts.setPowered(level, connectorPos(), false);
        forced = false;
        connectorOffset = p == null ? null : p.subtract(worldPosition);
        changed();
    }

    /** Порт Simulated на месте: его направление, иначе null. */
    @Nullable
    public Direction connectorFacing() {
        BlockPos c = connectorPos();
        return c == null || level == null ? null : DockingPorts.facingAt(level, c);
    }

    /** Конец порта Simulated в координатах постройки (плот); null - порта нет. */
    @Nullable
    public Vec3 localTip() {
        Direction f = connectorFacing();
        return f == null ? null : DockingPorts.localTip(connectorPos(), f);
    }

    /** Порт сцеплен. */
    public boolean locked() {
        BlockPos c = connectorPos();
        return c != null && level != null && DockingPorts.isLocked(level, c);
    }

    /** Штекер: выдвигать порт корабля ещё пару тиков (мост повторяет команду каждый тик). */
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
        BlockPos c = connectorPos();
        Direction facing = connectorFacing();
        boolean extend = false;
        if (facing == null) {
            if (role == Docking.Role.PORT) DockingRegistry.remove(level, worldPosition);
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
                DockingRegistry.put(level, describe(level, c, facing));
            }
            boolean locked = locked();
            status = locked ? Status.LOCKED : extend ? Status.EXTENDED : r != null ? Status.RESERVED : Status.IDLE;
        } else {
            extend = now <= extendUntil;
            status = locked() ? Status.LOCKED : extend ? Status.EXTENDED : Status.IDLE;
        }
        // через 2 с после загрузки: порт мог сохраниться выдвинутым по команде, а команды нет - вернуть ему его сигнал
        // (раньше нельзя: пристыкованный корабль расстыковался бы, пока мост не подтвердил стыковку)
        if (firstTick == Long.MIN_VALUE) firstTick = now;
        boolean check = !checked && now - firstTick >= 40;
        if (c != null && (extend || forced || check)) forced = DockingPorts.setPowered(level, c, extend);
        if (check) checked = true;
        if (now - lastSync >= 10) {
            lastSync = now;
            String summary = status.name();
            if (!summary.equals(lastSummary)) {
                lastSummary = summary;
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    /** Порт для реестра: мировой конец, направление и скорость (у порта на корабле - через его позу). */
    private DockingRegistry.PortInfo describe(ServerLevel level, BlockPos c, Direction facing) {
        var ship = SableCompat.containing(level, worldPosition);
        Vec3 tip = DockingPorts.localTip(c, facing);
        Vec3 w = SableCompat.toWorld(level, tip);
        Vec3 f = SableCompat.dirToWorld(ship, Vec3.atLowerCornerOf(facing.getNormal()));
        Vec3 v = SableCompat.pointVelocity(level, ship, tip).scale(20);
        return new DockingRegistry.PortInfo(worldPosition, number, networks, new Vec(w.x, w.y, w.z), new Vec(f.x, f.y, f.z).normalize(),
                new Vec(v.x, v.y, v.z), ship == null ? null : ship.getUniqueId(), locked(), true);
    }

    @Override
    public void onChunkUnloaded() {
        unloading = true;
        if (level != null && !level.isClientSide) {
            if (role == Docking.Role.PORT) DockingRegistry.unloaded(level, worldPosition);
            BlockPos c = connectorPos();
            if (c != null) DockingPorts.unforce(level, c);
        }
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide && !unloading) {
            DockingRegistry.remove(level, worldPosition);
            BlockPos c = connectorPos();
            if (forced && c != null && level.isLoaded(c)) DockingPorts.setPowered(level, c, false);
        }
        super.setRemoved();
    }

    // ------------------------------------------------------------- настройки

    public CompoundTag writeSettings() {
        CompoundTag t = new CompoundTag();
        t.putString("Role", role.name());
        t.putInt("Number", number);
        return t;
    }

    public void readSettings(CompoundTag t) {
        try {
            role = Docking.Role.valueOf(t.getString("Role"));
        } catch (IllegalArgumentException e) {
            role = Docking.Role.PORT;
        }
        number = cleanNumber(t.getInt("Number"));
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

    public boolean connectorLinked() {
        return connectorOffset != null;
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
        if (connectorOffset != null) tag.putLong("Connector", connectorOffset.asLong());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readSettings(tag.getCompound("Docking"));
        connectorOffset = tag.contains("Connector") ? BlockPos.of(tag.getLong("Connector")) : null;
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
        if (connectorOffset != null) t.putLong("Connector", connectorOffset.asLong());
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
