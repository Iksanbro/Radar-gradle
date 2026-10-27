package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.link.LinkKind;
import dev.radarfcs.link.LinkRegistry;
import dev.radarfcs.registry.FcsRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

/**
 * Радарный линк - участник радарной сети (как складской передатчик Create): аппаратура, на которой он стоит, связана
 * со всей аппаратурой той же сети, на любом расстоянии. Полный сигнал редстоуна отключает линк.
 * Старые парные линки (версии 0.6-0.7, без сети) продолжают связывать оба своих конца.
 */
public class RadarLinkBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    @Nullable private UUID network;
    /** Только у старых парных линков: второй конец относительно блока, на котором стоит линк. */
    private BlockPos legacyOffset = BlockPos.ZERO;
    private boolean registered;
    /** Для очков инженера: аппаратура сети по видам (LinkKind); отключён ли сигналом. */
    private int[] counts = new int[LinkKind.values().length];
    private boolean disabled;

    public RadarLinkBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.RADAR_LINK_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RadarLinkBlockEntity be) {
        if (level.getGameTime() % 20 == Math.floorMod(pos.hashCode(), 20)) be.refresh();
        else if (!be.registered && !be.disabled) be.refresh();
    }

    /** Блок, на грани которого стоит линк. */
    public BlockPos attached() {
        BlockState st = getBlockState();
        return st.hasProperty(RadarLinkBlock.FACING) ? worldPosition.relative(st.getValue(RadarLinkBlock.FACING).getOpposite()) : worldPosition;
    }

    /** Сеть линка (у старого парного - производная от его концов) или null. */
    @Nullable
    public UUID network() {
        if (network != null) return network;
        if (!legacyOffset.equals(BlockPos.ZERO)) {
            BlockPos a = attached(), b = a.offset(legacyOffset);
            BlockPos lo = a.compareTo(b) < 0 ? a : b, hi = lo == a ? b : a;
            return UUID.nameUUIDFromBytes(("radar_fcs:legacy:" + lo.asLong() + ":" + hi.asLong()).getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    /** Подключить к сети (сразу после установки). */
    public void setNetwork(UUID net) {
        network = net;
        legacyOffset = BlockPos.ZERO;
        setChanged();
        refresh();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Перерегистрация в сети, отключение по редстоуну и сводка для очков инженера. */
    public void refresh() {
        if (level == null || level.isClientSide) return;
        boolean off = level.getBestNeighborSignal(worldPosition) >= 15;
        UUID net = network();
        if (off || net == null) {
            unregister();
        } else {
            List<LinkRegistry.Member> members = new ArrayList<>();
            members.add(new LinkRegistry.Member(worldPosition, attached(), net));
            if (network == null) members.add(new LinkRegistry.Member(worldPosition, attached().offset(legacyOffset), net));
            boolean first = !registered;
            LinkRegistry.put(level, worldPosition, members);
            registered = true;
            if (first) notifyNetwork(net);
        }
        int[] c = new int[LinkKind.values().length];
        if (net != null && !off) {
            for (BlockPos p : LinkRegistry.members(level, net)) {
                LinkKind k = LinkKind.at(level, p);
                if (k != null) c[k.ordinal()]++;
            }
        }
        if (off != disabled || !java.util.Arrays.equals(c, counts)) {
            disabled = off;
            counts = c;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private void unregister() {
        if (level == null || level.isClientSide || !registered) return;
        LinkRegistry.remove(level, worldPosition);
        registered = false;
        UUID net = network();
        if (net != null) notifyNetwork(net);
    }

    /** Вычислители сети пересматривают аппаратуру сразу, а не по таймеру. */
    private void notifyNetwork(UUID net) {
        List<BlockPos> ps = new ArrayList<>(LinkRegistry.members(level, net));
        ps.add(attached());
        for (BlockPos p : ps) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof FireControlBlockEntity fcs) fcs.requestDiscover();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refresh();
    }

    @Override
    public void setRemoved() {
        unregister();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        unregister();
        super.onChunkUnloaded();
    }

    /** Короткое имя сети для игрока: #3F2A. */
    public static String shortName(UUID net) {
        return "#" + net.toString().substring(0, 4).toUpperCase(Locale.ROOT);
    }

    /** Строка для игрока: сеть и что в ней. */
    public Component describe() {
        UUID net = network();
        if (net == null) return Component.translatable("radar_fcs.link.not_linked").withStyle(ChatFormatting.RED);
        if (disabled) return Component.translatable("radar_fcs.link.disabled", shortName(net)).withStyle(ChatFormatting.GOLD);
        return Component.translatable("radar_fcs.link.network_info", shortName(net), counts[0], counts[1], counts[2], counts[3] + counts[4])
                .withStyle(ChatFormatting.AQUA);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.radar_link").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(describe()));
        return true;
    }

    // ------------------------------------------------------------------ NBT

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (network != null) tag.putUUID("Network", network);
        if (!legacyOffset.equals(BlockPos.ZERO)) tag.putLong("Target", legacyOffset.asLong());
        tag.putIntArray("Counts", counts);
        tag.putBoolean("Disabled", disabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        network = tag.hasUUID("Network") ? tag.getUUID("Network") : null;
        legacyOffset = tag.contains("Target") ? BlockPos.of(tag.getLong("Target")) : BlockPos.ZERO;
        int[] c = tag.getIntArray("Counts");
        counts = c.length == LinkKind.values().length ? c : new int[LinkKind.values().length];
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
