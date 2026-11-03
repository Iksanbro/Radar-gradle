package dev.radarfcs.block;

import dev.radarfcs.registry.FcsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Монитор камер: участник радарной сети (камеры - аппаратура той же сети). */
public class CameraMonitorBlockEntity extends BlockEntity {
    public CameraMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.CAMERA_MONITOR_BE.get(), pos, state);
    }
}
