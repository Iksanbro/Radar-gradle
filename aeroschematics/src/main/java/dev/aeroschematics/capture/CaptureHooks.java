package dev.aeroschematics.capture;

import dev.aeroschematics.AeroSchematics;
import dev.aeroschematics.compat.DriveByWireCompat;
import dev.aeroschematics.compat.Mods;
import dev.aeroschematics.nbt.AeroSanitizer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Save side. Sable's Create integration fills one {@link StructureTemplate} per physics structure intersecting the
 * schematic selection while a {@link SubLevelSchematicSerializationContext} of type SAVE is active. For each of those
 * templates this class
 * <ul>
 *     <li>strips stored resources from block entities and entities (the file never contains items, ammo, fuel...),</li>
 *     <li>records where the structure lived (plot center, plot square, template origin) so links can be re-targeted,</li>
 *     <li>records its Drive-By-Wire connections.</li>
 * </ul>
 */
public final class CaptureHooks {
    private CaptureHooks() {
    }

    @Nullable
    public static SubLevelCaptureMeta onFillFromWorld(Level level, BlockPos origin, List<StructureTemplate.Palette> palettes,
                                                     List<StructureTemplate.StructureEntityInfo> entities) {
        SubLevelSchematicSerializationContext context = SubLevelSchematicSerializationContext.getCurrentContext();
        if (context == null || context.getType() != SubLevelSchematicSerializationContext.Type.SAVE) {
            return null;
        }
        SubLevel subLevel = Sable.HELPER.getContaining(level, origin);
        if (subLevel == null) {
            return null;
        }
        SubLevelSchematicSerializationContext.SchematicMapping mapping = context.getMapping(subLevel);
        if (mapping == null) {
            // The template is the selection itself, made inside a plot: not one of the captured structures.
            return null;
        }

        try {
            for (StructureTemplate.Palette palette : palettes) {
                for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
                    if (info.nbt() != null) {
                        AeroSanitizer.sanitizeTemplateBlock(info.state(), info.nbt());
                    }
                }
            }
            for (StructureTemplate.StructureEntityInfo entity : entities) {
                AeroSanitizer.sanitizeEntity(entity.nbt);
            }
        } catch (RuntimeException e) {
            AeroSchematics.LOGGER.error("Failed to sanitize physics structure {} while saving a schematic", subLevel, e);
        }

        LevelPlot plot = subLevel.getPlot();
        ChunkPos chunkMin = plot.getChunkMin();
        ChunkPos chunkMax = plot.getChunkMax();
        List<SubLevelCaptureMeta.WireRecord> wires = List.of();
        if (Mods.DRIVE_BY_WIRE.isLoaded()) {
            try {
                wires = DriveByWireCompat.capture(level, subLevel, context);
            } catch (RuntimeException | LinkageError e) {
                AeroSchematics.LOGGER.error("Failed to capture Drive-By-Wire connections of {}", subLevel, e);
            }
        }
        return new SubLevelCaptureMeta(
                SubLevelCaptureMeta.CURRENT_VERSION,
                mapping.newUUID(),
                subLevel.getUniqueId(),
                plot.getCenterBlock(),
                origin.immutable(),
                chunkMin.getMinBlockX(),
                chunkMin.getMinBlockZ(),
                chunkMax.getMaxBlockX() + 1,
                chunkMax.getMaxBlockZ() + 1,
                wires);
    }
}
