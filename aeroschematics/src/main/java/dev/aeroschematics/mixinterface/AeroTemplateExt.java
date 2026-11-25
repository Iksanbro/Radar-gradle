package dev.aeroschematics.mixinterface;

import dev.aeroschematics.capture.SubLevelCaptureMeta;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Implemented by {@code StructureTemplate} through a mixin. */
public interface AeroTemplateExt {
    /** Metadata captured for this template when it was filled from a sub-level plot (save side). */
    @Nullable
    SubLevelCaptureMeta aeroschematics$getCapture();

    /** Metadata of the sub-levels found in {@code sub_levels} when this template was loaded, keyed by schematic id. */
    Map<UUID, SubLevelCaptureMeta> aeroschematics$getSubLevelMeta();
}
