package dev.aeroschematics.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.schematics.SchematicPrinter;
import com.simibubi.create.content.schematics.packet.SchematicPlacePacket;
import dev.aeroschematics.place.CreativePlacer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicPlacePacket.class, remap = false)
public abstract class SchematicPlacePacketMixin {
    @Shadow
    @Final
    private ItemStack stack;

    @Inject(
            method = "handle",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/schematics/SchematicPrinter;loadSchematic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Z)V",
                    shift = At.Shift.AFTER
            )
    )
    private void aeroschematics$placePhysicsStructures(ServerPlayer player, CallbackInfo ci, @Local SchematicPrinter printer) {
        CreativePlacer.placeAll(player, printer, this.stack);
    }
}
