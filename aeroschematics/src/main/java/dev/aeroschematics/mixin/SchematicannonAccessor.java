package dev.aeroschematics.mixin;

import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = SchematicannonBlockEntity.class, remap = false)
public interface SchematicannonAccessor {
    @Accessor("printerCooldown")
    int aeroschematics$getPrinterCooldown();

    @Accessor("printerCooldown")
    void aeroschematics$setPrinterCooldown(int cooldown);

    @Accessor("blockSkipped")
    void aeroschematics$setBlockSkipped(boolean skipped);

    @Invoker("refillFuelIfPossible")
    void aeroschematics$refillFuelIfPossible();

    @Invoker("grabItemsFromAttachedInventories")
    boolean aeroschematics$grabItems(ItemRequirement.StackRequirement required, boolean simulate);
}
