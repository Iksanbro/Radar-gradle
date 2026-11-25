package dev.aeroschematics.nbt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/**
 * Explicit sanitizing rules. Every rule below was derived from the {@code write}/{@code saveAdditional} code of the
 * respective mod version (Create 6.0.10, Create Aeronautics/Simulated/Offroad 1.3.3, Sable 2.0.6, Create: Flight Control
 * 0.8.5, Create Big Cannons 5.11.7, CBC: Firepower Components 0.4.4, Drive-By-Wire (Sable) 0.3.0).
 * <p>
 * Rules are keyed either by an exact block entity type id ({@code namespace:path}) or by a namespace (applies to every
 * block entity type of that mod). Server owners can add more through the config (see {@code AeroConfig}).
 */
public final class SanitizerRules {
    /**
     * Keys written by Create's {@code KineticBlockEntity}: rotation speed, stress network membership and the position of
     * the rotation source. Restoring them on a freshly printed block would leave it spinning / attached to a network
     * that does not exist (and the source position would point into the old plot).
     */
    public static final List<String> KINETIC_RUNTIME_KEYS = List.of("Speed", "Source", "Network", "NeedsSpeedUpdate", "Sequence");

    /**
     * Keys that hold "ghost" items: copies used purely as configuration, which the player never gets back. Keeping them
     * is exactly what vanilla Create does for its own blocks.
     * <ul>
     *     <li>{@code Filter} - Create filtering behaviour (funnels, deployers, smart chutes...).</li>
     *     <li>{@code FrequencyFirst}/{@code FrequencyLast} - Create redstone link frequencies.</li>
     *     <li>{@code ControllerData} - frequencies of a Linked Controller sitting on a lectern (the controller item itself
     *     is charged by the lectern's schematic requirement).</li>
     *     <li>{@code afnProbeItems}/{@code afnFrequencyItems} - Flight Control AFN frequency slots (menu copies the
     *     carried stack with count 1 and never lets the player take it out).</li>
     *     <li>{@code Keys} - Simulated linked typewriter key frequencies.</li>
     * </ul>
     */
    public static final Set<String> DEFAULT_GHOST_KEYS = Set.of(
            "Filter", "FrequencyFirst", "FrequencyLast", "ControllerData", "afnProbeItems", "afnFrequencyItems", "Keys");

    private final Map<String, BlockEntityRule> byType = new HashMap<>();
    private final Map<String, BlockEntityRule> byNamespace = new HashMap<>();
    private final Map<String, Set<String>> extraGhostKeysByType = new HashMap<>();
    private final Set<String> globalGhostKeys = new HashSet<>(DEFAULT_GHOST_KEYS);

    /** What to do with a single root key of a block entity tag. */
    public enum Action {
        /** Remove the key entirely. */
        REMOVE,
        /** Set a numeric key to zero (counters like "ammo count" / "burn time" / "stored energy"). */
        ZERO
    }

    public record KeyEdit(String key, Action action) {
    }

    /** A set of root-level edits for one block entity type or namespace. */
    public static final class BlockEntityRule {
        private final List<KeyEdit> edits = new ArrayList<>();
        /**
         * Composite condition used for Create Big Cannons' {@code BigCannonBehavior}: the loaded projectile / charge is
         * stored as {@code Pos} (long) + {@code State} (block state) + {@code Data} (its block entity data). Other block
         * entities of the same mod use {@code Pos} differently, so these keys are only removed when {@code State} is a
         * block state compound.
         */
        private boolean stripContainedBlock;

        public BlockEntityRule remove(String... keys) {
            for (String key : keys) {
                this.edits.add(new KeyEdit(key, Action.REMOVE));
            }
            return this;
        }

        public BlockEntityRule zero(String... keys) {
            for (String key : keys) {
                this.edits.add(new KeyEdit(key, Action.ZERO));
            }
            return this;
        }

        public BlockEntityRule stripContainedBlock() {
            this.stripContainedBlock = true;
            return this;
        }

        public List<KeyEdit> edits() {
            return this.edits;
        }

        BlockEntityRule merge(BlockEntityRule other) {
            BlockEntityRule merged = new BlockEntityRule();
            merged.edits.addAll(this.edits);
            merged.edits.addAll(other.edits);
            merged.stripContainedBlock = this.stripContainedBlock || other.stripContainedBlock;
            return merged;
        }

        void apply(CompoundTag tag, NbtSanitizer.Report report) {
            for (KeyEdit edit : this.edits) {
                if (!tag.contains(edit.key())) {
                    continue;
                }
                switch (edit.action()) {
                    case REMOVE -> {
                        tag.remove(edit.key());
                        report.ruleEdits++;
                    }
                    case ZERO -> {
                        Tag value = tag.get(edit.key());
                        if (value instanceof NumericTag numeric && numeric.getAsDouble() != 0) {
                            switch (value.getId()) {
                                case Tag.TAG_BYTE -> tag.putByte(edit.key(), (byte) 0);
                                case Tag.TAG_SHORT -> tag.putShort(edit.key(), (short) 0);
                                case Tag.TAG_LONG -> tag.putLong(edit.key(), 0L);
                                case Tag.TAG_FLOAT -> tag.putFloat(edit.key(), 0f);
                                case Tag.TAG_DOUBLE -> tag.putDouble(edit.key(), 0d);
                                default -> tag.putInt(edit.key(), 0);
                            }
                            report.ruleEdits++;
                        }
                    }
                }
            }
            if (this.stripContainedBlock && tag.contains("State", Tag.TAG_COMPOUND) && tag.getCompound("State").contains("Name", Tag.TAG_STRING)) {
                tag.remove("State");
                tag.remove("Pos");
                tag.remove("Data");
                report.ruleEdits++;
                report.notes.add("loaded munition removed");
            }
        }
    }

    public static SanitizerRules defaults() {
        SanitizerRules rules = new SanitizerRules();

        // ---- Create ------------------------------------------------------------------------------------------------
        // Blaze burner fuel (remaining burn time and the fuel level it produces).
        rules.type("create:blaze_heater", new BlockEntityRule().zero("burnTimeRemaining", "fuelLevel"));
        // The player currently using a lectern controller.
        rules.type("create:lectern_controller", new BlockEntityRule().remove("User"));

        // ---- Create Big Cannons -------------------------------------------------------------------------------------
        // BigCannonBehavior: loaded shell / powder charge stored as a block (Pos + State + Data).
        // ItemCannonBehavior: autocannon round inside a barrel ("ContainedStack").
        // Autocannon breech: "Input" buffer, "Output" round, "Magazine" (an item).
        // Autocannon ammo container: "Ammo" + "Tracers" stacks (CBC's own writeSafe keeps them!).
        // Fluid shells / cannon drill: "FluidContent" tank.
        BlockEntityRule cbc = new BlockEntityRule()
                .stripContainedBlock()
                .remove("ContainedStack", "Input", "Output", "Magazine", "Ammo", "Tracers", "FluidContent", "Fuze", "Tracer");
        rules.namespace("createbigcannons", cbc);

        // ---- CBC: Firepower Components ------------------------------------------------------------------------------
        // Most of its block entities extend CBC ones (same keys). In addition:
        //  * ready ammunition compartment / carousel rack: "Rounds" + the selected round copies,
        //  * large autocannon ammo box: counters stored as plain integers (would resurrect the ammo without stacks),
        //  * magazine loader / spent casing collector: "Items".
        rules.namespace("cbc_firepower_components", new BlockEntityRule()
                .stripContainedBlock()
                .remove("ContainedStack", "Input", "Output", "Magazine", "Ammo", "Tracers", "FluidContent", "Fuze", "Tracer")
                .remove("Rounds", "SelectedProjectile", "SelectedPropellant", "Items")
                .zero("LargeMainAmmoCount", "LargeTracerAmmoCount", "InsertionCursor", "PendingCartridgeGroup"));

        // ---- Create: Flight Control ---------------------------------------------------------------------------------
        // Smart vector thruster fuel (amount + fluid id) and ion thruster stored energy.
        rules.namespace("create_flight_control", new BlockEntityRule()
                .zero("fluidAmountMb", "energyStored")
                .remove("storedFluidId"));

        // ---- Drive-By-Wire ------------------------------------------------------------------------------------------
        // The wire network backup block re-creates wires on its own when placed (without charging wire items). Wires are
        // captured and restored by this mod instead, so the backup payload is dropped.
        rules.type("drivebywire:backup_block", new BlockEntityRule().remove("WireNetwork"));

        // ---- Create Aeronautics / Simulated / Offroad --------------------------------------------------------------
        // Portable engine fuel, docking connector battery charge.
        rules.namespace("simulated", new BlockEntityRule().zero("BurnTime").remove("Battery"));

        // Charged content: these blocks charge the held item in their schematic requirement (StrictNbtStackRequirement),
        // so the item is paid for by the schematicannon and may stay.
        rules.ghost("offroad:wheel_mount", "CurrentStack");
        rules.ghost("simulated:navigation_table", "CurrentStack");
        return rules;
    }

    public SanitizerRules type(String blockEntityType, BlockEntityRule rule) {
        this.byType.merge(blockEntityType, rule, BlockEntityRule::merge);
        return this;
    }

    public SanitizerRules namespace(String namespace, BlockEntityRule rule) {
        this.byNamespace.merge(namespace, rule, BlockEntityRule::merge);
        return this;
    }

    public SanitizerRules ghost(String blockEntityType, String... keys) {
        this.extraGhostKeysByType.computeIfAbsent(blockEntityType, k -> new HashSet<>()).addAll(List.of(keys));
        return this;
    }

    public SanitizerRules globalGhost(Collection<String> keys) {
        this.globalGhostKeys.addAll(keys);
        return this;
    }

    /**
     * Parses config entries of the form {@code namespace:path#Key}, {@code namespace:path#Key=0},
     * {@code namespace#Key} or {@code namespace#Key=0}.
     */
    public SanitizerRules addConfigEntries(Collection<? extends String> entries) {
        for (String raw : entries) {
            String entry = raw.trim();
            int hash = entry.indexOf('#');
            if (hash <= 0 || hash == entry.length() - 1) {
                continue;
            }
            String target = entry.substring(0, hash);
            String keyPart = entry.substring(hash + 1);
            BlockEntityRule rule = new BlockEntityRule();
            if (keyPart.endsWith("=0")) {
                rule.zero(keyPart.substring(0, keyPart.length() - 2));
            } else {
                rule.remove(keyPart);
            }
            if (target.contains(":")) {
                this.type(target, rule);
            } else {
                this.namespace(target, rule);
            }
        }
        return this;
    }

    public BlockEntityRule ruleFor(String blockEntityType) {
        BlockEntityRule exact = this.byType.get(blockEntityType);
        int colon = blockEntityType.indexOf(':');
        BlockEntityRule ns = colon > 0 ? this.byNamespace.get(blockEntityType.substring(0, colon)) : null;
        if (exact != null && ns != null) {
            return ns.merge(exact);
        }
        return exact != null ? exact : ns;
    }

    public Set<String> ghostKeysFor(String blockEntityType) {
        Set<String> extra = this.extraGhostKeysByType.get(blockEntityType);
        if (extra == null) {
            return this.globalGhostKeys;
        }
        Set<String> merged = new LinkedHashSet<>(this.globalGhostKeys);
        merged.addAll(extra);
        return merged;
    }

    public Set<String> globalGhostKeys() {
        return this.globalGhostKeys;
    }
}
