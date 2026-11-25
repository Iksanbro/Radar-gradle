package dev.aeroschematics.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * A small "airship" carrying one device of every mod the user asked about, with every storage filled, every setting
 * changed from its default and links/wires between the devices. It is assembled into a Sable physics structure.
 */
final class TestShip {
    static final String WIRE_CHANNEL = "aerotest";

    /** Named devices, test-relative positions before assembly. */
    final Map<String, BlockPos> devices = new LinkedHashMap<>();
    final List<BlockPos> allBlocks = new ArrayList<>();
    /** Plot positions after assembly. */
    final Map<String, BlockPos> plotPositions = new LinkedHashMap<>();
    ServerSubLevel subLevel;

    private final GameTestHelper helper;
    private final boolean full;
    private final boolean tanker;

    private TestShip(GameTestHelper helper, boolean full) {
        this(helper, full, false);
    }

    private TestShip(GameTestHelper helper, boolean full, boolean tanker) {
        this.helper = helper;
        this.full = full;
        this.tanker = tanker;
    }

    /** Every fluid container of the requested mods on one structure, all of them filled. */
    static TestShip tanker(GameTestHelper helper) {
        TestShip ship = new TestShip(helper, false, true);
        ship.buildTanker();
        return ship;
    }

    private void buildTanker() {
        ServerLevel level = this.helper.getLevel();
        for (int x = 2; x < 7; x++) {
            for (int z = 2; z < 5; z++) {
                this.put(null, new BlockPos(x, 2, z), block("minecraft:stone").defaultBlockState());
            }
        }
        this.put("tank", new BlockPos(2, 3, 2), block("create:fluid_tank").defaultBlockState());
        this.put("spout", new BlockPos(3, 3, 2), block("create:spout").defaultBlockState());
        this.put("drain", new BlockPos(4, 3, 2), block("create:item_drain").defaultBlockState());
        this.put("basin", new BlockPos(5, 3, 2), block("create:basin").defaultBlockState());
        this.put("pulley", new BlockPos(6, 3, 2), block("create:hose_pulley").defaultBlockState());
        this.put("docking", new BlockPos(2, 3, 4), block("simulated:docking_connector").defaultBlockState());
        this.put("shell", new BlockPos(3, 3, 4), block("createbigcannons:fluid_shell").defaultBlockState());
        this.put("thruster", new BlockPos(4, 3, 4), block("create_flight_control:smart_vector_thruster").defaultBlockState());
        this.put("slab", new BlockPos(5, 3, 4), block("minecraft:oak_slab").defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        this.put("cauldron", new BlockPos(6, 3, 4), block("minecraft:lava_cauldron").defaultBlockState());

        FluidFill.fill(level, this.devices.get("tank"), new FluidStack(Fluids.LAVA, 8000));
        FluidFill.fill(level, this.devices.get("spout"), new FluidStack(Fluids.WATER, 1000));
        FluidFill.fill(level, this.devices.get("drain"), new FluidStack(Fluids.WATER, 1000));
        FluidFill.fill(level, this.devices.get("basin"), new FluidStack(Fluids.LAVA, 1000));
        FluidFill.fill(level, this.devices.get("pulley"), new FluidStack(Fluids.WATER, 1000));
        // The docking connector only accepts fluid while docked: write its tank the way Simulated stores it.
        merge(level, this.devices.get("docking"), nbt("{Tank:{Amount:1000L,Variant:{Fluid:\"minecraft:lava\"}}}"));
        merge(level, this.devices.get("shell"), nbt("{FluidContent:{Fluid:{id:\"minecraft:lava\",amount:250}}}"));
        merge(level, this.devices.get("thruster"), nbt("{fluidAmountMb:4000,storedFluidId:\"minecraft:lava\"}"));
    }

    static Block block(String id) {
        ResourceLocation rl = ResourceLocation.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(rl)) {
            throw new GameTestAssertException("Block " + id + " is not registered (mod missing?)");
        }
        return BuiltInRegistries.BLOCK.get(rl);
    }

    static CompoundTag nbt(String snbt) {
        try {
            return TagParser.parseTag(snbt);
        } catch (Exception e) {
            throw new IllegalArgumentException(snbt, e);
        }
    }

    /** Full ship with Flight Control, Create Big Cannons, Firepower Components, Drive-By-Wire and Create devices. */
    static TestShip full(GameTestHelper helper) {
        TestShip ship = new TestShip(helper, true);
        ship.build();
        return ship;
    }

    /** Three stone blocks and an autocannon ammo container full of rounds (survival consumption test). */
    static TestShip small(GameTestHelper helper) {
        TestShip ship = new TestShip(helper, false);
        ship.build();
        return ship;
    }

    private void build() {
        ServerLevel level = this.helper.getLevel();
        int size = this.full ? 5 : 3;
        for (int x = 2; x < 2 + size; x++) {
            for (int z = 2; z < (this.full ? 2 + size : 3); z++) {
                this.put(null, new BlockPos(x, 2, z), block("minecraft:stone").defaultBlockState());
            }
        }
        if (!this.full) {
            this.put("ammo", new BlockPos(2, 3, 2), block("createbigcannons:autocannon_ammo_container").defaultBlockState());
            this.data("ammo", "{Ammo:{id:\"createbigcannons:ap_autocannon_round\",count:64},Tracers:{id:\"createbigcannons:ap_autocannon_round\",count:16},TracerSpacing:3}");
            return;
        }

        // Create Big Cannons: a barrel with a loaded HE shell, an ammo container full of rounds.
        this.put("barrel", new BlockPos(2, 3, 2), block("createbigcannons:cast_iron_cannon_barrel").defaultBlockState());
        this.data("barrel", "{Pos:0L,State:{Name:\"createbigcannons:he_shell\",Properties:{facing:\"north\"}},Data:{Fuze:{id:\"createbigcannons:impact_fuze\",count:1}}}");
        this.put("ammo", new BlockPos(3, 3, 2), block("createbigcannons:autocannon_ammo_container").defaultBlockState());
        this.data("ammo", "{Ammo:{id:\"createbigcannons:ap_autocannon_round\",count:64},Tracers:{id:\"createbigcannons:ap_autocannon_round\",count:16},TracerSpacing:3}");

        // CBC: Firepower Components: large ammo box (integer counters) and a ready ammunition compartment.
        this.put("box", new BlockPos(4, 3, 2), block("cbc_firepower_components:large_autocannon_ammo_box").defaultBlockState());
        this.data("box", "{Ammo:{id:\"cbc_firepower_components:large_autocannon_round\",count:40},Tracers:{id:\"cbc_firepower_components:large_autocannon_he_round\",count:8},LargeMainAmmoCount:40,LargeTracerAmmoCount:8,TracerSpacing:2}");
        this.put("rack", new BlockPos(5, 3, 2), block("cbc_firepower_components:ready_ammunition_compartment").defaultBlockState());
        this.data("rack", "{Rounds:[{Slot:0b,Projectile:{id:\"createbigcannons:ap_shell\",count:1},Propellant:{id:\"createbigcannons:powder_charge\",count:1}}],InsertionCursor:1}");

        // Create: Flight Control: a thruster with fuel and non-default settings.
        this.put("thruster", new BlockPos(6, 3, 2), block("create_flight_control:smart_vector_thruster").defaultBlockState());
        this.data("thruster", "{fluidAmountMb:4000,storedFluidId:\"minecraft:lava\",flameColor:65280,halfAngleDeg:12.5f,soundVolScale:0.25f}");

        // Plain Create / vanilla: a chest with items, a redstone link with a frequency, a lectern controller.
        this.put("chest", new BlockPos(2, 3, 4), block("minecraft:chest").defaultBlockState());
        this.data("chest", "{Items:[{Slot:0b,id:\"minecraft:diamond\",count:64},{Slot:5b,id:\"createbigcannons:he_shell\",count:4}]}");
        this.put("link", new BlockPos(3, 3, 4), block("create:redstone_link").defaultBlockState().trySetValue(BlockStateProperties.FACING, Direction.UP));
        this.data("link", "{FrequencyFirst:{id:\"minecraft:diamond\",count:1},FrequencyLast:{id:\"minecraft:emerald\",count:1}}");
        this.put("lectern", new BlockPos(6, 3, 4), block("create:lectern_controller").defaultBlockState());

        // Drive-By-Wire: a controller hub wired to a lamp (wires are added after assembly).
        this.put("hub", new BlockPos(4, 3, 4), block("drivebywire:controller_hub").defaultBlockState());
        this.put("lamp", new BlockPos(5, 3, 4), block("minecraft:redstone_lamp").defaultBlockState());
    }

    private void put(String name, BlockPos relative, BlockState state) {
        this.helper.setBlock(relative, state);
        BlockPos absolute = this.helper.absolutePos(relative);
        this.allBlocks.add(absolute);
        if (name != null) {
            this.devices.put(name, absolute);
        }
    }

    private void data(String name, String snbt) {
        merge(this.helper.getLevel(), this.devices.get(name), nbt(snbt));
    }

    static void merge(ServerLevel level, BlockPos pos, CompoundTag extra) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            throw new GameTestAssertException("No block entity at " + pos + " (" + level.getBlockState(pos) + ")");
        }
        HolderLookup.Provider registries = level.registryAccess();
        CompoundTag tag = blockEntity.saveWithoutMetadata(registries);
        tag.merge(extra);
        blockEntity.loadWithComponents(tag, registries);
        blockEntity.setChanged();
    }

    static CompoundTag saved(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            throw new GameTestAssertException("No block entity at " + pos + " (" + level.getBlockState(pos) + ")");
        }
        return blockEntity.saveWithoutMetadata(level.registryAccess());
    }

    /** Turns the ship into a physics structure, then adds the links that refer to plot positions. */
    ServerSubLevel assemble() {
        ServerLevel level = this.helper.getLevel();
        BlockPos anchor = this.allBlocks.get(0);
        BoundingBox3i bounds = BoundingBox3i.from(this.allBlocks).expand(1, 1, 1);
        this.subLevel = SubLevelAssemblyHelper.assembleBlocks(level, anchor, this.allBlocks, bounds);
        BlockPos plotAnchor = this.subLevel.getPlot().getCenterBlock();
        this.devices.forEach((name, pos) -> this.plotPositions.put(name, pos.subtract(anchor).offset(plotAnchor)));
        for (Map.Entry<String, BlockPos> entry : this.plotPositions.entrySet()) {
            BlockState state = level.getBlockState(entry.getValue());
            if (state.isAir()) {
                throw new GameTestAssertException("Assembly did not move '" + entry.getKey() + "' to " + entry.getValue());
            }
        }
        if (this.full) {
            // Drive-By-Wire: the lectern belongs to the hub (absolute position) and the hub drives the lamp by wire.
            merge(level, this.plotPositions.get("lectern"), nbt("{DriveByWireHub:" + this.plotPositions.get("hub").asLong() + "L}"));
            WireNetworkManager.ConnectionResult result = WireNetworkManager.createConnection(level, this.plotPositions.get("hub"),
                    this.plotPositions.get("lamp"), Direction.UP, WIRE_CHANNEL);
            if (!result.isSuccess()) {
                throw new GameTestAssertException("Could not create the test wire: " + result.getDescription());
            }
        }
        return this.subLevel;
    }

    /** Where {@code name} of this ship ended up in another (printed) structure. */
    BlockPos in(ServerSubLevel copy, String name) {
        BlockPos original = this.plotPositions.get(name);
        return original.subtract(this.subLevel.getPlot().getCenterBlock()).offset(copy.getPlot().getCenterBlock());
    }

    /** Whether block entity data still holds a fluid in any layout the sanitizer knows. */
    static boolean findFluid(net.minecraft.nbt.Tag tag) {
        dev.aeroschematics.nbt.NbtSanitizer scanner = new dev.aeroschematics.nbt.NbtSanitizer(new dev.aeroschematics.nbt.SanitizerRules(),
                dev.aeroschematics.nbt.AeroSanitizer.REGISTRY_LOOKUP);
        return findFluid(scanner, tag);
    }

    private static boolean findFluid(dev.aeroschematics.nbt.NbtSanitizer scanner, net.minecraft.nbt.Tag tag) {
        if (tag instanceof CompoundTag compound) {
            if (scanner.looksLikeFluidStack(compound)) {
                return true;
            }
            for (String key : compound.getAllKeys()) {
                if (findFluid(scanner, compound.get(key))) {
                    return true;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (net.minecraft.nbt.Tag element : list) {
                if (findFluid(scanner, element)) {
                    return true;
                }
            }
        }
        return false;
    }

    static ListTag list(CompoundTag tag, String key) {
        return tag.getList(key, 10);
    }
}
