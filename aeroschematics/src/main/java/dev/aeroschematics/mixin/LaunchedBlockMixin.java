package dev.aeroschematics.mixin;

import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block shot at a physics structure that no longer exists (destroyed while the block was in flight) must not be
 * written into the now ownerless plot area.
 */
@Mixin(value = LaunchedItem.ForBlockState.class, remap = false)
public abstract class LaunchedBlockMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void aeroschematics$skipDeadPlots(Level level, CallbackInfo ci) {
        LaunchedItem self = (LaunchedItem) (Object) this;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null && container.inBounds(self.target) && Sable.HELPER.getContaining(level, self.target) == null) {
            ci.cancel();
        }
    }
}
