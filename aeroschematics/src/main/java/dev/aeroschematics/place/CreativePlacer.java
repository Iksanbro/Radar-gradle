package dev.aeroschematics.place;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.SchematicPrinter;
import dev.aeroschematics.AeroSchematics;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.create.schematics.SchematicLevelExtension;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.create.schematics.SchematicPrinterExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Instant placement of a schematic's physics structures (Create's creative "print" from the schematic tool). Replaces
 * Sable's built-in handler, which only keeps the subset of block entity data Create considers safe for ordinary blocks
 * (so device settings were lost) and does not restore wires.
 */
public final class CreativePlacer {
    private CreativePlacer() {
    }

    /**
     * Places all physics structures of the loaded printer and removes them from Sable's list so that Sable's own
     * handler does nothing afterwards.
     */
    public static void placeAll(ServerPlayer player, SchematicPrinter printer, ItemStack schematic) {
        if (!(player.level() instanceof ServerLevel level) || !printer.isLoaded() || printer.isErrored()) {
            return;
        }
        Mirror mirror = schematic.get(AllDataComponents.SCHEMATIC_MIRROR);
        if (mirror != null && mirror != Mirror.NONE) {
            // Sable refuses mirrored placement of physics structures and cancels the whole placement; let it do so.
            return;
        }
        SubLevelBlueprint blueprint = SubLevelBlueprint.from(printer);
        if (blueprint.isEmpty()) {
            return;
        }
        try {
            place(level, printer.getAnchor(), blueprint);
        } catch (RuntimeException e) {
            AeroSchematics.LOGGER.error("Failed to place the physics structures of a schematic", e);
        } finally {
            ((SchematicLevelExtension) ((SchematicPrinterExtension) printer).sable$getSchematicLevel()).sable$getSubLevels().clear();
        }
    }

    public static PlacementSession place(ServerLevel level, BlockPos anchor, SubLevelBlueprint blueprint) {
        PlacementSession session = new PlacementSession(level, anchor, blueprint);
        // Allocate every structure first: links between structures (bearings, welds, wires) need all of them mapped.
        for (SubLevelBlueprint.Entry entry : blueprint.entries()) {
            session.allocate(entry);
        }
        for (SubLevelBlueprint.Entry entry : blueprint.entries()) {
            for (BlockPos local : entry.order) {
                BlockState state = entry.stateAt(local);
                if (!state.isAir()) {
                    session.placeNow(entry, local, null);
                }
            }
        }
        for (SubLevelBlueprint.Entry entry : blueprint.entries()) {
            session.settlePose(entry);
        }
        for (SubLevelBlueprint.Entry entry : blueprint.entries()) {
            for (Entity entity : entry.level.getEntityList()) {
                Entity copy = session.prepareEntity(entry, entity);
                if (copy != null) {
                    level.addFreshEntity(copy);
                }
            }
        }
        boolean anyWire = false;
        for (PlacementSession.PendingWire wire : session.pendingWires()) {
            anyWire |= session.connect(wire);
        }
        if (anyWire) {
            session.syncWires();
        }
        return session;
    }
}
