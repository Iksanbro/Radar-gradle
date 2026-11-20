package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.energy.EnergyBuffer;
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

/** Блок памяти: с питанием (FE) расширяет списки вычислителей своей радарной сети на PER_BLOCK записей. */
public class MemoryBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    /** Записей в каждом списке без блоков памяти и за каждый блок памяти. */
    public static final int BASE = 16, PER_BLOCK = 16;

    private final EnergyBuffer energy = new EnergyBuffer(240, this::setChanged);
    private boolean powered;

    public MemoryBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.MEMORY_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MemoryBlockEntity be) {
        int usage = FcsConfig.safe(FcsConfig.MEMORY_USAGE);
        be.energy.fit(usage);
        boolean p = !EnergyBuffer.required() || be.energy.consume(usage);
        if (p != be.powered) {
            be.powered = p;
            if (state.hasProperty(MemoryBlock.POWERED)) level.setBlock(pos, state.setValue(MemoryBlock.POWERED, p), 3);
        } else if (level.getGameTime() % 20 == Math.floorMod(pos.hashCode(), 20)) {
            level.sendBlockUpdated(pos, state, state, 3);                // запас энергии для очков инженера
        }
    }

    /** Работает ли (есть питание). */
    public boolean powered() {
        return powered;
    }

    public EnergyBuffer energy() {
        return energy;
    }

    public Component describe() {
        return powered ? Component.translatable("radar_fcs.memory.on", PER_BLOCK).withStyle(ChatFormatting.GREEN)
                : Component.translatable("radar_fcs.memory.off", FcsConfig.safe(FcsConfig.MEMORY_USAGE)).withStyle(ChatFormatting.RED);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.memory_block").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(describe()));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.energy.stored", energy.getEnergyStored(),
                energy.getMaxEnergyStored(), FcsConfig.safe(FcsConfig.MEMORY_USAGE)).withStyle(ChatFormatting.DARK_GRAY)));
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        energy.save(tag);
        tag.putBoolean("Powered", powered);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.load(tag);
        powered = tag.getBoolean("Powered");
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
