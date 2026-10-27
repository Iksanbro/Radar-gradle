package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.link.LinkKind;
import dev.radarfcs.link.LinkRegistry;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Радарный линк: хранит второй конец связи (смещение от блока, на котором стоит). */
public class RadarLinkBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    /** Второй конец относительно блока, на котором стоит линк (переживает перенос постройки целиком). */
    private BlockPos targetOffset = BlockPos.ZERO;
    private boolean registered;

    public RadarLinkBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.RADAR_LINK_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RadarLinkBlockEntity be) {
        // повторная регистрация раз в секунду: связь восстанавливается после очистки реестра (перезапуск сервера)
        if (!be.registered || level.getGameTime() % 20 == 0) be.register();
    }

    /** Блок, на грани которого стоит линк. */
    public BlockPos attached() {
        BlockState st = getBlockState();
        return st.hasProperty(RadarLinkBlock.FACING) ? worldPosition.relative(st.getValue(RadarLinkBlock.FACING).getOpposite()) : worldPosition;
    }

    /** Второй конец связи. */
    public BlockPos other() {
        return attached().offset(targetOffset);
    }

    public boolean hasTarget() {
        return !targetOffset.equals(BlockPos.ZERO);
    }

    /** Задать второй конец (сразу после установки линка). */
    public void setOther(BlockPos other) {
        targetOffset = other.subtract(attached());
        setChanged();
        register();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void register() {
        if (level == null || level.isClientSide || !hasTarget()) return;
        boolean first = !registered;
        LinkRegistry.put(level, worldPosition, attached(), other());
        registered = true;
        if (first) notifyEnds();
    }

    private void unregister() {
        if (level == null || level.isClientSide || !registered) return;
        LinkRegistry.remove(level, worldPosition);
        registered = false;
        notifyEnds();
    }

    /** Вычислители на концах пересматривают подключённую аппаратуру сразу, а не по таймеру. */
    private void notifyEnds() {
        for (BlockPos p : new BlockPos[]{attached(), other()}) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof FireControlBlockEntity fcs) fcs.requestDiscover();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        register();
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

    /** Строка для игрока: что с чем связано и на каком расстоянии. */
    public Component describe() {
        if (level == null || !hasTarget()) return Component.translatable("radar_fcs.link.not_linked").withStyle(ChatFormatting.RED);
        LinkKind a = LinkKind.at(level, attached()), b = LinkKind.at(level, other());
        MutableComponent c = Component.translatable("radar_fcs.link.describe", name(a), name(b),
                String.format(Locale.ROOT, "%.0f", Math.sqrt(attached().distSqr(other()))));
        boolean ok = a != null && b != null && LinkKind.canLink(a, b);
        return c.withStyle(ok ? ChatFormatting.AQUA : ChatFormatting.RED);
    }

    private static Component name(LinkKind k) {
        return k == null ? Component.translatable("radar_fcs.link.kind.missing") : k.displayName();
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
        tag.putLong("Target", targetOffset.asLong());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetOffset = BlockPos.of(tag.getLong("Target"));
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
