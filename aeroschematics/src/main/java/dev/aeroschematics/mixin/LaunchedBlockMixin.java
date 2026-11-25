package dev.aeroschematics.mixin;

import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks shot at a physics structure (their target lies in a Sable plot):
 * <ul>
 *     <li>are not written into a plot whose structure no longer exists (destroyed while the block was in flight);</li>
 *     <li>get their prepared block entity data applied once more after Create called {@code setPlacedBy} with the
 *     printed item: some blocks (e.g. Create Big Cannons' ammo container) copy settings from the item there and would
 *     otherwise lose the settings stored in the schematic. The data is the sanitized one, so nothing is added.</li>
 * </ul>
 */
@Mixin(value = LaunchedItem.ForBlockState.class, remap = false)
public abstract class LaunchedBlockMixin {
    @Shadow
    public BlockState state;

    @Shadow
    public CompoundTag data;

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void aeroschematics$skipDeadPlots(Level level, CallbackInfo ci) {
        LaunchedItem self = (LaunchedItem) (Object) this;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null && container.inBounds(self.target) && Sable.HELPER.getContaining(level, self.target) == null) {
            ci.cancel();
        }
    }

    @Inject(method = "place", at = @At("TAIL"))
    private void aeroschematics$reapplyData(Level level, CallbackInfo ci) {
        LaunchedItem self = (LaunchedItem) (Object) this;
        if (this.data == null || Sable.HELPER.getContaining(level, self.target) == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(self.target);
        if (blockEntity == null || level.getBlockState(self.target).getBlock() != this.state.getBlock()) {
            return;
        }
        CompoundTag tag = this.data.copy();
        tag.putInt("x", self.target.getX());
        tag.putInt("y", self.target.getY());
        tag.putInt("z", self.target.getZ());
        blockEntity.loadWithComponents(tag, level.registryAccess());
        blockEntity.setChanged();
        level.sendBlockUpdated(self.target, blockEntity.getBlockState(), blockEntity.getBlockState(), 3);
    }
}
