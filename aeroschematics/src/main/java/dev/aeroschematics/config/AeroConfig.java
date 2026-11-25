package dev.aeroschematics.config;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Common config (read on both sides: saving happens on the client, printing on the server). */
public final class AeroConfig {
    public enum WireCharging {
        /** Charge one wire item per restored connection when Drive-By-Wire itself consumes wires. */
        AUTO,
        ALWAYS,
        NEVER
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXTRA_STRIP_RULES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXTRA_GHOST_KEYS;
    public static final ModConfigSpec.BooleanValue UNLOCK_AFTER_PRINTING;
    public static final ModConfigSpec.EnumValue<WireCharging> WIRE_CHARGING;
    public static final ModConfigSpec.BooleanValue RESTORE_WIRES;
    public static final ModConfigSpec.BooleanValue LOG_SANITIZING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("sanitizing");
        EXTRA_STRIP_RULES = builder
                .comment("Additional block entity keys holding stored resources that must never be copied.",
                        "Format: 'namespace:block_entity_type#Key' (remove), 'namespace#Key' (whole mod),",
                        "append '=0' to zero a numeric counter instead of removing it, e.g. 'mymod:tank#StoredAmount=0'.")
                .defineListAllowEmpty("extraStripRules", List.of(), () -> "", o -> o instanceof String s && s.contains("#"));
        EXTRA_GHOST_KEYS = builder
                .comment("Additional NBT keys holding 'ghost' items (configuration copies, never given back to players).",
                        "Items under these keys are kept. Only add keys you are sure about: wrong entries allow duplication.")
                .defineListAllowEmpty("extraGhostKeys", List.of(), () -> "", o -> o instanceof String);
        LOG_SANITIZING = builder
                .comment("Log every block entity whose stored contents were removed while saving or printing.")
                .define("logSanitizing", false);
        builder.pop();

        builder.push("printing");
        UNLOCK_AFTER_PRINTING = builder
                .comment("Release the printed physics structure once the schematicannon has finished it.",
                        "While printing it is always held in place. If false it stays locked (creative physics staff unlocks it).")
                .define("unlockAfterPrinting", true);
        RESTORE_WIRES = builder
                .comment("Restore Drive-By-Wire connections stored in schematics.")
                .define("restoreWires", true);
        WIRE_CHARGING = builder
                .comment("Whether restoring a Drive-By-Wire connection costs one wire item in survival.",
                        "AUTO follows Drive-By-Wire's own 'shouldConsumeWires' setting.")
                .defineEnum("wireCharging", WireCharging.AUTO);
        builder.pop();
        SPEC = builder.build();
    }

    private AeroConfig() {
    }
}
