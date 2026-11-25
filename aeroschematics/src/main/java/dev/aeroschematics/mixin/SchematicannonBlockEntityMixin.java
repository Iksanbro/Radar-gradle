package dev.aeroschematics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.schematics.SchematicPrinter;
import com.simibubi.create.content.schematics.cannon.MaterialChecklist;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonInventory;
import dev.aeroschematics.AeroSchematics;
import dev.aeroschematics.mixinterface.AeroCannonExt;
import dev.aeroschematics.place.SubLevelBlueprint;
import dev.aeroschematics.print.SubLevelPrintStage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicannonBlockEntity.class, remap = false)
public abstract class SchematicannonBlockEntityMixin implements AeroCannonExt {
    @Unique
    private static final String AEROSCHEMATICS$TAG = "AeroSchematicsStage";

    @Shadow
    public SchematicPrinter printer;
    @Shadow
    public SchematicannonInventory inventory;
    @Shadow
    public SchematicannonBlockEntity.State state;
    @Shadow
    public String statusMsg;
    @Shadow
    public boolean positionNotLoaded;
    @Shadow
    public ItemStack missingItem;
    @Shadow
    public int remainingFuel;
    @Shadow
    public MaterialChecklist checklist;
    @Shadow
    public int blocksToPlace;

    @Unique
    private final SubLevelPrintStage aeroschematics$stage = new SubLevelPrintStage();

    @Override
    public SubLevelPrintStage aeroschematics$getStage() {
        return this.aeroschematics$stage;
    }

    @Unique
    private SchematicannonBlockEntity aeroschematics$self() {
        return (SchematicannonBlockEntity) (Object) this;
    }

    /** While physics structures are being printed, this stage replaces Create's per-block logic. */
    @Inject(method = "tickPrinter", at = @At("HEAD"), cancellable = true)
    private void aeroschematics$tickStage(CallbackInfo ci) {
        if (!this.aeroschematics$stage.isActive() || !this.printer.isLoaded()) {
            return;
        }
        ItemStack blueprint = this.inventory.getStackInSlot(0);
        if (blueprint.isEmpty() || this.state == SchematicannonBlockEntity.State.STOPPED) {
            return; // Let Create stop/reset the printer (our reset hook follows).
        }
        ci.cancel();
        if (this.state == SchematicannonBlockEntity.State.PAUSED && !this.positionNotLoaded && this.missingItem == null && this.remainingFuel > 0) {
            return; // Paused by the player.
        }
        this.aeroschematics$stage.tick(this.aeroschematics$self());
    }

    /** Create finished the ordinary blocks: continue with the physics structures before finishing. */
    @WrapOperation(
            method = "tickPrinter",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity;finishedPrinting()V")
    )
    private void aeroschematics$continueWithSubLevels(SchematicannonBlockEntity cannon, Operation<Void> original) {
        if (this.aeroschematics$stage.hasPendingWork()) {
            this.aeroschematics$stage.begin(cannon);
            return;
        }
        original.call(cannon);
    }

    /** A schematic containing only physics structures has no ordinary blocks; it is not "expired". */
    @WrapOperation(
            method = "initializePrinter",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/schematics/SchematicPrinter;isWorldEmpty()Z")
    )
    private boolean aeroschematics$notEmptyWithSubLevels(SchematicPrinter printer, Operation<Boolean> original) {
        return original.call(printer) && !SubLevelBlueprint.hasSubLevels(printer);
    }

    @Inject(method = "initializePrinter", at = @At("TAIL"))
    private void aeroschematics$printerLoaded(ItemStack blueprint, CallbackInfo ci) {
        if (this.printer.isLoaded() && !this.printer.isErrored() && this.state != SchematicannonBlockEntity.State.STOPPED) {
            try {
                this.aeroschematics$stage.onPrinterLoaded(this.aeroschematics$self());
            } catch (RuntimeException e) {
                AeroSchematics.LOGGER.error("Failed to prepare the physics structures of a schematic", e);
            }
        }
    }

    @Inject(method = "resetPrinter", at = @At("TAIL"))
    private void aeroschematics$reset(CallbackInfo ci) {
        this.aeroschematics$stage.reset(this.aeroschematics$self());
    }

    @Inject(method = "updateChecklist", at = @At("TAIL"))
    private void aeroschematics$addSubLevelRequirements(CallbackInfo ci) {
        try {
            this.blocksToPlace += this.aeroschematics$stage.addRequirements(this.aeroschematics$self(), this.checklist);
        } catch (RuntimeException e) {
            AeroSchematics.LOGGER.error("Failed to list the materials of the physics structures of a schematic", e);
        }
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void aeroschematics$save(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket && this.aeroschematics$stage.isActive()) {
            compound.put(AEROSCHEMATICS$TAG, this.aeroschematics$stage.save());
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void aeroschematics$load(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket && compound.contains(AEROSCHEMATICS$TAG, Tag.TAG_COMPOUND)) {
            this.aeroschematics$stage.load(compound.getCompound(AEROSCHEMATICS$TAG));
        }
    }
}
