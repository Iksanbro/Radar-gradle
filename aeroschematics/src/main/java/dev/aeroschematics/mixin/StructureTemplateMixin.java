package dev.aeroschematics.mixin;

import dev.aeroschematics.AeroSchematics;
import dev.aeroschematics.capture.CaptureHooks;
import dev.aeroschematics.capture.SubLevelCaptureMeta;
import dev.aeroschematics.mixinterface.AeroTemplateExt;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin implements AeroTemplateExt {
    @Shadow
    @Final
    private List<StructureTemplate.Palette> palettes;

    @Shadow
    @Final
    private List<StructureTemplate.StructureEntityInfo> entityInfoList;

    @Unique
    @Nullable
    private SubLevelCaptureMeta aeroschematics$capture;

    @Unique
    private final Map<UUID, SubLevelCaptureMeta> aeroschematics$subLevelMeta = new HashMap<>();

    @Inject(method = "fillFromWorld", at = @At("TAIL"))
    private void aeroschematics$captureSubLevel(Level level, BlockPos pos, Vec3i size, boolean withEntities, @Nullable Block toIgnore, CallbackInfo ci) {
        try {
            this.aeroschematics$capture = CaptureHooks.onFillFromWorld(level, pos, this.palettes, this.entityInfoList);
        } catch (RuntimeException | LinkageError e) {
            AeroSchematics.LOGGER.error("Failed to capture physics structure metadata", e);
            this.aeroschematics$capture = null;
        }
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void aeroschematics$writeCapture(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if (this.aeroschematics$capture != null) {
            tag.put(SubLevelCaptureMeta.KEY, this.aeroschematics$capture.save());
        }
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void aeroschematics$readSubLevelMeta(HolderGetter<Block> blockGetter, CompoundTag tag, CallbackInfo ci) {
        this.aeroschematics$subLevelMeta.clear();
        for (Tag entry : tag.getList("sub_levels", Tag.TAG_COMPOUND)) {
            CompoundTag subLevel = (CompoundTag) entry;
            if (!subLevel.hasUUID("uuid") || !subLevel.contains(SubLevelCaptureMeta.KEY, Tag.TAG_COMPOUND)) {
                continue;
            }
            SubLevelCaptureMeta meta = SubLevelCaptureMeta.load(subLevel.getCompound(SubLevelCaptureMeta.KEY));
            // Only trust metadata that belongs to this entry (a hand-edited file could try to point elsewhere).
            if (meta != null && meta.schematicUuid().equals(subLevel.getUUID("uuid"))) {
                this.aeroschematics$subLevelMeta.put(meta.schematicUuid(), meta);
            }
        }
    }

    @Override
    @Nullable
    public SubLevelCaptureMeta aeroschematics$getCapture() {
        return this.aeroschematics$capture;
    }

    @Override
    public Map<UUID, SubLevelCaptureMeta> aeroschematics$getSubLevelMeta() {
        return this.aeroschematics$subLevelMeta;
    }
}
