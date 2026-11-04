package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.energy.EnergyBuffer;
import dev.radarfcs.link.LinkRegistry;
import dev.radarfcs.registry.FcsRegistry;
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
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Монитор камер: участник радарной сети (камеры - аппаратура той же сети). Электричество - за каждую камеру сети
 * (`energy.cameraMonitorUsagePerCamera`, 64 FE/тик); без питания через камеры не смотрят.
 */
public class CameraMonitorBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    private final EnergyBuffer energy = new EnergyBuffer(64, this::setChanged);
    private boolean powered = true;
    private int cameras;

    public CameraMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.CAMERA_MONITOR_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CameraMonitorBlockEntity be) {
        if (level.getGameTime() % 20 == Math.floorMod(pos.hashCode(), 20)) {
            int n = 0;
            for (BlockPos p : LinkRegistry.linkedTo(level, pos)) {
                if (level.isLoaded(p) && level.getBlockEntity(p) instanceof CameraBlockEntity) n++;
            }
            if (n != be.cameras) {
                be.cameras = n;
                level.sendBlockUpdated(pos, state, state, 3);
            }
        }
        int usage = be.usage();
        be.energy.fit(Math.max(1, usage));
        boolean p = !EnergyBuffer.required() || usage == 0 || be.energy.consume(usage);
        if (p != be.powered) {
            be.powered = p;
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    /** Потребление сейчас, FE/тик: за каждую камеру сети. */
    public int usage() {
        return cameras * FcsConfig.safe(FcsConfig.CAMERA_MONITOR_USAGE);
    }

    public int cameras() {
        return cameras;
    }

    public boolean powered() {
        return powered;
    }

    public EnergyBuffer energy() {
        return energy;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.camera_monitor").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.camera_monitor.cameras", cameras)
                .withStyle(ChatFormatting.AQUA)));
        tooltip.add(Component.literal("    ").append(powered
                ? Component.translatable("radar_fcs.energy.stored", energy.getEnergyStored(), energy.getMaxEnergyStored(), usage())
                        .withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("radar_fcs.camera_monitor.no_energy", usage()).withStyle(ChatFormatting.RED)));
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        energy.save(tag);
        tag.putBoolean("Powered", powered);
        tag.putInt("Cameras", cameras);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.load(tag);
        powered = !tag.contains("Powered") || tag.getBoolean("Powered");
        cameras = tag.getInt("Cameras");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
