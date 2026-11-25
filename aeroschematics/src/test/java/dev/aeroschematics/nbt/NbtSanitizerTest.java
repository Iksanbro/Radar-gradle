package dev.aeroschematics.nbt;

import static dev.aeroschematics.nbt.TestNbt.blockState;
import static dev.aeroschematics.nbt.TestNbt.fluid;
import static dev.aeroschematics.nbt.TestNbt.kineticRuntime;
import static dev.aeroschematics.nbt.TestNbt.pos;
import static dev.aeroschematics.nbt.TestNbt.slotted;
import static dev.aeroschematics.nbt.TestNbt.stack;
import static dev.aeroschematics.nbt.TestNbt.stackWithComponent;
import static dev.aeroschematics.nbt.TestNbt.strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every test builds block entity NBT in the exact shape the respective mod writes it (see the class/method named in the
 * test) and checks that stored resources are gone while configuration survives.
 */
class NbtSanitizerTest {
    private final NbtSanitizer sanitizer = TestNbt.sanitizer();

    /** Recursively searches for anything that still looks like an item or fluid stack. */
    private void assertNoContents(CompoundTag tag) {
        assertFalse(this.containsStack(tag), () -> "stored contents left in " + tag);
    }

    private boolean containsStack(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            if (this.sanitizer.looksLikeItemStack(compound) || this.sanitizer.looksLikeFluidStack(compound)) {
                return true;
            }
            for (String key : compound.getAllKeys()) {
                if (this.containsStack(compound.get(key))) {
                    return true;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                if (this.containsStack(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nested
    class CreateBigCannons {
        @Test
        void loadedShellInBarrelIsRemovedButCannonStructureIsKept() {
            // CannonBehavior + BigCannonBehavior#write: Facing, Connections, Welds + Pos/State/Data of the loaded block.
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "createbigcannons:cannon");
            tag.putString("Facing", "north");
            tag.put("Connections", strings("north", "south"));
            tag.put("Welds", strings("south"));
            tag.put("Pos", pos(new BlockPos(0, 0, 0)));
            tag.put("State", blockState("createbigcannons:he_shell"));
            CompoundTag shellData = new CompoundTag();
            shellData.put("Fuze", stack("createbigcannons:impact_fuze", 1));
            shellData.put("Tracer", new CompoundTag());
            tag.put("Data", shellData);

            NbtSanitizer.Report report = sanitizer.sanitizeBlockEntity("createbigcannons:cannon", tag, false);

            assertFalse(tag.contains("State"), "loaded shell block state must be gone");
            assertFalse(tag.contains("Pos"));
            assertFalse(tag.contains("Data"));
            assertEquals("north", tag.getString("Facing"));
            assertEquals(2, tag.getList("Connections", Tag.TAG_STRING).size());
            assertEquals(1, tag.getList("Welds", Tag.TAG_STRING).size());
            assertTrue(report.notes.contains("loaded munition removed"));
            assertNoContents(tag);
        }

        @Test
        void powderChargeInChamberIsRemoved() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Facing", "up");
            tag.put("Pos", pos(new BlockPos(0, 1, 0)));
            tag.put("State", blockState("createbigcannons:powder_charge"));
            sanitizer.sanitizeBlockEntity("createbigcannons:cannon", tag, false);
            assertFalse(tag.contains("State"));
        }

        @Test
        void autocannonBreechBuffersAreEmptiedButFiringRateKept() {
            // AbstractAutocannonBreechBlockEntity#write
            CompoundTag tag = new CompoundTag();
            tag.putInt("FiringRate", 6);
            tag.putInt("Cooldown", 3);
            tag.putString("Seat", "south");
            tag.put("Output", stack("createbigcannons:ap_autocannon_round", 1));
            ListTag input = new ListTag();
            input.add(stack("createbigcannons:ap_autocannon_round", 1));
            input.add(stack("createbigcannons:ap_autocannon_round", 1));
            tag.put("Input", input);
            tag.put("Magazine", stackWithComponent("createbigcannons:autocannon_cartridge_sheet", 1, "minecraft:container", new CompoundTag()));
            kineticRuntime(tag, new BlockPos(1, 2, 3));

            sanitizer.sanitizeBlockEntity("createbigcannons:autocannon_breech", tag, true);

            assertEquals(6, tag.getInt("FiringRate"));
            assertEquals("south", tag.getString("Seat"));
            assertFalse(tag.contains("Output"));
            assertFalse(tag.contains("Input"));
            assertFalse(tag.contains("Magazine"));
            assertFalse(tag.contains("Speed"), "kinetic runtime state must be reset");
            assertFalse(tag.contains("Network"));
            assertFalse(tag.contains("Source"));
            assertNoContents(tag);
        }

        @Test
        void roundInAutocannonBarrelIsRemoved() {
            // ItemCannonBehavior#write: ContainedStack
            CompoundTag tag = new CompoundTag();
            tag.put("ContainedStack", stack("createbigcannons:ap_autocannon_round", 1));
            tag.put("Connections", strings("north"));
            sanitizer.sanitizeBlockEntity("createbigcannons:autocannon", tag, false);
            assertFalse(tag.contains("ContainedStack"));
            assertTrue(tag.contains("Connections"));
        }

        @Test
        void ammoContainerIsEmptiedButTracerSpacingKept() {
            // AutocannonAmmoContainerBlockEntity#saveAdditional (its own writeSafe keeps the ammo!)
            CompoundTag tag = new CompoundTag();
            tag.put("Ammo", stack("createbigcannons:ap_autocannon_round", 64));
            tag.put("Tracers", stack("createbigcannons:ap_autocannon_round", 16));
            tag.putInt("TracerSpacing", 4);
            sanitizer.sanitizeBlockEntity("createbigcannons:autocannon_ammo_container", tag, false);
            assertFalse(tag.contains("Ammo"));
            assertFalse(tag.contains("Tracers"));
            assertEquals(4, tag.getInt("TracerSpacing"));
        }

        @Test
        void fluidShellAndDrillLubricantAreEmptied() {
            CompoundTag shell = new CompoundTag();
            CompoundTag tank = new CompoundTag();
            tank.put("Fluid", fluid("minecraft:lava", 1000));
            shell.put("FluidContent", tank);
            shell.put("Fuze", stack("createbigcannons:timed_fuze", 1));
            sanitizer.sanitizeBlockEntity("createbigcannons:fluid_shell", shell, false);
            assertNoContents(shell);
        }

        @Test
        void cartridgePowerIsChargedByItsRequirementAndKept() {
            // BigCartridgeBlockEntity: Power is part of the StrictNbtStackRequirement, so it is paid for.
            CompoundTag tag = new CompoundTag();
            tag.putInt("Power", 3);
            sanitizer.sanitizeBlockEntity("createbigcannons:big_cartridge", tag, false);
            assertEquals(3, tag.getInt("Power"));
        }

        @Test
        void cannonMountSettingsSurvive() {
            CompoundTag tag = new CompoundTag();
            tag.putFloat("CannonYaw", 45f);
            tag.putFloat("CannonPitch", -10f);
            tag.putBoolean("Running", false);
            kineticRuntime(tag, new BlockPos(0, 0, 0));
            sanitizer.sanitizeBlockEntity("createbigcannons:cannon_mount", tag, true);
            assertEquals(45f, tag.getFloat("CannonYaw"));
            assertEquals(-10f, tag.getFloat("CannonPitch"));
        }
    }

    @Nested
    class FirepowerComponents {
        @Test
        void largeAmmoBoxCountersAreZeroed() {
            // LargeAutocannonAmmoBoxBlockEntity: counters restore the ammo even without stacks.
            CompoundTag tag = new CompoundTag();
            tag.put("Ammo", stack("cbc_firepower_components:large_autocannon_ap_round", 64));
            tag.put("Tracers", stack("cbc_firepower_components:large_autocannon_he_round", 8));
            tag.putInt("TracerSpacing", 2);
            tag.putInt("LargeMainAmmoCount", 64);
            tag.putInt("LargeTracerAmmoCount", 8);
            sanitizer.sanitizeBlockEntity("cbc_firepower_components:large_autocannon_ammo_box", tag, false);
            assertEquals(0, tag.getInt("LargeMainAmmoCount"));
            assertEquals(0, tag.getInt("LargeTracerAmmoCount"));
            assertEquals(2, tag.getInt("TracerSpacing"));
            assertNoContents(tag);
        }

        @Test
        void readyAmmunitionCompartmentIsEmptied() {
            // ReadyAmmunitionCompartmentBlockEntity#saveAdditional
            CompoundTag tag = new CompoundTag();
            ListTag rounds = new ListTag();
            for (int i = 0; i < 3; i++) {
                CompoundTag round = new CompoundTag();
                round.putByte("Slot", (byte) i);
                round.put("Projectile", stack("createbigcannons:ap_shell", 1));
                round.put("Propellant", stack("createbigcannons:powder_charge", 1));
                rounds.add(round);
            }
            tag.put("Rounds", rounds);
            tag.putInt("InsertionCursor", 3);
            tag.put("SelectedProjectile", stack("createbigcannons:ap_shell", 1));
            tag.put("SelectedPropellant", stack("createbigcannons:powder_charge", 1));
            sanitizer.sanitizeBlockEntity("cbc_firepower_components:ready_ammunition_compartment", tag, false);
            assertFalse(tag.contains("Rounds"));
            assertFalse(tag.contains("SelectedProjectile"));
            assertEquals(0, tag.getInt("InsertionCursor"));
            assertNoContents(tag);
        }

        @Test
        void carouselRackIsEmptiedAndStopped() {
            CompoundTag tag = new CompoundTag();
            ListTag rounds = new ListTag();
            CompoundTag round = new CompoundTag();
            round.putByte("Slot", (byte) 5);
            round.put("Projectile", stack("createbigcannons:he_shell", 1));
            rounds.add(round);
            tag.put("Rounds", rounds);
            tag.putInt("CurrentIndex", 5);
            tag.putInt("TargetIndex", 7);
            kineticRuntime(tag, new BlockPos(5, 5, 5));
            sanitizer.sanitizeBlockEntity("cbc_firepower_components:carousel_ammunition_rack", tag, true);
            assertFalse(tag.contains("Rounds"));
            assertFalse(tag.contains("Speed"));
            assertEquals(5, tag.getInt("CurrentIndex"));
            assertNoContents(tag);
        }

        @Test
        void magazineLoaderAndCasingCollectorAreEmptied() {
            CompoundTag loader = new CompoundTag();
            ListTag items = new ListTag();
            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) 0);
            entry.put("Item", stack("createbigcannons:big_cartridge", 1));
            items.add(entry);
            loader.put("Items", items);
            loader.putBoolean("AutomaticLocked", true);
            loader.putInt("PendingCartridgeGroup", 2);
            sanitizer.sanitizeBlockEntity("cbc_firepower_components:cannon_magazine_loader", loader, false);
            assertTrue(loader.getBoolean("AutomaticLocked"));
            assertNoContents(loader);

            CompoundTag collector = new CompoundTag();
            collector.put("Items", items.copy());
            sanitizer.sanitizeBlockEntity("cbc_firepower_components:spent_casing_collector", collector, false);
            assertNoContents(collector);
        }

        @Test
        void controllerSettingsSurvive() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Mode", 2);
            tag.putInt("CoordinationMode", 1);
            tag.putInt("OutputSignalStrength", 15);
            sanitizer.sanitizeBlockEntity("cbc_firepower_components:automatic_cannon_controller", tag, false);
            assertEquals(2, tag.getInt("Mode"));
            assertEquals(1, tag.getInt("CoordinationMode"));
            assertEquals(15, tag.getInt("OutputSignalStrength"));
        }
    }

    @Nested
    class FlightControl {
        @Test
        void thrusterFuelIsDrainedButSettingsKept() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("fluidAmountMb", 8000);
            tag.putString("storedFluidId", "create_flight_control:kerosene");
            tag.putInt("smi", 2);
            tag.putDouble("smarx", 1.5);
            tag.putFloat("maxThrustPercent", 0.75f);
            sanitizer.sanitizeBlockEntity("create_flight_control:smart_vector_thruster", tag, true);
            assertEquals(0, tag.getInt("fluidAmountMb"));
            assertFalse(tag.contains("storedFluidId"));
            assertEquals(0.75f, tag.getFloat("maxThrustPercent"));
            assertEquals(2, tag.getInt("smi"));
        }

        @Test
        void ionThrusterEnergyIsDrained() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("energyStored", 50000);
            sanitizer.sanitizeBlockEntity("create_flight_control:smart_vector_ion_thruster", tag, false);
            assertEquals(0, tag.getInt("energyStored"));
        }

        @Test
        void afnFrequencyGhostSlotsAreKept() {
            // NavigationProfileBuilder: afnProbeItems is a SimpleContainer of ghost copies (menu forbids taking them).
            CompoundTag tag = new CompoundTag();
            ListTag ghosts = new ListTag();
            ghosts.add(slotted(0, stack("create_afn:frequency_card", 1)));
            tag.put("afnProbeItems", ghosts);
            tag.putString("templateName", "patrol");
            sanitizer.sanitizeBlockEntity("create_flight_control:navigation_profile_builder", tag, false);
            assertEquals(1, tag.getList("afnProbeItems", Tag.TAG_COMPOUND).size());
        }

        @Test
        void sailConsumedMaterialItemIsRemovedButGeometryKept() {
            // IntegratedSailBlockEntity: "Item" is returned when the sail is broken -> would be free wool.
            CompoundTag tag = new CompoundTag();
            tag.put("Item", stack("minecraft:white_wool", 12));
            tag.put("Material", blockState("minecraft:white_wool"));
            CompoundTag geometry = new CompoundTag();
            geometry.putInt("w", 3);
            tag.put("SailGeometry", geometry);
            sanitizer.sanitizeBlockEntity("create_flight_control:integrated_sail", tag, false);
            assertNoContents(tag);
            assertTrue(tag.contains("Material"));
            assertEquals(3, tag.getCompound("SailGeometry").getInt("w"));
        }

        @Test
        void linksAndSettingsAreUntouched() {
            CompoundTag tag = new CompoundTag();
            ListTag links = new ListTag();
            CompoundTag link = new CompoundTag();
            link.putLong("localPos", new BlockPos(1, 2, 3).asLong());
            links.add(link);
            tag.put("linkedTargets", links);
            tag.putDouble("mouseTargetYOffset", 2.5);
            CompoundTag before = tag.copy();
            NbtSanitizer.Report report = sanitizer.sanitizeBlockEntity("create_flight_control:forward_indicator", tag, false);
            assertFalse(report.changedAnything());
            assertEquals(before, tag);
        }
    }

    @Nested
    class DriveByWire {
        @Test
        void backupBlockPayloadIsDropped() {
            CompoundTag tag = new CompoundTag();
            CompoundTag network = new CompoundTag();
            network.putInt("SnapshotVersion", 3);
            network.put("Connections", new ListTag());
            tag.put("WireNetwork", network);
            sanitizer.sanitizeBlockEntity("drivebywire:backup_block", tag, false);
            assertFalse(tag.contains("WireNetwork"));
        }

        @Test
        void lecternControllerKeepsFrequenciesAndHubLinkButDropsUser() {
            // Create LecternControllerBlockEntity + Drive-By-Wire mixin (DriveByWireHub).
            CompoundTag tag = new CompoundTag();
            ListTag frequencies = new ListTag();
            CompoundTag slot = new CompoundTag();
            slot.putInt("slot", 0);
            slot.put("item", stack("minecraft:redstone_torch", 1));
            frequencies.add(slot);
            tag.put("ControllerData", frequencies);
            tag.putUUID("User", java.util.UUID.randomUUID());
            tag.putLong("DriveByWireHub", new BlockPos(10, 64, 10).asLong());
            sanitizer.sanitizeBlockEntity("create:lectern_controller", tag, false);
            assertEquals(1, tag.getList("ControllerData", Tag.TAG_COMPOUND).size(), "frequency ghost items kept");
            assertFalse(tag.contains("User"));
            assertTrue(tag.contains("DriveByWireHub"));
        }
    }

    @Nested
    class Generic {
        @Test
        void slotInventoriesOfUnknownModsAreEmptied() {
            CompoundTag tag = new CompoundTag();
            CompoundTag inventory = new CompoundTag();
            ListTag items = new ListTag();
            items.add(slotted(0, stack("minecraft:stone", 64)));
            items.add(slotted(4, stack("create:andesite_alloy", 12)));
            inventory.put("Items", items);
            inventory.putInt("Size", 9);
            tag.put("Inventory", inventory);
            tag.putInt("SomeSetting", 7);
            sanitizer.sanitizeBlockEntity("unknownmod:crate", tag, false);
            assertEquals(0, tag.getCompound("Inventory").getList("Items", Tag.TAG_COMPOUND).size());
            assertEquals(9, tag.getCompound("Inventory").getInt("Size"));
            assertEquals(7, tag.getInt("SomeSetting"));
        }

        @Test
        void containerComponentsAreEmptied() {
            CompoundTag tag = new CompoundTag();
            CompoundTag components = new CompoundTag();
            ListTag container = new ListTag();
            CompoundTag slot = new CompoundTag();
            slot.putInt("slot", 0);
            slot.put("item", stack("minecraft:stone", 3));
            container.add(slot);
            components.put("minecraft:container", container);
            tag.put("components", components);
            sanitizer.sanitizeBlockEntity("unknownmod:box", tag, false);
            assertNoContents(tag);
        }

        @Test
        void fluidTanksAreEmptied() {
            CompoundTag tag = new CompoundTag();
            CompoundTag tank = new CompoundTag();
            tank.put("Fluid", fluid("minecraft:water", 8000));
            tag.put("TankContent", tank);
            CompoundTag legacy = new CompoundTag();
            legacy.putString("FluidName", "minecraft:lava");
            legacy.putInt("Amount", 500);
            tag.put("Legacy", legacy);
            sanitizer.sanitizeBlockEntity("unknownmod:tank", tag, false);
            assertNoContents(tag);
        }

        @Test
        void createFilterAndRedstoneLinkFrequenciesAreGhostsAndKept() {
            CompoundTag tag = new CompoundTag();
            tag.put("Filter", stack("minecraft:stone", 1));
            tag.put("FrequencyFirst", stack("minecraft:redstone_torch", 1));
            tag.put("FrequencyLast", stack("create:wrench", 1));
            tag.putInt("ScrollValue", 16);
            sanitizer.sanitizeBlockEntity("create:redstone_link", tag, false);
            assertTrue(sanitizer.looksLikeItemStack(tag.getCompound("Filter")));
            assertTrue(sanitizer.looksLikeItemStack(tag.getCompound("FrequencyFirst")));
            assertTrue(sanitizer.looksLikeItemStack(tag.getCompound("FrequencyLast")));
        }

        @Test
        void chargedContentIsKeptOnlyWhereTheRequirementPaysForIt() {
            CompoundTag wheel = new CompoundTag();
            wheel.put("CurrentStack", stack("offroad:tire", 1));
            sanitizer.sanitizeBlockEntity("offroad:wheel_mount", wheel, true);
            assertTrue(sanitizer.looksLikeItemStack(wheel.getCompound("CurrentStack")), "wheel mounts charge their tire");

            CompoundTag other = new CompoundTag();
            other.put("CurrentStack", stack("offroad:tire", 1));
            sanitizer.sanitizeBlockEntity("unknownmod:holder", other, false);
            assertNoContents(other);
        }

        @Test
        void blazeBurnerFuelIsRemoved() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("fuelLevel", 2);
            tag.putInt("burnTimeRemaining", 9000);
            tag.putBoolean("isCreative", false);
            sanitizer.sanitizeBlockEntity("create:blaze_heater", tag, false);
            assertEquals(0, tag.getInt("fuelLevel"));
            assertEquals(0, tag.getInt("burnTimeRemaining"));
        }

        @Test
        void portableEngineFuelAndDockingBatteryAreRemoved() {
            CompoundTag engine = new CompoundTag();
            engine.putInt("BurnTime", 1600);
            CompoundTag inventory = new CompoundTag();
            ListTag items = new ListTag();
            items.add(slotted(0, stack("minecraft:stone", 1)));
            inventory.put("Items", items);
            engine.put("Inventory", inventory);
            sanitizer.sanitizeBlockEntity("simulated:portable_engine", engine, true);
            assertEquals(0, engine.getInt("BurnTime"));
            assertNoContents(engine);

            CompoundTag docking = new CompoundTag();
            CompoundTag battery = new CompoundTag();
            battery.putInt("Energy", 100000);
            docking.put("Battery", battery);
            sanitizer.sanitizeBlockEntity("simulated:docking_connector", docking, false);
            assertFalse(docking.contains("Battery"));
        }

        @Test
        void configEntriesAddRules() {
            SanitizerRules rules = SanitizerRules.defaults().addConfigEntries(List.of("othermod:battery#Charge=0", "othermod#Secret", "broken"));
            NbtSanitizer configured = new NbtSanitizer(rules, TestNbt.LOOKUP);
            CompoundTag tag = new CompoundTag();
            tag.putLong("Charge", 99L);
            tag.putString("Secret", "x");
            configured.sanitizeBlockEntity("othermod:battery", tag, false);
            assertEquals(0L, tag.getLong("Charge"));
            assertFalse(tag.contains("Secret"));
        }
    }

    @Nested
    class Fluids {
        private CompoundTag neoForgeTank(String fluid, int amount) {
            CompoundTag tank = new CompoundTag();
            tank.put("Fluid", fluid(fluid, amount)); // NeoForge FluidTank#writeToNBT
            return tank;
        }

        private CompoundTag lerped(float value) {
            CompoundTag level = new CompoundTag();
            level.putFloat("Speed", 0.25f);
            level.putFloat("Target", value);
            level.putFloat("Value", value);
            return level;
        }

        @Test
        void createFluidTankIsEmptiedAndStopsGlowingAndBoiling() {
            // FluidTankBlockEntity#write (controller)
            CompoundTag tag = new CompoundTag();
            tag.put("TankContent", neoForgeTank("minecraft:lava", 8000));
            tag.putBoolean("Window", true);
            tag.putInt("Size", 2);
            tag.putInt("Height", 3);
            tag.putInt("Luminosity", 12);
            CompoundTag boiler = new CompoundTag();
            boiler.putFloat("Supply", 30f);
            boiler.putInt("ActiveHeat", 4);
            boiler.putInt("Engines", 2);
            tag.put("Boiler", boiler);
            sanitizer.sanitizeBlockEntity("create:fluid_tank", tag, false);
            assertNoContents(tag);
            assertEquals(0, tag.getInt("Luminosity"), "empty tank must not glow");
            assertEquals(0f, tag.getCompound("Boiler").getFloat("Supply"), "no phantom water for the steam engine");
            assertEquals(2, tag.getCompound("Boiler").getInt("Engines"));
            assertEquals(2, tag.getInt("Size"));
            assertEquals(3, tag.getInt("Height"));
            assertTrue(tag.getBoolean("Window"));
        }

        @Test
        void spoutDrainAndBasinTanksAreEmptiedIncludingRenderedLevel() {
            // SmartFluidTankBehaviour: "<type>Tanks": [{TankContent: {Fluid}, Level: LerpedFloat}]
            for (String type : List.of("create:spout", "create:item_drain", "create:basin")) {
                CompoundTag tag = new CompoundTag();
                ListTag tanks = new ListTag();
                CompoundTag segment = new CompoundTag();
                segment.put("TankContent", neoForgeTank("minecraft:water", 1000));
                segment.put("Level", lerped(1f));
                tanks.add(segment);
                tag.put("FluidTanks", tanks);
                ListTag overflow = new ListTag();
                overflow.add(fluid("create:honey", 250));
                tag.put("FluidOverflow", overflow); // basin output buffer
                sanitizer.sanitizeBlockEntity(type, tag, false);
                assertNoContents(tag);
                CompoundTag cleaned = tag.getList("FluidTanks", Tag.TAG_COMPOUND).getCompound(0);
                assertEquals(0f, cleaned.getCompound("Level").getFloat("Value"), type + " rendered level");
                assertEquals(0, tag.getList("FluidOverflow", Tag.TAG_COMPOUND).size(), type + " overflow buffer");
            }
        }

        @Test
        void pipeFlowsAndOpenEndsAreEmptied() {
            // FluidTransportBehaviour/PipeConnection: per side {Pressure, Flow: {Fluid, In, Progress}, OpenEnd: {Fluid, ...}}
            CompoundTag tag = new CompoundTag();
            CompoundTag north = new CompoundTag();
            ListTag pressure = new ListTag();
            pressure.add(net.minecraft.nbt.FloatTag.valueOf(1f));
            pressure.add(net.minecraft.nbt.FloatTag.valueOf(0f));
            north.put("Pressure", pressure);
            CompoundTag flow = new CompoundTag();
            flow.put("Fluid", fluid("minecraft:water", 250));
            flow.putBoolean("In", true);
            flow.put("Progress", lerped(0.5f));
            north.put("Flow", flow);
            CompoundTag openEnd = new CompoundTag();
            openEnd.put("Fluid", fluid("minecraft:water", 1000));
            openEnd.putBoolean("Pulling", true);
            north.put("OpenEnd", openEnd);
            tag.put("north", north);
            sanitizer.sanitizeBlockEntity("create:fluid_pipe", tag, false);
            assertNoContents(tag);
            assertFalse(tag.getCompound("north").contains("Flow"), "a flow without fluid is dropped");
            assertTrue(tag.getCompound("north").contains("Pressure"));
        }

        @Test
        void hosePulleyAndPortableInterfaceTanksAreEmptied() {
            CompoundTag tag = new CompoundTag();
            tag.put("Tank", neoForgeTank("minecraft:lava", 1000));
            tag.putInt("Offset", 5);
            sanitizer.sanitizeBlockEntity("create:hose_pulley", tag, true);
            assertNoContents(tag);
            assertEquals(5, tag.getInt("Offset"));
        }

        @Test
        void simulatedDockingConnectorTankInItsOwnLayoutIsEmptied() {
            // Simulated SingleTank#write: {Amount: long, Variant: {Fluid: "...", data: {...}}} - not a FluidStack!
            CompoundTag tag = new CompoundTag();
            CompoundTag tank = new CompoundTag();
            tank.putLong("Amount", 4000L);
            CompoundTag variant = new CompoundTag();
            variant.putString("Fluid", "minecraft:lava");
            variant.put("data", new CompoundTag());
            tank.put("Variant", variant);
            tag.put("Tank", tank);
            CompoundTag battery = new CompoundTag();
            battery.putLong("Amount", 100000L);
            tag.put("Battery", battery);
            tag.putBoolean("Extended", true);

            NbtSanitizer.Report report = sanitizer.sanitizeBlockEntity("simulated:docking_connector", tag, false);

            CompoundTag emptied = tag.getCompound("Tank");
            assertEquals(0L, emptied.getLong("Amount"), "docking connector fluid amount");
            assertEquals("minecraft:empty", emptied.getCompound("Variant").getString("Fluid"), "docking connector fluid type");
            assertEquals(Tag.TAG_LONG, emptied.get("Amount").getId(), "reader keeps working: same tag type");
            assertFalse(tag.contains("Battery"));
            assertTrue(tag.getBoolean("Extended"));
            assertEquals(1, report.fluidStacks);
            assertNoContents(tag);
        }

        @Test
        void otherLoaderLayoutsAreRecognised() {
            CompoundTag fabricLike = new CompoundTag();
            CompoundTag variant = new CompoundTag();
            variant.putString("fluid", "minecraft:water");
            fabricLike.put("variant", variant);
            fabricLike.putLong("amount", 81000L);
            assertTrue(sanitizer.looksLikeFluidStack(fabricLike));

            CompoundTag flat = new CompoundTag();
            flat.putString("Fluid", "minecraft:lava");
            flat.putInt("Amount", 1000);
            assertTrue(sanitizer.looksLikeFluidStack(flat));

            CompoundTag empty = new CompoundTag();
            empty.putString("Fluid", "minecraft:empty");
            empty.putInt("Amount", 0);
            assertFalse(sanitizer.looksLikeFluidStack(empty));

            CompoundTag item = stack("minecraft:stone", 3);
            item.putInt("amount", 3);
            assertFalse(sanitizer.looksLikeFluidStack(item), "item stacks are not fluids");
        }

        @Test
        void fluidsOfMountedTanksOnContraptionsAreEmptied() {
            // MountedStorageManager: fluids: [{pos, storage: {type, capacity, fluid: FluidStack.OPTIONAL_CODEC}}]
            CompoundTag entity = new CompoundTag();
            entity.putString("id", "create:contraption");
            CompoundTag contraption = new CompoundTag();
            CompoundTag storageManager = new CompoundTag();
            ListTag fluids = new ListTag();
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", 0L);
            CompoundTag storage = new CompoundTag();
            storage.putString("type", "create:fluid_tank");
            storage.putInt("capacity", 8000);
            storage.put("fluid", fluid("minecraft:lava", 8000));
            entry.put("storage", storage);
            fluids.add(entry);
            storageManager.put("fluids", fluids);
            contraption.put("Storage", storageManager);
            entity.put("Contraption", contraption);
            sanitizer.sanitizeEntity(entity);
            assertNoContents(entity);
            CompoundTag cleaned = entity.getCompound("Contraption").getCompound("Storage").getList("fluids", Tag.TAG_COMPOUND).getCompound(0);
            assertEquals(8000, cleaned.getCompound("storage").getInt("capacity"), "tank stays, only its content goes");
        }

        @Test
        void cannonCastLosesMoltenMetalAndCastingProgress() {
            CompoundTag tag = new CompoundTag();
            tag.put("FluidContent", neoForgeTank("createbigcannons:molten_cast_iron", 1296));
            tag.put("Leakage", fluid("createbigcannons:molten_cast_iron", 144));
            tag.putInt("CastingTime", 400);
            tag.putInt("StartCastingTime", 1200);
            tag.putInt("Height", 3);
            sanitizer.sanitizeBlockEntity("createbigcannons:cannon_cast", tag, false);
            assertNoContents(tag);
            assertEquals(0, tag.getInt("CastingTime"));
            assertFalse(tag.contains("StartCastingTime"));
            assertEquals(3, tag.getInt("Height"));
        }
    }

    @Nested
    class Entities {
        @Test
        void itemFrameAndArmorStandItemsAreRemovedKeepingSlots() {
            CompoundTag frame = new CompoundTag();
            frame.putString("id", "minecraft:item_frame");
            frame.put("Item", stack("createbigcannons:he_shell", 1));
            frame.putInt("TileX", 1);
            sanitizer.sanitizeEntity(frame);
            assertNoContents(frame);
            assertEquals("minecraft:item_frame", frame.getString("id"), "entity id itself is not an item stack");

            CompoundTag stand = new CompoundTag();
            stand.putString("id", "minecraft:armor_stand");
            ListTag armor = new ListTag();
            armor.add(new CompoundTag());
            armor.add(new CompoundTag());
            armor.add(stack("minecraft:diamond_chestplate", 1));
            armor.add(new CompoundTag());
            stand.put("ArmorItems", armor);
            sanitizer.sanitizeEntity(stand);
            assertEquals(4, stand.getList("ArmorItems", Tag.TAG_COMPOUND).size(), "positional list keeps its size");
            assertNoContents(stand);
        }

        @Test
        void loadedCannonInsideContraptionEntityIsEmptied() {
            // An assembled cannon is a contraption entity carrying the cannon blocks' block entity data.
            CompoundTag entity = new CompoundTag();
            entity.putString("id", "createbigcannons:pitch_contraption");
            CompoundTag contraption = new CompoundTag();
            CompoundTag blocks = new CompoundTag();
            ListTag blockList = new ListTag();
            CompoundTag block = new CompoundTag();
            block.putLong("Pos", 0L);
            block.putInt("State", 0);
            CompoundTag data = new CompoundTag();
            data.putString("id", "createbigcannons:cannon");
            data.put("Pos", pos(BlockPos.ZERO));
            data.put("State", blockState("createbigcannons:he_shell"));
            data.put("Data", new CompoundTag());
            data.putString("Facing", "east");
            block.put("Data", data);
            blockList.add(block);
            blocks.put("BlockList", blockList);
            contraption.put("Blocks", blocks);
            CompoundTag storage = new CompoundTag();
            ListTag storageItems = new ListTag();
            storageItems.add(slotted(0, stack("createbigcannons:ap_shell", 4)));
            storage.put("Items", storageItems);
            contraption.put("Storage", storage);
            entity.put("Contraption", contraption);

            sanitizer.sanitizeEntity(entity);

            CompoundTag cleaned = entity.getCompound("Contraption").getCompound("Blocks").getList("BlockList", Tag.TAG_COMPOUND).getCompound(0);
            assertTrue(cleaned.contains("Pos"), "contraption block entry keeps its own position");
            assertTrue(cleaned.contains("State"), "contraption block entry keeps its palette index");
            assertFalse(cleaned.getCompound("Data").contains("State"), "loaded shell inside the assembled cannon removed");
            assertEquals("east", cleaned.getCompound("Data").getString("Facing"));
            assertNoContents(entity);
        }
    }
}
