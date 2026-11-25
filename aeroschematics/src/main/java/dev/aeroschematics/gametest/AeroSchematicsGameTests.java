package dev.aeroschematics.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.SchematicExport;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.packet.SchematicPlacePacket;
import com.simibubi.create.foundation.utility.CreatePaths;
import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.aeroschematics.AeroSchematics;
import dev.aeroschematics.capture.SubLevelCaptureMeta;
import dev.aeroschematics.mixinterface.AeroCannonExt;
import dev.aeroschematics.print.SubLevelPrintStage;
import dev.aeroschematics.nbt.AeroSanitizer;
import dev.aeroschematics.nbt.NbtSanitizer;
import dev.aeroschematics.nbt.SanitizerRules;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import com.mojang.authlib.GameProfile;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * End-to-end tests with the real mods: build a ship carrying Flight Control, Create Big Cannons, CBC Firepower
 * Components and Drive-By-Wire devices (storages filled, settings changed, linked and wired), assemble it into a Sable
 * physics structure, save it with Create's schematic export, then place it (creative tool) or print it (schematicannon).
 */
@GameTestHolder(AeroSchematics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AeroSchematicsGameTests {
    private static final String OWNER = "aerotest";
    private static final int OFFSET = 14;
    private static final int SETTLE_TICKS = 5;

    private AeroSchematicsGameTests() {
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------------------------------

    @GameTest(template = "platform", timeoutTicks = 200, batch = "aeroschematics_creative")
    public static void creativePlacementKeepsSettingsLinksAndWiresButNoContents(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestShip ship = TestShip.full(helper);
        ServerSubLevel original = ship.assemble();
        assertSetupLoaded(level, ship);
        String file = "creative_" + UUID.randomUUID() + ".nbt";
        // Sable updates the bounds of a new physics structure on its next ticks, as in game.
        helper.startSequence().thenIdle(SETTLE_TICKS).thenExecute(() -> {
            Vec3 originalBarrel = worldPos(original, ship.plotPositions.get("barrel"));
            CompoundTag originalThruster = TestShip.saved(level, ship.plotPositions.get("thruster"));
            CompoundTag schematic = save(helper, file);
            assertSchematicFile(helper, schematic, true);

            Set<UUID> before = subLevelIds(level);
            // A fake player (not in the player list): mod packets cannot be sent to a mock connection.
            ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("0a0e0500-0000-4000-8000-00000000ae50"), "aerotest"));
            player.setGameMode(GameType.CREATIVE);
            BlockPos anchor = helper.absolutePos(new BlockPos(1, 2, 1 + OFFSET));
            new SchematicPlacePacket(schematicItem(level, file, anchor)).handle(player);

            ServerSubLevel copy = single(newSubLevels(level, before), "creative placement");
            assertCopy(level, ship, copy, originalThruster);
            assertNear(originalBarrel.add(0, 0, OFFSET), worldPos(copy, ship.in(copy, "barrel")), 0.25, "copy pose");
        }).thenSucceed();
    }

    @GameTest(template = "platform", timeoutTicks = 2400, batch = "aeroschematics_cannon")
    public static void schematicannonPrintsPhysicsStructureWithSettingsLinksAndWires(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AllConfigs.server().schematics.schematicannonDelay.set(1);
        TestShip ship = TestShip.full(helper);
        ServerSubLevel original = ship.assemble();
        assertSetupLoaded(level, ship);
        String file = "cannon_" + UUID.randomUUID() + ".nbt";

        AtomicReference<Vec3> originalBarrel = new AtomicReference<>();
        AtomicReference<CompoundTag> originalThruster = new AtomicReference<>();
        AtomicReference<Set<UUID>> before = new AtomicReference<>(Set.of());
        AtomicReference<SchematicannonBlockEntity> cannonRef = new AtomicReference<>();
        AtomicReference<ServerSubLevel> lockedSeen = new AtomicReference<>();
        helper.onEachTick(() -> {
            SchematicannonBlockEntity cannon = cannonRef.get();
            if (cannon == null) {
                return;
            }
            keepRunning(cannon);
            // While printing, the new structure must be held in place.
            for (ServerSubLevel subLevel : newSubLevels(level, before.get())) {
                if (PhysicsStaffServerHandler.get(level).isLocked(subLevel)) {
                    lockedSeen.set(subLevel);
                }
            }
        });
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    originalBarrel.set(worldPos(original, ship.plotPositions.get("barrel")));
                    originalThruster.set(TestShip.saved(level, ship.plotPositions.get("thruster")));
                    CompoundTag schematic = save(helper, file);
                    assertSchematicFile(helper, schematic, true);
                    before.set(subLevelIds(level));
                    BlockPos anchor = helper.absolutePos(new BlockPos(1, 2, 1 + OFFSET));
                    helper.setBlock(new BlockPos(10, 1, 9), AllBlocks.CREATIVE_CRATE.getDefaultState());
                    cannonRef.set(schematicannon(helper, new BlockPos(9, 1, 9), schematicItem(level, file, anchor)));
                })
                .thenWaitUntil(() -> {
                    SchematicannonBlockEntity cannon = cannonRef.get();
                    if (!"finished".equals(cannon.statusMsg)) {
                        throw new GameTestAssertException("schematicannon: " + cannon.state + " / " + cannon.statusMsg + " placed " + cannon.blocksPlaced);
                    }
                })
                .thenExecute(() -> {
                    ServerSubLevel copy = single(newSubLevels(level, before.get()), "schematicannon print");
                    if (lockedSeen.get() != copy) {
                        throw new GameTestAssertException("The structure was not locked while it was printed");
                    }
                    if (PhysicsStaffServerHandler.get(level).isLocked(copy)) {
                        throw new GameTestAssertException("The structure is still locked after printing (unlockAfterPrinting=true)");
                    }
                    assertCopy(level, ship, copy, originalThruster.get());
                    assertNear(originalBarrel.get().add(0, 0, OFFSET), worldPos(copy, ship.in(copy, "barrel")), 0.25, "printed pose");
                })
                .thenSucceed();
    }

    @GameTest(template = "platform", timeoutTicks = 1200, batch = "aeroschematics_survival")
    public static void schematicannonChargesBlocksButNeverStoredItems(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AllConfigs.server().schematics.schematicannonDelay.set(1);
        TestShip ship = TestShip.small(helper);
        ship.assemble();
        String file = "survival_" + UUID.randomUUID() + ".nbt";
        AtomicReference<Set<UUID>> before = new AtomicReference<>(Set.of());
        AtomicReference<SchematicannonBlockEntity> cannonRef = new AtomicReference<>();
        AtomicReference<ChestBlockEntity> supplyRef = new AtomicReference<>();
        AtomicReference<String> checklistSeen = new AtomicReference<>();
        Item container = BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:autocannon_ammo_container"));
        helper.onEachTick(() -> {
            SchematicannonBlockEntity cannon = cannonRef.get();
            if (cannon != null) {
                if (cannon.printer.isLoaded() && checklistSeen.get() == null) {
                    // The material list is computed when the schematic is loaded, before anything is printed.
                    checklistSeen.set("stone=" + cannon.checklist.required.getInt(Items.STONE) + ", container=" + cannon.checklist.required.getInt(container));
                }
                keepRunning(cannon);
            }
        });
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    CompoundTag schematic = save(helper, file);
                    assertSchematicFile(helper, schematic, false);
                    before.set(subLevelIds(level));
                    BlockPos anchor = helper.absolutePos(new BlockPos(1, 2, 1 + OFFSET));
                    // Exactly the materials of the structure (3 stone + 1 empty ammo container) and something unrelated.
                    helper.setBlock(new BlockPos(10, 1, 9), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                    ChestBlockEntity supply = (ChestBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(10, 1, 9)));
                    supply.setItem(0, new ItemStack(Items.STONE, 3));
                    supply.setItem(1, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:autocannon_ammo_container"))));
                    supply.setItem(2, new ItemStack(Items.DIAMOND, 5));
                    supplyRef.set(supply);
                    SchematicannonBlockEntity cannon = schematicannon(helper, new BlockPos(9, 1, 9), schematicItem(level, file, anchor));
                    cannon.inventory.setStackInSlot(4, new ItemStack(Items.GUNPOWDER, 4));
                    cannonRef.set(cannon);
                })
                .thenWaitUntil(() -> {
                    SchematicannonBlockEntity cannon = cannonRef.get();
                    if (!"finished".equals(cannon.statusMsg)) {
                        throw new GameTestAssertException("schematicannon: " + cannon.state + " / " + cannon.statusMsg + " missing " + cannon.missingItem
                                + ", placed " + cannon.blocksPlaced + ", stage " + ((dev.aeroschematics.mixinterface.AeroCannonExt) cannon).aeroschematics$getStage().phase()
                                + ", supply " + supplyRef.get().getItem(0) + " " + supplyRef.get().getItem(1) + " " + supplyRef.get().getItem(2)
                                + ", list " + checklistSeen.get());
                    }
                })
                .thenExecute(() -> {
                    ChestBlockEntity supply = supplyRef.get();
                    if (!"stone=3, container=1".equals(checklistSeen.get())) {
                        throw new GameTestAssertException("material list of the physics structure wrong: " + checklistSeen.get());
                    }
                    ServerSubLevel copy = single(newSubLevels(level, before.get()), "survival print");
                    int stone = 0;
                    int containers = 0;
                    int diamonds = 0;
                    for (int slot = 0; slot < supply.getContainerSize(); slot++) {
                        ItemStack stack = supply.getItem(slot);
                        if (stack.is(Items.STONE)) {
                            stone += stack.getCount();
                        } else if (stack.is(Items.DIAMOND)) {
                            diamonds += stack.getCount();
                        } else if (!stack.isEmpty()) {
                            containers += stack.getCount();
                        }
                    }
                    if (stone != 0 || containers != 0 || diamonds != 5) {
                        throw new GameTestAssertException("expected all stone and the container consumed and 5 diamonds left, got stone=" + stone
                                + " containers=" + containers + " diamonds=" + diamonds);
                    }
                    CompoundTag ammo = TestShip.saved(level, ship.in(copy, "ammo"));
                    assertEmptyStack(level, ammo, "Ammo", "printed ammo container");
                    assertEmptyStack(level, ammo, "Tracers", "printed ammo container tracers");
                    assertEquals(3, ammo.getInt("TracerSpacing"), "ammo container setting TracerSpacing");
                })
                .thenSucceed();
    }

    @GameTest(template = "platform", timeoutTicks = 200, batch = "aeroschematics_tampered")
    public static void handEditedSchematicCannotSmuggleItems(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestShip ship = TestShip.full(helper);
        ship.assemble();
        String file = "tampered_" + UUID.randomUUID() + ".nbt";
        helper.startSequence().thenIdle(SETTLE_TICKS).thenExecute(() -> {
            CompoundTag schematic = save(helper, file);
            // A modified client writes the contents back into the file before uploading it.
            CompoundTag subLevel = schematic.getList("sub_levels", Tag.TAG_COMPOUND).getCompound(0);
            int injected = 0;
            for (Tag entry : subLevel.getList("blocks", Tag.TAG_COMPOUND)) {
                CompoundTag nbt = ((CompoundTag) entry).getCompound("nbt");
                String id = nbt.getString("id");
                if (id.equals("minecraft:chest")) {
                    nbt.put("Items", TestShip.nbt("{l:[{Slot:0b,id:\"minecraft:diamond_block\",count:64}]}").getList("l", Tag.TAG_COMPOUND));
                    injected++;
                } else if (id.equals("createbigcannons:cannon")) {
                    nbt.merge(TestShip.nbt("{Pos:0L,State:{Name:\"createbigcannons:he_shell\",Properties:{facing:\"north\"}}}"));
                    injected++;
                } else if (id.equals("cbc_firepower_components:large_autocannon_ammo_box")) {
                    nbt.merge(TestShip.nbt("{Ammo:{id:\"cbc_firepower_components:large_autocannon_round\",count:64},LargeMainAmmoCount:64}"));
                    injected++;
                } else if (id.equals("create_flight_control:smart_vector_thruster")) {
                    nbt.merge(TestShip.nbt("{fluidAmountMb:64000,storedFluidId:\"minecraft:lava\"}"));
                    injected++;
                }
            }
            if (injected != 4) {
                throw new GameTestAssertException("could only tamper with " + injected + " block entities");
            }
            try {
                NbtIo.writeCompressed(schematic, CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve(OWNER).resolve(file));
            } catch (IOException e) {
                throw new GameTestAssertException("could not write tampered schematic: " + e);
            }

            Set<UUID> before = subLevelIds(level);
            ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("0a0e0500-0000-4000-8000-00000000ae50"), "aerotest"));
            player.setGameMode(GameType.CREATIVE);
            new SchematicPlacePacket(schematicItem(level, file, helper.absolutePos(new BlockPos(1, 2, 1 + OFFSET)))).handle(player);
            ServerSubLevel copy = single(newSubLevels(level, before), "tampered placement");
            if (!TestShip.saved(level, ship.in(copy, "chest")).getList("Items", Tag.TAG_COMPOUND).isEmpty()) {
                throw new GameTestAssertException("DUPE: items injected into the file reached the world");
            }
            if (TestShip.saved(level, ship.in(copy, "barrel")).contains("State")) {
                throw new GameTestAssertException("DUPE: a shell injected into the file reached the world");
            }
            assertEquals(0, TestShip.saved(level, ship.in(copy, "box")).getInt("LargeMainAmmoCount"), "DUPE: injected ammo count");
            assertEmptyStack(level, TestShip.saved(level, ship.in(copy, "box")), "Ammo", "injected ammo");
            assertEquals(0, TestShip.saved(level, ship.in(copy, "thruster")).getInt("fluidAmountMb"), "DUPE: injected fuel");
        }).thenSucceed();
    }

    @GameTest(template = "platform", timeoutTicks = 2400, batch = "aeroschematics_restart")
    public static void schematicannonResumesAfterReloadWithoutDuplicating(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AllConfigs.server().schematics.schematicannonDelay.set(2);
        TestShip ship = TestShip.full(helper);
        ship.assemble();
        String file = "restart_" + UUID.randomUUID() + ".nbt";
        BlockPos cannonPos = new BlockPos(9, 1, 9);
        AtomicReference<Set<UUID>> before = new AtomicReference<>(Set.of());
        AtomicReference<SchematicannonBlockEntity> cannonRef = new AtomicReference<>();
        AtomicReference<CompoundTag> originalThruster = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean reloaded = new java.util.concurrent.atomic.AtomicBoolean();
        helper.onEachTick(() -> {
            SchematicannonBlockEntity cannon = cannonRef.get();
            if (cannon == null) {
                return;
            }
            keepRunning(cannon);
            SubLevelPrintStage stage = ((AeroCannonExt) cannon).aeroschematics$getStage();
            if (!reloaded.get() && stage.phase() == SubLevelPrintStage.Phase.BLOCKS && cannon.blocksPlaced >= 8) {
                // Simulate a server restart: a new block entity loaded from the saved data replaces the old one.
                reloaded.set(true);
                BlockPos absolute = helper.absolutePos(cannonPos);
                CompoundTag saved = cannon.saveWithFullMetadata(level.registryAccess());
                SchematicannonBlockEntity fresh = (SchematicannonBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                        absolute, level.getBlockState(absolute), saved, level.registryAccess());
                level.setBlockEntity(fresh);
                fresh.state = SchematicannonBlockEntity.State.RUNNING;
                cannonRef.set(fresh);
            }
        });
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    originalThruster.set(TestShip.saved(level, ship.plotPositions.get("thruster")));
                    save(helper, file);
                    before.set(subLevelIds(level));
                    helper.setBlock(new BlockPos(10, 1, 9), AllBlocks.CREATIVE_CRATE.getDefaultState());
                    cannonRef.set(schematicannon(helper, cannonPos, schematicItem(level, file, helper.absolutePos(new BlockPos(1, 2, 1 + OFFSET)))));
                })
                .thenWaitUntil(() -> {
                    SchematicannonBlockEntity cannon = cannonRef.get();
                    if (!reloaded.get() || !"finished".equals(cannon.statusMsg)) {
                        throw new GameTestAssertException("schematicannon: reloaded=" + reloaded.get() + " " + cannon.state + " / " + cannon.statusMsg);
                    }
                })
                .thenExecute(() -> {
                    ServerSubLevel copy = single(newSubLevels(level, before.get()), "print resumed after reload");
                    assertCopy(level, ship, copy, originalThruster.get());
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------------

    private static void assertSetupLoaded(ServerLevel level, TestShip ship) {
        // Make sure the test really starts with loaded cannons, filled storages and a wire.
        if (!TestShip.saved(level, ship.plotPositions.get("barrel")).contains("State")) {
            throw new GameTestAssertException("setup: the cannon barrel did not accept the loaded shell");
        }
        if (TestShip.saved(level, ship.plotPositions.get("box")).getInt("LargeMainAmmoCount") <= 0) {
            throw new GameTestAssertException("setup: the large ammo box is empty");
        }
        if (TestShip.saved(level, ship.plotPositions.get("thruster")).getInt("fluidAmountMb") <= 0) {
            throw new GameTestAssertException("setup: the thruster has no fuel");
        }
        if (!WireNetworkManager.hasConnection(level, ship.plotPositions.get("hub"), ship.plotPositions.get("lamp"), Direction.UP, TestShip.WIRE_CHANNEL)) {
            throw new GameTestAssertException("setup: wire missing");
        }
    }

    /** Selects the air around the ship (not the test platform floor), like a player would. */
    private static CompoundTag save(GameTestHelper helper, String file) {
        Path dir = CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve(OWNER);
        SchematicExport.SchematicExportResult result = SchematicExport.saveSchematic(dir, file, true, helper.getLevel(),
                helper.absolutePos(new BlockPos(1, 2, 1)), helper.absolutePos(new BlockPos(9, 6, 9)));
        if (result == null) {
            throw new GameTestAssertException("Create could not save the schematic");
        }
        try {
            return NbtIo.readCompressed(result.file(), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new GameTestAssertException("Cannot read saved schematic: " + e);
        }
    }

    /** The file itself must not contain any stored item/fluid and must carry the metadata and the wire. */
    private static void assertSchematicFile(GameTestHelper helper, CompoundTag schematic, boolean full) {
        ListTag subLevels = schematic.getList("sub_levels", Tag.TAG_COMPOUND);
        if (subLevels.size() != 1) {
            throw new GameTestAssertException("expected 1 physics structure in the schematic, found " + subLevels.size());
        }
        CompoundTag subLevel = subLevels.getCompound(0);
        if (!subLevel.contains(SubLevelCaptureMeta.KEY, Tag.TAG_COMPOUND)) {
            throw new GameTestAssertException("capture metadata missing");
        }
        SubLevelCaptureMeta meta = SubLevelCaptureMeta.load(subLevel.getCompound(SubLevelCaptureMeta.KEY));
        if (meta == null || !meta.schematicUuid().equals(subLevel.getUUID("uuid"))) {
            throw new GameTestAssertException("capture metadata invalid");
        }
        if (full && meta.wires().size() != 1) {
            throw new GameTestAssertException("expected 1 Drive-By-Wire connection in the schematic, found " + meta.wires().size());
        }
        NbtSanitizer scanner = new NbtSanitizer(new SanitizerRules(), AeroSanitizer.REGISTRY_LOOKUP);
        for (Tag block : subLevel.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag nbt = ((CompoundTag) block).getCompound("nbt");
            String found = findContents(scanner, nbt, "");
            if (found != null) {
                throw new GameTestAssertException("schematic file still contains stored contents at " + found + " in " + nbt);
            }
            if (nbt.getString("id").startsWith("createbigcannons:") && nbt.contains("State", Tag.TAG_COMPOUND)) {
                throw new GameTestAssertException("schematic file still contains a loaded munition: " + nbt);
            }
            if (nbt.getInt("LargeMainAmmoCount") != 0 || nbt.getInt("fluidAmountMb") != 0) {
                throw new GameTestAssertException("schematic file still contains ammunition/fuel counters: " + nbt);
            }
        }
    }

    private static String findContents(NbtSanitizer scanner, Tag tag, String path) {
        if (tag instanceof CompoundTag compound) {
            if (scanner.looksLikeItemStack(compound) || scanner.looksLikeFluidStack(compound)) {
                return path;
            }
            for (String key : compound.getAllKeys()) {
                if (SanitizerRules.DEFAULT_GHOST_KEYS.contains(key)) {
                    continue;
                }
                String found = findContents(scanner, compound.get(key), path + "/" + key);
                if (found != null) {
                    return found;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                String found = findContents(scanner, list.get(i), path + "[" + i + "]");
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void assertCopy(ServerLevel level, TestShip ship, ServerSubLevel copy, CompoundTag originalThruster) {
        HolderLookup.Provider registries = level.registryAccess();
        ship.devices.keySet().forEach(name -> {
            BlockPos pos = ship.in(copy, name);
            if (level.getBlockState(pos).getBlock() != level.getBlockState(ship.plotPositions.get(name)).getBlock()) {
                throw new GameTestAssertException("'" + name + "' was not printed: found " + level.getBlockState(pos) + " at " + pos);
            }
        });

        // Create Big Cannons: barrel printed without its shell, ammo container empty, setting kept.
        CompoundTag barrel = TestShip.saved(level, ship.in(copy, "barrel"));
        if (barrel.contains("State")) {
            throw new GameTestAssertException("DUPE: the printed cannon barrel is loaded: " + barrel);
        }
        CompoundTag ammo = TestShip.saved(level, ship.in(copy, "ammo"));
        assertEmptyStack(level, ammo, "Ammo", "printed autocannon ammo container");
        assertEmptyStack(level, ammo, "Tracers", "printed autocannon ammo container tracers");
        assertEquals(3, ammo.getInt("TracerSpacing"), "ammo container TracerSpacing setting");

        // Firepower Components: counters zero, rounds gone, setting kept.
        CompoundTag box = TestShip.saved(level, ship.in(copy, "box"));
        assertEquals(0, box.getInt("LargeMainAmmoCount"), "DUPE: large ammo box main count");
        assertEquals(0, box.getInt("LargeTracerAmmoCount"), "DUPE: large ammo box tracer count");
        assertEquals(2, box.getInt("TracerSpacing"), "large ammo box TracerSpacing setting");
        CompoundTag rack = TestShip.saved(level, ship.in(copy, "rack"));
        if (!rack.getList("Rounds", Tag.TAG_COMPOUND).isEmpty()) {
            throw new GameTestAssertException("DUPE: ready ammunition compartment still has rounds: " + rack);
        }

        // Flight Control: fuel drained, every setting equal to the original.
        CompoundTag thruster = TestShip.saved(level, ship.in(copy, "thruster"));
        assertEquals(0, thruster.getInt("fluidAmountMb"), "DUPE: thruster fuel");
        if (thruster.contains("storedFluidId")) {
            throw new GameTestAssertException("DUPE: thruster still knows its fuel: " + thruster);
        }
        for (String key : List.of("flameColor", "halfAngleDeg", "soundVolScale", "maxThrust", "controlMode", "defaultEnabled")) {
            if (!java.util.Objects.equals(originalThruster.get(key), thruster.get(key))) {
                throw new GameTestAssertException("thruster setting '" + key + "' changed: " + originalThruster.get(key) + " -> " + thruster.get(key));
            }
        }

        // Vanilla/Create: chest empty, redstone link frequency (ghost items) kept.
        CompoundTag chest = TestShip.saved(level, ship.in(copy, "chest"));
        if (!chest.getList("Items", Tag.TAG_COMPOUND).isEmpty()) {
            throw new GameTestAssertException("DUPE: printed chest has items: " + chest);
        }
        CompoundTag link = TestShip.saved(level, ship.in(copy, "link"));
        ItemStack frequency = ItemStack.parseOptional(registries, link.getCompound("FrequencyFirst"));
        if (!frequency.is(Items.DIAMOND)) {
            throw new GameTestAssertException("redstone link frequency lost: " + link);
        }

        // Drive-By-Wire: lectern points at the printed hub, the wire connects the printed blocks.
        CompoundTag lectern = TestShip.saved(level, ship.in(copy, "lectern"));
        if (lectern.getLong("DriveByWireHub") != ship.in(copy, "hub").asLong()) {
            throw new GameTestAssertException("lectern hub link not re-targeted: " + BlockPos.of(lectern.getLong("DriveByWireHub"))
                    + " expected " + ship.in(copy, "hub"));
        }
        if (!WireNetworkManager.hasConnection(level, ship.in(copy, "hub"), ship.in(copy, "lamp"), Direction.UP, TestShip.WIRE_CHANNEL)) {
            throw new GameTestAssertException("Drive-By-Wire connection was not restored");
        }
        if (!WireNetworkManager.hasConnection(level, ship.plotPositions.get("hub"), ship.plotPositions.get("lamp"), Direction.UP, TestShip.WIRE_CHANNEL)) {
            throw new GameTestAssertException("the original wire disappeared");
        }

        // The original keeps its contents (nothing was moved out of it either).
        if (!TestShip.saved(level, ship.plotPositions.get("barrel")).contains("State")) {
            throw new GameTestAssertException("the original cannon lost its shell");
        }
    }

    private static void assertEmptyStack(ServerLevel level, CompoundTag tag, String key, String what) {
        if (!ItemStack.parseOptional(level.registryAccess(), tag.getCompound(key)).isEmpty()) {
            throw new GameTestAssertException("DUPE: " + what + " is not empty: " + tag.get(key));
        }
    }

    private static void assertEquals(int expected, int actual, String what) {
        if (expected != actual) {
            throw new GameTestAssertException(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertNear(Vec3 expected, Vec3 actual, double tolerance, String what) {
        if (expected.distanceTo(actual) > tolerance) {
            throw new GameTestAssertException(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static Vec3 worldPos(SubLevel subLevel, BlockPos plotPos) {
        return subLevel.logicalPose().transformPosition(Vec3.atCenterOf(plotPos));
    }

    private static ItemStack schematicItem(ServerLevel level, String file, BlockPos anchor) {
        ItemStack stack = SchematicItem.create(level, file, OWNER);
        stack.set(AllDataComponents.SCHEMATIC_ANCHOR, anchor);
        stack.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        return stack;
    }

    private static SchematicannonBlockEntity schematicannon(GameTestHelper helper, BlockPos relative, ItemStack schematic) {
        helper.setBlock(relative, AllBlocks.SCHEMATICANNON.getDefaultState());
        SchematicannonBlockEntity cannon = (SchematicannonBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(relative));
        cannon.inventory.setStackInSlot(0, schematic);
        cannon.state = SchematicannonBlockEntity.State.RUNNING;
        return cannon;
    }

    /** What a player does in the schematicannon screen: press "play" (again after the schematic was loaded). */
    private static void keepRunning(SchematicannonBlockEntity cannon) {
        if (cannon.state == SchematicannonBlockEntity.State.PAUSED && "ready".equals(cannon.statusMsg)) {
            cannon.state = SchematicannonBlockEntity.State.RUNNING;
        }
    }

    private static Set<UUID> subLevelIds(ServerLevel level) {
        Set<UUID> ids = new HashSet<>();
        for (SubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            ids.add(subLevel.getUniqueId());
        }
        return ids;
    }

    private static List<ServerSubLevel> newSubLevels(ServerLevel level, Set<UUID> before) {
        return SubLevelContainer.getContainer(level).getAllSubLevels().stream()
                .filter(subLevel -> !before.contains(subLevel.getUniqueId()) && !subLevel.isRemoved())
                .toList();
    }

    private static ServerSubLevel single(List<ServerSubLevel> subLevels, String what) {
        if (subLevels.size() != 1) {
            throw new GameTestAssertException(what + " should create exactly one physics structure, created " + subLevels.size());
        }
        return subLevels.get(0);
    }
}
