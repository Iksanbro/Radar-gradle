package dev.radarfcs.block;

import dev.radarfcs.iff.IffRegistry;
import dev.radarfcs.registry.FcsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Регистрирует транспондер в IffRegistry, пока блок загружен. */
public class IffTransponderBlockEntity extends BlockEntity {
    public IffTransponderBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.IFF_BE.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            IffRegistry.put(level, worldPosition, IffTransponderBlock.channelOf(getBlockState()));
        }
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && !level.isClientSide) IffRegistry.remove(level, worldPosition);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) IffRegistry.remove(level, worldPosition);
        super.setRemoved();
    }
}
