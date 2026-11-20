package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Vec;
import dev.radarfcs.dock.BeaconRegistry;
import java.util.List;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Маяк орбиты: центр орбиты, по которой корабль летает без цели (режим моста «орбита вокруг маяка»). Мост находит
 * маяк по коду (в любом месте измерения, {@link BeaconRegistry}), а без кода в мосту - первый маяк своей радарной сети.
 * Может стоять и на корабле - тогда орбита идёт за ним. Радиус, высота и направление - в мосту.
 */
public class OrbitBeaconBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    private String code = "";
    private boolean unloading;

    public OrbitBeaconBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String code() {
        return code;
    }

    public void setCode(String c) {
        code = BeaconRegistry.clean(c);
        if (level != null && !level.isClientSide) publish();
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState st, OrbitBeaconBlockEntity be) {
        // маяк на корабле движется - положение каждые 5 тиков; на земле - раз в секунду
        if (level.getGameTime() % (SableCompat.containingId(level, pos) != null ? 5 : 20) == 0) be.publish();
    }

    /** Сообщить реестру мировую точку и скорость маяка. */
    private void publish() {
        if (level == null) return;
        Vec3 local = Vec3.atCenterOf(worldPosition);
        Vec3 w = SableCompat.toWorld(level, local);
        Vec3 v = SableCompat.pointVelocity(level, SableCompat.containing(level, worldPosition), local).scale(20);
        BeaconRegistry.put(level, new BeaconRegistry.Beacon(worldPosition, code, new Vec(w.x, w.y, w.z), new Vec(v.x, v.y, v.z)));
    }

    @Override
    public void onChunkUnloaded() {
        unloading = true;
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide && !unloading) BeaconRegistry.remove(level, worldPosition);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Code", code);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        code = BeaconRegistry.clean(tag.getString("Code"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.putString("Code", code);
        return t;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.orbit_beacon")).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("    ").append(code.isEmpty() ? Component.translatable("radar_fcs.orbit_beacon.no_code")
                : Component.translatable("radar_fcs.orbit_beacon.code", code)).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.orbit_beacon.goggles")).withStyle(ChatFormatting.GRAY));
        return true;
    }
}
