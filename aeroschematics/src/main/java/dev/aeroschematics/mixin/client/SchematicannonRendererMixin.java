package dev.aeroschematics.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.schematics.cannon.SchematicannonRenderer;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Blocks shot at a physics structure target its plot; draw their arc towards where the structure actually is. */
@Mixin(value = SchematicannonRenderer.class, remap = false)
public abstract class SchematicannonRendererMixin {
    @WrapOperation(
            method = "renderLaunchedBlocks",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;atCenterOf(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;", ordinal = 1)
    )
    private static Vec3 aeroschematics$projectTarget(Vec3i target, Operation<Vec3> original) {
        Vec3 center = original.call(target);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return center;
        }
        return Sable.HELPER.projectOutOfSubLevel(minecraft.level, center);
    }
}
