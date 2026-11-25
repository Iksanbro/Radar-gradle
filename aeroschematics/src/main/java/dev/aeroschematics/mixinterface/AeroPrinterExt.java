package dev.aeroschematics.mixinterface;

import dev.aeroschematics.capture.SubLevelCaptureMeta;
import java.util.Map;
import java.util.UUID;

/** Implemented by Create's {@code SchematicPrinter} through a mixin. */
public interface AeroPrinterExt {
    Map<UUID, SubLevelCaptureMeta> aeroschematics$getSubLevelMeta();
}
