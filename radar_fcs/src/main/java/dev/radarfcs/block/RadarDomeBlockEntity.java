package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.radar.ContactBook;
import dev.radarfcs.radar.Coverage;
import dev.radarfcs.radar.RadarEmitter;
import dev.radarfcs.radar.RadarInfo;
import dev.radarfcs.radar.RadarNetwork;
import dev.radarfcs.radar.RadarScanner;
import dev.radarfcs.radar.RadarStatus;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class RadarDomeBlockEntity extends BlockEntity implements RadarEmitter, IHaveGoggleInformation {
    private final ContactBook book = new ContactBook();
    private DyeColor channel = DyeColor.WHITE;
    private RadarStatus status = RadarStatus.OK;
    private Vec3 origin = Vec3.ZERO;
    private CompoundTag clientSummary = new CompoundTag();
    private CompoundTag lastSent = new CompoundTag();

    public RadarDomeBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.RADAR_DOME_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RadarDomeBlockEntity be) {
        if (level instanceof ServerLevel sl) be.tick(sl);
    }

    private void tick(ServerLevel level) {
        RadarNetwork.register(this);
        long now = level.getGameTime();
        int interval = FcsConfig.DOME_INTERVAL.get();
        if (now % interval != Math.floorMod(worldPosition.hashCode(), interval)) return;
        status = level.hasNeighborSignal(worldPosition) ? RadarStatus.DISABLED : RadarStatus.OK;
        origin = SableCompat.toWorld(level, Vec3.atBottomCenterOf(worldPosition).add(0, 1.05, 0));
        if (status == RadarStatus.OK) {
            var ship = SableCompat.containing(level, worldPosition);
            RadarScanner.scan(level, new RadarScanner.Spec(origin, FcsConfig.DOME_RANGE.get(),
                    Coverage.dome(FcsConfig.DOME_MIN_EL.get(), FcsConfig.DOME_MAX_EL.get()), interval * 3 + 10,
                    ship == null ? null : ship.getUniqueId(), null), book, now);
        } else {
            book.clear();
        }
        CompoundTag s = RadarInfo.summary(status, FcsConfig.DOME_RANGE.get(), book.size(), channel, -1, interval);
        if (!s.equals(lastSent)) {
            lastSent = s;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setChannel(DyeColor c, Player p) {
        channel = c;
        book.clear();
        setChanged();
        RadarInfo.channelMessage(p, c);
    }

    @Override public Level radarLevel() { return level; }
    @Override public BlockPos radarPos() { return worldPosition; }
    @Override public boolean isRadarRemoved() { return isRemoved(); }
    @Override public boolean radarRunning() { return status == RadarStatus.OK; }
    @Override public int radarChannel() { return channel.getId(); }
    @Override public Vec3 radarOrigin() { return origin; }
    @Override public double radarRange() { return FcsConfig.DOME_RANGE.get(); }
    @Override public Type radarType() { return Type.DOME; }
    @Override public ContactBook radarContacts() { return book; }

    @Override
    public void setRemoved() {
        RadarNetwork.unregister(this);
        super.setRemoved();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        RadarInfo.tooltip(tooltip, "dome", clientSummary);
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("RadarChannel", channel.getId());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        channel = DyeColor.byId(tag.getInt("RadarChannel"));
        if (tag.contains("Radar")) clientSummary = tag.getCompound("Radar");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        tag.put("Radar", RadarInfo.summary(status, FcsConfig.DOME_RANGE.get(), book.size(), channel, -1, FcsConfig.DOME_INTERVAL.get()));
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
