package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Vec;
import dev.radarfcs.radar.ContactBook;
import dev.radarfcs.radar.Coverage;
import dev.radarfcs.radar.RadarEmitter;
import dev.radarfcs.radar.RadarInfo;
import dev.radarfcs.radar.RadarNetwork;
import dev.radarfcs.radar.RadarScanner;
import dev.radarfcs.radar.RadarStatus;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Фазированная решётка: контроллер + панели в одной плоскости (мультиблок любой формы).
 * Сектор ±halfAngle перед решёткой обновляется целиком; период обзора зависит от скорости вала,
 * нагрузка на кинетику - от числа элементов.
 */
public class PhasedArrayBlockEntity extends KineticBlockEntity implements RadarEmitter {
    private final ContactBook book = new ContactBook();
    private DyeColor channel = DyeColor.WHITE;
    private int panels = -1;
    private Vec3 origin = Vec3.ZERO;
    private double range;
    private long lastScan, lastStructure = Long.MIN_VALUE / 2;
    private int interval = 20;
    private RadarStatus status = RadarStatus.NO_ROTATION;
    private CompoundTag clientSummary = new CompoundTag();

    public PhasedArrayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        setLazyTickRate(20);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    @Override
    public void tick() {
        super.tick();
        if (level instanceof ServerLevel sl) radarTick(sl);
    }

    private void radarTick(ServerLevel level) {
        RadarNetwork.register(this);
        long now = level.getGameTime();
        if (now - lastStructure >= 20) {
            lastStructure = now;
            rescan(level);
        }
        float rpm = Math.abs(getSpeed());
        if (isOverStressed()) status = RadarStatus.OVERSTRESSED;
        else if (rpm == 0) status = RadarStatus.NO_ROTATION;
        else status = RadarStatus.OK;
        if (status != RadarStatus.OK) {
            book.clear();
            return;
        }
        interval = Mth.clamp(Math.round(64f / rpm), 2, 20);
        if (now - lastScan < interval) return;
        lastScan = now;
        Direction f = getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        var ship = SableCompat.containing(level, worldPosition);
        Vec3 dir = SableCompat.dirToWorld(ship, Vec3.atLowerCornerOf(f.getNormal()));
        double yaw = new Vec(dir.x, dir.y, dir.z).yawDeg();
        Coverage cov = Coverage.sector(yaw, FcsConfig.ARRAY_HALF_ANGLE.get(), FcsConfig.ARRAY_MIN_EL.get(), FcsConfig.ARRAY_MAX_EL.get());
        RadarScanner.scan(level, new RadarScanner.Spec(origin, range, cov, interval * 3 + 10,
                ship == null ? null : ship.getUniqueId(), null), book, now);
    }

    /** Панели решётки: связные с контроллером в его плоскости, с тем же направлением. */
    private void rescan(ServerLevel level) {
        BlockState self = getBlockState();
        Direction f = self.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Direction[] plane = {Direction.UP, Direction.DOWN, f.getClockWise(), f.getCounterClockWise()};
        int max = FcsConfig.ARRAY_MAX_PANELS.get();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(worldPosition);
        queue.add(worldPosition);
        double sx = 0, sy = 0, sz = 0;
        int count = 0;
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            sx += p.getX() + 0.5;
            sy += p.getY() + 0.5;
            sz += p.getZ() + 0.5;
            for (Direction d : plane) {
                BlockPos q = p.relative(d);
                if (count >= max || seen.contains(q) || !level.isLoaded(q)) continue;
                BlockState s = level.getBlockState(q);
                if (s.getBlock() instanceof PhasedArrayPanelBlock && s.getValue(PhasedArrayPanelBlock.FACING) == f) {
                    seen.add(q);
                    queue.add(q);
                    count++;
                }
            }
        }
        int n = seen.size();
        Vec3 center = new Vec3(sx / n, sy / n, sz / n).add(Vec3.atLowerCornerOf(f.getNormal()).scale(0.6));
        origin = SableCompat.toWorld(level, center);
        range = Math.min(FcsConfig.ARRAY_MAX_RANGE.get(), FcsConfig.ARRAY_BASE_RANGE.get() + FcsConfig.ARRAY_RANGE_PER_PANEL.get() * count);
        if (count != panels) {
            panels = count;
            if (hasNetwork()) getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
            book.clear();
            sendData();
        }
    }

    @Override
    public float calculateStressApplied() {
        float impact = (float) (FcsConfig.ARRAY_STRESS.get() * (1 + Math.max(0, panels)));
        lastStressApplied = impact;
        return impact;
    }

    public void setChannel(DyeColor c, Player p) {
        channel = c;
        book.clear();
        setChanged();
        sendData();
        RadarInfo.channelMessage(p, c);
    }

    public int panelCount() { return Math.max(0, panels); }

    public RadarStatus status() { return status; }

    // ------------------------------------------------------------ RadarEmitter

    @Override public Level radarLevel() { return level; }
    @Override public BlockPos radarPos() { return worldPosition; }
    @Override public boolean isRadarRemoved() { return isRemoved(); }
    @Override public boolean radarRunning() { return status == RadarStatus.OK; }
    @Override public int radarChannel() { return channel.getId(); }
    @Override public Vec3 radarOrigin() { return origin; }
    @Override public double radarRange() { return range; }
    @Override public Type radarType() { return Type.ARRAY; }
    @Override public ContactBook radarContacts() { return book; }

    @Override
    public void remove() {
        RadarNetwork.unregister(this);
        super.remove();
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (level != null && !level.isClientSide) sendData();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("RadarChannel", channel.getId());
        if (clientPacket) tag.put("Radar", RadarInfo.summary(status, range, book.size(), channel, Math.max(0, panels), interval));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        channel = DyeColor.byId(tag.getInt("RadarChannel"));
        if (clientPacket) clientSummary = tag.getCompound("Radar");
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        RadarInfo.tooltip(tooltip, "array", clientSummary);
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }
}
