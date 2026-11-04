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
    /** Настройки орудия (одинаковые у всех линков сети; новее - по метке времени). */
    private dev.radarfcs.fcs.GunSettings settings = dev.radarfcs.fcs.GunSettings.DEFAULT;

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

    /** Можно ли поставить орудийный линк на этот блок: прицел, вращатель или привод орудия, станок CBC. */
    public static boolean isGunPart(@Nullable BlockEntity be) {
        return be instanceof SightBlockEntity || be instanceof GunRotatorBlockEntity || be instanceof GunDriveBlockEntity
                || (be != null && CbcMount.of(be) != null);
    }

    public void refresh() {
        if (level == null || level.isClientSide) return;
        boolean off = RadarLinkBlock.controlSignal(level, worldPosition, getBlockState()) >= 15;
        if (off || network == null) {
            if (registered) GunLinks.remove(level, worldPosition);
            registered = false;
        } else {
            GunLinks.put(level, worldPosition, attached(), network, settings);
            // другой линк сети настроен позже - взять его настройки
            dev.radarfcs.fcs.GunSettings net = GunLinks.settings(level, network);
            if (net.stamp > settings.stamp) {
                settings = net;
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
            registered = true;
        }
        int[] c = {0, 0, 0};
        if (network != null && !off) {
            for (BlockPos p : GunLinks.members(level, network)) {
                if (!level.isLoaded(p)) continue;
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof SightBlockEntity) c[0]++;
                else if (be instanceof GunRotatorBlockEntity || be instanceof GunDriveBlockEntity) c[1]++;
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

    public dev.radarfcs.fcs.GunSettings settings() {
        return settings;
    }

    /** Новые настройки орудия - всем загруженным линкам его сети (экран Shift+ПКМ). */
    public void saveSettings(dev.radarfcs.fcs.GunSettings s) {
        if (level == null || level.isClientSide || network == null) return;
        dev.radarfcs.fcs.GunSettings st = s.withStamp(Math.max(System.currentTimeMillis(), settings.stamp + 1));
        java.util.Set<BlockPos> links = new java.util.HashSet<>(GunLinks.links(level, network));
        links.add(worldPosition);
        for (BlockPos p : links) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof GunLinkBlockEntity l) {
                l.settings = st;
                l.setChanged();
                l.refresh();
                level.sendBlockUpdated(p, l.getBlockState(), l.getBlockState(), 3);
            }
        }
    }

    public Component describe() {
        if (network == null) return Component.translatable("radar_fcs.gun_link.not_linked").withStyle(ChatFormatting.RED);
        String name = RadarLinkBlockEntity.shortName(network);
        if (disabled) return Component.translatable("radar_fcs.link.disabled", name).withStyle(ChatFormatting.GOLD);
        Component info = Component.translatable("radar_fcs.gun_link.info", settings.name.isEmpty() ? name : settings.name + " " + name,
                counts[0], counts[1], counts[2]);
        return info.copy().withStyle(ChatFormatting.GOLD);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.gun_link").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(describe()));
        if (network != null) tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.gun.summary",
                Component.translatable(settings.type.key()),
                Component.translatable(settings.autoAim ? "radar_fcs.gun.on" : "radar_fcs.gun.off"),
                Component.translatable(settings.fire ? "radar_fcs.gun.on" : "radar_fcs.gun.off")).withStyle(ChatFormatting.GRAY)));
        if (network != null && !disabled && counts[0] == 0 && counts[2] == 0) tooltip.add(Component.literal("    ")
                .append(Component.translatable("radar_fcs.gun_link.no_sight").withStyle(ChatFormatting.RED)));
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (network != null) tag.putUUID("Network", network);
        tag.putIntArray("Counts", counts);
        tag.putBoolean("Disabled", disabled);
        tag.put("Gun", settings.write());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        network = tag.hasUUID("Network") ? tag.getUUID("Network") : null;
        int[] c = tag.getIntArray("Counts");
        counts = c.length == 3 ? c : new int[]{0, 0, 0};
        disabled = tag.getBoolean("Disabled");
        settings = dev.radarfcs.fcs.GunSettings.read(tag.getCompound("Gun"));
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
