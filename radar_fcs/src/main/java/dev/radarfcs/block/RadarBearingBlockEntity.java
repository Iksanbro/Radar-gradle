package dev.radarfcs.block;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Aim;
import dev.radarfcs.core.Vec;
import dev.radarfcs.radar.ContactBook;
import dev.radarfcs.radar.Coverage;
import dev.radarfcs.radar.RadarEmitter;
import dev.radarfcs.radar.RadarInfo;
import dev.radarfcs.radar.RadarNetwork;
import dev.radarfcs.radar.RadarScanner;
import dev.radarfcs.radar.RadarStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Радарный подшипник. Вращает антенну (контрапшен Create) и обнаруживает цели в секторе,
 * который прошёл луч с прошлого обзора. Дальность растёт с числом отражателей, частота обновления -
 * со скоростью вращения. Подшипник должен смотреть вверх или вниз.
 */
public class RadarBearingBlockEntity extends MechanicalBearingBlockEntity implements RadarEmitter {
    private static final int SCAN_EVERY = 2;

    private final ContactBook book = new ContactBook();
    private DyeColor channel = DyeColor.WHITE;

    // антенна (пересчитывается при новой сборке)
    @Nullable private ControlledContraptionEntity analyzed;
    private int dishes;
    @Nullable private BlockPos receiverLocal;
    private Direction receiverFacing = Direction.NORTH;
    private double range;

    // развёртка луча: азимут без переноса через 360
    private double unwrapped = Double.NaN, lastYaw, scanFrom;
    private long lastScan;
    private Vec3 origin = Vec3.ZERO;
    private RadarStatus status = RadarStatus.NO_ROTATION;
    private int periodTicks;

    private CompoundTag clientSummary = new CompoundTag();

    public RadarBearingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        if (level instanceof ServerLevel sl) radarTick(sl);
    }

    private void radarTick(ServerLevel level) {
        RadarNetwork.register(this);
        long now = level.getGameTime();
        if (movedContraption != analyzed) analyze();
        Direction facing = getBlockState().getValue(BearingBlock.FACING);
        RadarStatus st;
        if (facing.getAxis() != Direction.Axis.Y) st = RadarStatus.BAD_FACING;
        else if (isOverStressed()) st = RadarStatus.OVERSTRESSED;
        else if (movedContraption == null || !running || getSpeed() == 0) st = RadarStatus.NO_ROTATION;
        else if (receiverLocal == null) st = RadarStatus.NO_RECEIVER;
        else st = RadarStatus.OK;
        status = st;
        if (st != RadarStatus.OK || movedContraption == null || receiverLocal == null) {
            book.clear();
            unwrapped = Double.NaN;
            periodTicks = 0;
            return;
        }
        var ship = SableCompat.containing(level, worldPosition);
        origin = SableCompat.toWorld(level, movedContraption.toGlobalVector(Vec3.atCenterOf(receiverLocal), 1.0F));
        Vec3 n = Vec3.atLowerCornerOf(receiverFacing.getNormal());
        Vec3 dir = SableCompat.dirToWorld(ship, movedContraption.applyRotation(n, 1.0F));
        double yaw = new Vec(dir.x, dir.y, dir.z).yawDeg();
        if (Double.isNaN(unwrapped)) {
            unwrapped = yaw;
            scanFrom = yaw;
        } else {
            unwrapped += Aim.wrap180(yaw - lastYaw);
        }
        lastYaw = yaw;
        double omega = Math.abs(getAngularSpeed());                         // градусов/тик
        periodTicks = omega > 1e-3 ? (int) Math.ceil(360.0 / omega) : 0;
        if (now - lastScan < SCAN_EVERY) return;
        lastScan = now;
        int ttl = Mth.clamp((int) (periodTicks * 1.5) + 20, 40, 600);
        Coverage cov = Coverage.sweep(scanFrom, unwrapped, FcsConfig.BEARING_BEAM_WIDTH.get() / 2,
                FcsConfig.BEARING_MIN_EL.get(), FcsConfig.BEARING_MAX_EL.get());
        scanFrom = unwrapped;
        UUID ownShip = ship == null ? null : ship.getUniqueId();
        RadarScanner.scan(level, new RadarScanner.Spec(origin, range, cov, ttl, ownShip, movedContraption), book, now);
    }

    /** Состав антенны: облучатель (ближайший к оси) и число отражателей. */
    private void analyze() {
        analyzed = movedContraption;
        dishes = 0;
        receiverLocal = null;
        if (movedContraption != null && movedContraption.getContraption() != null) {
            double best = Double.MAX_VALUE;
            for (Map.Entry<BlockPos, StructureBlockInfo> en : movedContraption.getContraption().getBlocks().entrySet()) {
                BlockState s = en.getValue().state();
                if (s.getBlock() instanceof RadarReceiverBlock) {
                    BlockPos p = en.getKey();
                    double d = (double) p.getX() * p.getX() + (double) p.getZ() * p.getZ();
                    if (d < best) {
                        best = d;
                        receiverLocal = p;
                        receiverFacing = s.getValue(RadarReceiverBlock.FACING);
                    }
                } else if (s.getBlock() instanceof AntennaBlock) {
                    dishes++;
                }
            }
        }
        range = Math.min(FcsConfig.BEARING_MAX_RANGE.get(),
                FcsConfig.BEARING_BASE_RANGE.get() + FcsConfig.BEARING_RANGE_PER_DISH.get() * dishes);
        book.clear();
    }

    public void toggleAssembly() {
        if (running) disassemble();
        else assembleNextTick = true;
    }

    public void setChannel(DyeColor c, Player p) {
        channel = c;
        book.clear();
        setChanged();
        sendData();
        RadarInfo.channelMessage(p, c);
    }

    // ------------------------------------------------------------ RadarEmitter

    @Override public Level radarLevel() { return level; }
    @Override public BlockPos radarPos() { return worldPosition; }
    @Override public boolean isRadarRemoved() { return isRemoved(); }
    @Override public boolean radarRunning() { return status == RadarStatus.OK; }
    @Override public int radarChannel() { return channel.getId(); }
    @Override public Vec3 radarOrigin() { return origin; }
    @Override public double radarRange() { return range; }
    @Override public Type radarType() { return Type.BEARING; }
    @Override public ContactBook radarContacts() { return book; }

    public int dishCount() { return dishes; }

    public RadarStatus status() { return status; }

    @Override
    public void remove() {
        RadarNetwork.unregister(this);
        super.remove();
    }

    // ---------------------------------------------------------- данные, очки

    @Override
    public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("RadarChannel", channel.getId());
        if (clientPacket) {
            tag.put("Radar", RadarInfo.summary(status, range, book.size(), channel, dishes, periodTicks));
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        channel = DyeColor.byId(tag.getInt("RadarChannel"));
        if (clientPacket) clientSummary = tag.getCompound("Radar");
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        // без собранной антенны подшипник Create данные не шлёт, а очкам нужен статус
        if (level != null && !level.isClientSide && movedContraption == null && level.getGameTime() % 20 < 3) sendData();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        RadarInfo.tooltip(tooltip, "bearing", clientSummary);
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }
}
