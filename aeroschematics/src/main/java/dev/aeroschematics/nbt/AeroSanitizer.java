package dev.aeroschematics.nbt;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.aeroschematics.AeroSchematics;
import dev.aeroschematics.config.AeroConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Game-side entry point to {@link NbtSanitizer} (registry lookups + config). */
public final class AeroSanitizer {
    public static final NbtSanitizer.Lookup REGISTRY_LOOKUP = new NbtSanitizer.Lookup() {
        @Override
        public boolean isItem(String id) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
        }

        @Override
        public boolean isFluid(String id) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            return rl != null && BuiltInRegistries.FLUID.containsKey(rl);
        }
    };

    private static volatile NbtSanitizer instance = build();

    private AeroSanitizer() {
    }

    private static NbtSanitizer build() {
        SanitizerRules rules = SanitizerRules.defaults();
        try {
            rules.addConfigEntries(AeroConfig.EXTRA_STRIP_RULES.get());
            rules.globalGhost(AeroConfig.EXTRA_GHOST_KEYS.get().stream().map(String::valueOf).toList());
        } catch (IllegalStateException notLoadedYet) {
            // Config not loaded yet (early class loading); defaults only. reload() is called once it is.
        }
        return new NbtSanitizer(rules, REGISTRY_LOOKUP);
    }

    public static void reload() {
        instance = build();
    }

    public static NbtSanitizer get() {
        return instance;
    }

    public static String typeId(BlockEntityType<?> type) {
        ResourceLocation key = BlockEntityType.getKey(type);
        return key == null ? "unknown:unknown" : key.toString();
    }

    /** Sanitizes NBT that was saved from a live (or schematic-level) block entity. */
    public static NbtSanitizer.Report sanitize(BlockEntity blockEntity, CompoundTag tag) {
        NbtSanitizer.Report report = get().sanitizeBlockEntity(typeId(blockEntity.getType()), tag, blockEntity instanceof KineticBlockEntity);
        log(typeId(blockEntity.getType()), report);
        return report;
    }

    /** Sanitizes block entity NBT found in a structure template (which carries its type in {@code id}). */
    public static NbtSanitizer.Report sanitizeTemplateBlock(BlockState state, CompoundTag tag) {
        String type = tag.contains("id", Tag.TAG_STRING) ? tag.getString("id") : fallbackType(state);
        NbtSanitizer.Report report = get().sanitizeBlockEntity(type, tag, state.getBlock() instanceof IRotate);
        log(type, report);
        return report;
    }

    public static NbtSanitizer.Report sanitizeEntity(CompoundTag tag) {
        NbtSanitizer.Report report = get().sanitizeEntity(tag);
        log(tag.getString("id"), report);
        return report;
    }

    private static String fallbackType(@Nullable BlockState state) {
        if (state == null) {
            return "unknown:unknown";
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static void log(String what, NbtSanitizer.Report report) {
        if (report.changedAnything() && isLogging()) {
            AeroSchematics.LOGGER.info("Removed stored contents from {}: {}", what, report);
        }
    }

    private static boolean isLogging() {
        try {
            return AeroConfig.LOG_SANITIZING.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
