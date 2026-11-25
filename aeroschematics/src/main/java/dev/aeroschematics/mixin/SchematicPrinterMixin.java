package dev.aeroschematics.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.schematics.SchematicPrinter;
import dev.aeroschematics.capture.SubLevelCaptureMeta;
import dev.aeroschematics.mixinterface.AeroPrinterExt;
import dev.aeroschematics.mixinterface.AeroTemplateExt;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicPrinter.class, remap = false)
public abstract class SchematicPrinterMixin implements AeroPrinterExt {
    @Unique
    private final Map<UUID, SubLevelCaptureMeta> aeroschematics$subLevelMeta = new HashMap<>();

    @Inject(method = "loadSchematic", at = @At("TAIL"))
    private void aeroschematics$rememberMeta(ItemStack blueprint, Level originalWorld, boolean processNBT, CallbackInfo ci,
                                             @Local StructureTemplate activeTemplate) {
        this.aeroschematics$subLevelMeta.clear();
        this.aeroschematics$subLevelMeta.putAll(((AeroTemplateExt) activeTemplate).aeroschematics$getSubLevelMeta());
    }

    @Inject(method = "resetSchematic", at = @At("TAIL"))
    private void aeroschematics$forgetMeta(CallbackInfo ci) {
        this.aeroschematics$subLevelMeta.clear();
    }

    @Override
    public Map<UUID, SubLevelCaptureMeta> aeroschematics$getSubLevelMeta() {
        return this.aeroschematics$subLevelMeta;
    }
}
