package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.link.GunLinks;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Орудийный линк - участник орудийной сети: часть орудия, на которой он стоит, входит в орудие этой сети. */
public class GunLinkBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    @Nullable private UUID network;
    private boolean registered, disabled;
    /** Для очков инженера: прицелы, вращатели, станки CBC в сети. */
    private int[] counts = {0, 0, 0};

    public GunLinkBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.GUN_LINK_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GunLinkBlockEntity be) {
        if (level.getGameTime() % 20 == Math.floorMod(pos.hashCode(), 20) || !be.registered && !be.disabled) be.refresh();
    }

    /** Часть орудия, на грани которой стоит линк. */
    public BlockPos attached() {
        BlockState st = getBlockState();
        return st.hasProperty(RadarLinkBlock.FACING) ? worldPosition.relative(st.getValue(RadarLinkBlock.FACING).getOpposite()) : worldPosition;
    }

    @Nullable
    public UUID network() {
        return network;
    }

    public void setNetwork(UUID net) {
        network = net;
        setChanged();
        refresh();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Можно ли поставить орудийный линк на этот блок: прицел, вращатель орудия или станок CBC. */
    public static boolean isGunPart(@Nullable BlockEntity be) {
        return be instanceof SightBlockEntity || be instanceof GunRotatorBlockEntity || (be != null && CbcMount.of(be) != null);
    }

    public void refresh() {
        if (level == null || level.isClientSide) return;
        boolean off = RadarLinkBlock.controlSignal(level, worldPosition, getBlockState()) >= 15;
        if (off || network == null) {
            if (registered) GunLinks.remove(level, worldPosition);
            registered = false;
        } else {
            GunLinks.put(level, worldPosition, attached(), network);
            registered = true;
        }
        int[] c = {0, 0, 0};
        if (network != null && !off) {
            for (BlockPos p : GunLinks.members(level, network)) {
                if (!level.isLoaded(p)) continue;
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof SightBlockEntity) c[0]++;
                else if (be instanceof GunRotatorBlockEntity) c[1]++;
                else if (be != null && CbcMount.of(be) != null) c[2]++;
            }
        }
        if (off != disabled || !java.util.Arrays.equals(c, counts)) {
            disabled = off;
            counts = c;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refresh();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide && registered) GunLinks.remove(level, worldPosition);
        registered = false;
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && !level.isClientSide && registered) GunLinks.remove(level, worldPosition);
        registered = false;
        super.onChunkUnloaded();
    }

    public Component describe() {
        if (network == null) return Component.translatable("radar_fcs.gun_link.not_linked").withStyle(ChatFormatting.RED);
        String name = RadarLinkBlockEntity.shortName(network);
        if (disabled) return Component.translatable("radar_fcs.link.disabled", name).withStyle(ChatFormatting.GOLD);
        return Component.translatable("radar_fcs.gun_link.info", name, counts[0], counts[1], counts[2]).withStyle(ChatFormatting.GOLD);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.gun_link").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(describe()));
        if (network != null && !disabled && counts[0] == 0) tooltip.add(Component.literal("    ")
                .append(Component.translatable("radar_fcs.gun_link.no_sight").withStyle(ChatFormatting.RED)));
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (network != null) tag.putUUID("Network", network);
        tag.putIntArray("Counts", counts);
        tag.putBoolean("Disabled", disabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        network = tag.hasUUID("Network") ? tag.getUUID("Network") : null;
        int[] c = tag.getIntArray("Counts");
        counts = c.length == 3 ? c : new int[]{0, 0, 0};
        disabled = tag.getBoolean("Disabled");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
