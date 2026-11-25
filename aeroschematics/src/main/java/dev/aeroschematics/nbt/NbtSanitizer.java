package dev.aeroschematics.nbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Removes everything from block entity / entity NBT that represents a stored <em>resource</em> (items, fluids, ammunition,
 * fuel, energy) while leaving configuration ("settings") untouched.
 * <p>
 * The sanitizer works in two layers:
 * <ol>
 *     <li>A generic, mod-agnostic deep scan that removes anything shaped like an {@code ItemStack} or a {@code FluidStack}
 *     at any depth, except under keys that are known to hold "ghost" items (filters, frequencies, ...).</li>
 *     <li>Explicit per-mod rules from {@link SanitizerRules} for resources that are not stored as item/fluid stacks, for
 *     example a loaded shell inside a Create Big Cannons barrel (a block state), ammunition counters stored as plain
 *     integers, or thruster fuel stored as an amount + fluid id pair.</li>
 * </ol>
 * The class only depends on NBT classes so it can be unit-tested without a running game.
 */
public final class NbtSanitizer {
    /** Registry lookups, abstracted so that tests do not need a bootstrapped game. */
    public interface Lookup {
        boolean isItem(String id);

        boolean isFluid(String id);
    }

    /** Statistics about what was removed. Useful for logging and tests. */
    public static final class Report {
        public int itemStacks;
        public int fluidStacks;
        public int ruleEdits;
        public final List<String> notes = new ArrayList<>();

        public boolean changedAnything() {
            return itemStacks > 0 || fluidStacks > 0 || ruleEdits > 0;
        }

        public void add(Report other) {
            this.itemStacks += other.itemStacks;
            this.fluidStacks += other.fluidStacks;
            this.ruleEdits += other.ruleEdits;
            this.notes.addAll(other.notes);
        }

        @Override
        public String toString() {
            return "items=" + this.itemStacks + ", fluids=" + this.fluidStacks + ", ruleEdits=" + this.ruleEdits + (this.notes.isEmpty() ? "" : ", " + this.notes);
        }
    }

    private final SanitizerRules rules;
    private final Lookup lookup;

    public NbtSanitizer(SanitizerRules rules, Lookup lookup) {
        this.rules = rules;
        this.lookup = lookup;
    }

    public SanitizerRules rules() {
        return this.rules;
    }

    /**
     * Sanitizes the NBT of one block entity in place.
     *
     * @param blockEntityType registry id of the block entity type, e.g. {@code createbigcannons:cannon_end}
     * @param tag             the block entity data (as produced by {@code saveWithFullMetadata} or found in a template)
     * @param kinetic         whether the block entity is a Create kinetic block entity (runtime rotation state is reset)
     */
    public Report sanitizeBlockEntity(String blockEntityType, CompoundTag tag, boolean kinetic) {
        Report report = new Report();
        SanitizerRules.BlockEntityRule rule = this.rules.ruleFor(blockEntityType);
        if (rule != null) {
            rule.apply(tag, report);
        }
        if (kinetic) {
            for (String key : SanitizerRules.KINETIC_RUNTIME_KEYS) {
                if (tag.contains(key)) {
                    tag.remove(key);
                    report.ruleEdits++;
                }
            }
        }
        Set<String> ghostKeys = this.rules.ghostKeysFor(blockEntityType);
        this.deepStrip(tag, ghostKeys, report, 0);
        return report;
    }

    /**
     * Sanitizes entity NBT in place (item frames, armor stands, contraptions...). Nested block entity data inside
     * contraptions is recognised through its {@code id} and run through the block entity rules as well.
     */
    public Report sanitizeEntity(CompoundTag tag) {
        Report report = new Report();
        this.deepStrip(tag, this.rules.globalGhostKeys(), report, 0);
        return report;
    }

    private void deepStrip(CompoundTag tag, Set<String> ghostKeys, Report report, int depth) {
        if (depth > 64) {
            return; // Pathological nesting, nothing sensible lives this deep.
        }
        // Nested block entity data (e.g. inside a contraption entity) gets its own explicit rules applied.
        if (depth > 0 && tag.contains("id", Tag.TAG_STRING)) {
            SanitizerRules.BlockEntityRule nested = this.rules.ruleFor(tag.getString("id"));
            if (nested != null && !this.looksLikeItemStack(tag) && !this.looksLikeFluidStack(tag)) {
                nested.apply(tag, report);
            }
        }
        for (String key : List.copyOf(tag.getAllKeys())) {
            if (ghostKeys.contains(key)) {
                continue;
            }
            Tag value = tag.get(key);
            if (value instanceof CompoundTag compound) {
                if (this.looksLikeItemStack(compound)) {
                    tag.put(key, new CompoundTag());
                    report.itemStacks++;
                } else if (this.looksLikeFluidStack(compound)) {
                    tag.put(key, emptyFluid(compound));
                    report.fluidStacks++;
                } else {
                    this.deepStrip(compound, ghostKeys, report, depth + 1);
                }
            } else if (value instanceof ListTag list) {
                this.stripList(list, ghostKeys, report, depth + 1);
            }
        }
        cleanUpAfterFluids(tag);
    }

    /**
     * An empty replacement for a fluid stack, in a shape its reader still understands:
     * {@code {}} for NeoForge {@code FluidStack}s (parsed as empty), and an in-place emptied copy (amount 0, fluid
     * {@code minecraft:empty}) for other layouts such as Simulated's {@code {Amount, Variant:{Fluid}}} tanks, whose
     * readers expect the keys to be there.
     */
    static CompoundTag emptyFluid(CompoundTag fluid) {
        if (fluid.contains("id", Tag.TAG_STRING) && fluid.contains("amount", Tag.TAG_ANY_NUMERIC) && !fluid.contains("Variant") && !fluid.contains("variant")) {
            return new CompoundTag();
        }
        CompoundTag emptied = fluid.copy();
        for (String amountKey : AMOUNT_KEYS) {
            if (emptied.get(amountKey) instanceof NumericTag numeric) {
                emptied.put(amountKey, zeroLike(numeric));
            }
        }
        for (String idKey : FLUID_ID_KEYS) {
            if (emptied.contains(idKey, Tag.TAG_STRING)) {
                emptied.putString(idKey, "minecraft:empty");
            }
        }
        for (String variantKey : VARIANT_KEYS) {
            if (emptied.get(variantKey) instanceof CompoundTag variant) {
                CompoundTag emptyVariant = new CompoundTag();
                for (String idKey : FLUID_ID_KEYS) {
                    if (variant.contains(idKey, Tag.TAG_STRING)) {
                        emptyVariant.putString(idKey, "minecraft:empty");
                    }
                }
                emptied.put(variantKey, emptyVariant);
            }
        }
        return emptied;
    }

    private static Tag zeroLike(NumericTag numeric) {
        return switch (numeric.getId()) {
            case Tag.TAG_BYTE -> net.minecraft.nbt.ByteTag.valueOf((byte) 0);
            case Tag.TAG_SHORT -> net.minecraft.nbt.ShortTag.valueOf((short) 0);
            case Tag.TAG_LONG -> net.minecraft.nbt.LongTag.valueOf(0L);
            case Tag.TAG_FLOAT -> net.minecraft.nbt.FloatTag.valueOf(0f);
            case Tag.TAG_DOUBLE -> net.minecraft.nbt.DoubleTag.valueOf(0d);
            default -> net.minecraft.nbt.IntTag.valueOf(0);
        };
    }

    /**
     * State that only makes sense together with fluid that was just removed:
     * <ul>
     *     <li>Create pipes: a {@code Flow} whose fluid is gone is dropped (a fresh pipe has none);</li>
     *     <li>Create tanks of spouts/drains/basins: the rendered fill {@code Level} next to {@code TankContent} goes to 0.</li>
     * </ul>
     */
    private static void cleanUpAfterFluids(CompoundTag tag) {
        if (tag.get("Flow") instanceof CompoundTag flow && flow.contains("Fluid") && flow.getCompound("Fluid").isEmpty()) {
            tag.remove("Flow");
        }
        if (tag.contains("TankContent", Tag.TAG_COMPOUND) && tag.get("Level") instanceof CompoundTag level && level.contains("Value", Tag.TAG_ANY_NUMERIC)) {
            CompoundTag fluidTank = tag.getCompound("TankContent");
            if (!fluidTank.contains("Fluid") || fluidTank.getCompound("Fluid").isEmpty()) {
                level.putFloat("Value", 0f);
                level.putFloat("Target", 0f);
            }
        }
    }

    private void stripList(ListTag list, Set<String> ghostKeys, Report report, int depth) {
        if (list.getElementType() == Tag.TAG_COMPOUND) {
            for (int i = list.size() - 1; i >= 0; i--) {
                CompoundTag element = list.getCompound(i);
                ContentKind kind = this.classifyElement(element);
                if (kind == ContentKind.NONE) {
                    this.deepStrip(element, ghostKeys, report, depth);
                    continue;
                }
                if (kind == ContentKind.ITEM) {
                    report.itemStacks++;
                } else {
                    report.fluidStacks++;
                }
                if (kind == ContentKind.FLUID || hasSlotKey(element) || !this.isStackItself(element)) {
                    // Fluids, and slot-addressed entries ({Slot, id, count}, {slot, item:{...}}, {Slot, Projectile,
                    // Propellant}) are simply dropped: readers iterate entries and look the slot up.
                    list.remove(i);
                } else {
                    // Positional lists (e.g. ArmorItems) keep their size; an empty compound decodes to an empty stack.
                    list.set(i, new CompoundTag());
                }
            }
        } else if (list.getElementType() == Tag.TAG_LIST) {
            for (int i = 0; i < list.size(); i++) {
                this.stripList(list.getList(i), ghostKeys, report, depth);
            }
        }
    }

    private enum ContentKind {
        NONE,
        ITEM,
        FLUID
    }

    private ContentKind classifyElement(CompoundTag element) {
        if (this.looksLikeItemStack(element)) {
            return ContentKind.ITEM;
        }
        if (this.looksLikeFluidStack(element)) {
            return ContentKind.FLUID;
        }
        // Wrapper entries: {Slot: 1b, Item: {...}}, {slot: 0, item: {...}}, {Slot: 2b, Projectile: {...}, Propellant: {...}}
        ContentKind found = ContentKind.NONE;
        for (String key : element.getAllKeys()) {
            if (element.get(key) instanceof CompoundTag child) {
                if (this.looksLikeItemStack(child)) {
                    return ContentKind.ITEM;
                }
                if (this.looksLikeFluidStack(child)) {
                    found = ContentKind.FLUID;
                }
            }
        }
        return found;
    }

    private boolean isStackItself(CompoundTag element) {
        return this.looksLikeItemStack(element) || this.looksLikeFluidStack(element);
    }

    private static boolean hasSlotKey(CompoundTag element) {
        return element.contains("Slot", Tag.TAG_ANY_NUMERIC) || element.contains("slot", Tag.TAG_ANY_NUMERIC);
    }

    /** {@code {id: "minecraft:stone", count: 3, components: {...}}} or the legacy {@code {id, Count:3b, tag}}. */
    public boolean looksLikeItemStack(CompoundTag tag) {
        if (!(tag.get("id") instanceof StringTag idTag)) {
            return false;
        }
        boolean counted = tag.get("count") instanceof NumericTag || tag.get("Count") instanceof NumericTag;
        if (!counted) {
            return false;
        }
        String id = idTag.getAsString();
        return !id.isEmpty() && !id.equals("minecraft:air") && this.lookup.isItem(id);
    }

    private static final List<String> AMOUNT_KEYS = List.of("amount", "Amount");
    private static final List<String> FLUID_ID_KEYS = List.of("id", "FluidName", "Fluid", "fluid");
    private static final List<String> VARIANT_KEYS = List.of("Variant", "variant");

    /**
     * A fluid amount of a registered fluid, in any of the layouts used by the mods:
     * <ul>
     *     <li>NeoForge {@code FluidStack}: {@code {id: "minecraft:water", amount: 1000}},</li>
     *     <li>legacy Forge: {@code {FluidName: "minecraft:water", Amount: 1000}},</li>
     *     <li>multiloader / Fabric style (Simulated's docking connector tank):
     *     {@code {Amount: 1000L, Variant: {Fluid: "minecraft:water"}}}, {@code {amount, variant: {fluid}}},
     *     {@code {Fluid: "...", Amount: ...}}.</li>
     * </ul>
     */
    public boolean looksLikeFluidStack(CompoundTag tag) {
        NumericTag amount = null;
        for (String key : AMOUNT_KEYS) {
            if (tag.get(key) instanceof NumericTag numeric) {
                amount = numeric;
                break;
            }
        }
        if (amount == null || tag.contains("count") || tag.contains("Count")) {
            return false;
        }
        for (String key : FLUID_ID_KEYS) {
            if (tag.get(key) instanceof StringTag idTag && this.isRealFluid(idTag.getAsString())) {
                return true;
            }
        }
        for (String variantKey : VARIANT_KEYS) {
            if (tag.get(variantKey) instanceof CompoundTag variant) {
                for (String key : FLUID_ID_KEYS) {
                    if (variant.get(key) instanceof StringTag idTag && this.isRealFluid(idTag.getAsString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isRealFluid(String id) {
        return !id.isEmpty() && !id.equals("minecraft:empty") && this.lookup.isFluid(id);
    }
}
