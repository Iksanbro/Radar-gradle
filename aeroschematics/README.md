# Aeronautics Schematics

NeoForge 1.21.1 addon that lets you save **Create Aeronautics / Sable physics structures** (airships, planes,
cars...) as ordinary **Create schematics** and build them again exactly like any other schematic — with the
Schematic and Quill, the Schematic Table, the creative schematic tool and the **Schematicannon**.

Device settings, links between devices and Drive-By-Wire wires are kept. Stored items, fluids, ammunition, fuel and
energy are never copied, so a schematic cannot be used to duplicate anything.

## Download

[aeroschematics-1.0.0.jar](https://github.com/Iksanbro/Radar-gradle/raw/claude/create-aeronautics-schematic-save-jdo501/aeroschematics/release/aeroschematics-1.0.0.jar)
— put it into the `mods` folder (NeoForge 1.21.1). Every released version is kept in [`release/`](release/).

## How to use

Nothing new to learn — it is Create's schematic workflow:

1. **Save** – select an area around the ship with the *Schematic and Quill* and save it. Every physics structure that
   intersects the selection is saved completely (Sable's own integration), plus the extra data from this mod.
2. **Upload** the file at a *Schematic Table* and deploy the schematic with the schematic tool as usual (the preview
   already shows the ship).
3. **Build**
   * creative: *Print* from the schematic tool places the ship instantly;
   * survival: put the schematic into a *Schematicannon*. It first prints the ordinary blocks of the schematic, then the
     physics structures, consuming items and gunpowder for every block. The material list (clipboard) includes them.

While a ship is being printed it is held in place (Simulated's physics lock, the same as the creative physics staff)
and released once it is complete (`unlockAfterPrinting`).

## What is kept

| | |
| --- | --- |
| Device settings | Everything a block entity saves, minus stored resources (Flight Control thruster/autopilot settings, CBC mount angles and firing rates, Firepower controller modes, redstone link frequencies, filters, ...) |
| Links between blocks | Any block position or physics-structure id stored in block entity / entity data is re-targeted to the printed copy (Drive-By-Wire lectern hubs, Simulated swivel bearings, springs, merging glue, docking connectors, ropes, Flight Control welds and offsets, ...) |
| Drive-By-Wire wires | Captured from Drive-By-Wire's wire graph and recreated on the copy (optionally costs one wire item each, see `wireCharging`) |
| Several structures | All physics structures in the selection, including links and wires between them |

## What is never copied (anti-duplication)

Removed both when the schematic is **saved** (the file never contains them) and, authoritatively, on the server when it
is **placed/printed** (an edited schematic file cannot bring them back):

* any item or fluid stack anywhere in block entity or entity data (chests, tanks, machine buffers, held items,
  container components...), except "ghost" items that are pure configuration (filters, frequencies);
* **fluids in every layout the mods use**: NeoForge `{id, amount}` (Create fluid tanks, spouts, item drains, basins,
  mixers, hose pulleys, pipes and open pipe ends, tanks mounted on contraptions; CBC fluid shells, cannon casts,
  drill lubricant), legacy `{FluidName, Amount}`, and Simulated's own `{Amount, Variant:{Fluid}}` (docking connector
  tank). Related state goes too: the light of an emptied Create tank, the boiler's measured water supply, the rendered
  fill level, pipe flows. Waterlogged blocks are printed dry and filled cauldrons empty (Create's own placement rules);
  loose water/lava blocks have no item form and are not printed by the schematicannon;
* **Create Big Cannons** – loaded shells / powder charges inside barrels and chambers, rounds inside autocannon
  barrels, breech input/output/magazine, ammo container contents, fuzes and tracers, fluid shell contents;
* **CBC: Firepower Components** – large ammo box counters, ready ammunition compartment and carousel rack rounds,
  magazine loader and spent casing collector contents;
* **Create: Flight Control** – thruster fuel (amount and fluid), ion thruster energy, consumed sail material;
* **Drive-By-Wire** – backup block wire payload (wires are restored by this mod instead, and charged);
* **Create / Simulated** – blaze burner fuel, portable engine burn time, docking connector battery, kinetic runtime
  state (speed, stress network, rotation source).

Blocks whose item requirement already pays for their content (Offroad wheel mounts with a tire, navigation tables with
a map, CBC cartridges with powder) keep it — the schematicannon charges for it.

Assembled contraptions (e.g. a cannon that is currently assembled on its mount) are entities without an item form;
like in Create they are only placed by the creative tool (with their storages emptied), not by the schematicannon.
Disassemble them before saving if you want them printed in survival.

## Configuration (`config/aeroschematics-common.toml`)

* `sanitizing.extraStripRules` – more keys to remove for other mods: `namespace:block_entity#Key`,
  `namespace#Key`, or `...#Key=0` to zero a counter.
* `sanitizing.extraGhostKeys` – more keys that only hold configuration items (careful).
* `sanitizing.logSanitizing` – log every removal.
* `printing.unlockAfterPrinting`, `printing.restoreWires`, `printing.wireCharging` (`AUTO` follows Drive-By-Wire's
  `shouldConsumeWires`).

## Requirements

Minecraft 1.21.1, NeoForge ≥ 21.1.247, Create 6.0.10, Sable 2.0.6, Create Aeronautics 1.3.3 (Simulated).
Optional integrations: Drive-By-Wire (Sable) 0.3.0, Create Big Cannons 5.11.7, CBC: Firepower Components 0.4.4,
Create: Flight Control 0.8.5.

## Building and testing

```
./gradlew build                # mod jar in build/libs, runs the unit tests
./gradlew test                 # NBT sanitizer / link remapper / print order unit tests
./gradlew runGameTestServer    # end-to-end tests in a server with all the mods above (downloaded from Modrinth)
```

The game tests build a ship carrying Flight Control, Create Big Cannons, Firepower Components and Drive-By-Wire devices
with filled storages (loaded shell in a barrel, full ammo containers, rounds in racks, thruster fuel, a chest),
changed settings, a lectern-hub link and a wire, assemble it with Sable and save it with Create's schematic export:

| Test | Checks |
| --- | --- |
| `creativePlacementKeepsSettingsLinksAndWiresButNoContents` | the file contains no contents; the creative copy keeps every setting, the hub link and the wire, has no contents, and sits exactly where the preview was |
| `schematicannonPrintsPhysicsStructureWithSettingsLinksAndWires` | the same through a full schematicannon print; the structure is locked while printed, released and aligned afterwards |
| `schematicannonChargesBlocksButNeverStoredItems` | survival: material list, exactly the block items are consumed, the printed ammo container is empty |
| `handEditedSchematicCannotSmuggleItems` | items, a loaded shell, ammo counters and fuel written back into the file by hand never reach the world |
| `schematicannonResumesAfterReloadWithoutDuplicating` | the schematicannon is reloaded from its saved data mid-print and finishes the same structure, no second copy |
| `creativePlacementCopiesNoFluids`, `schematicannonPrintsNoFluids` | a "tanker" with a lava-filled Create tank, spout, item drain, basin, hose pulley, Simulated docking connector, CBC fluid shell, Flight Control thruster, waterlogged slab and lava cauldron: every printed container is empty when measured through its real fluid capability, the original keeps its fluids, the empty tank does not glow |

The build uses ModDevGradle 2.0.148 from the Gradle plugin portal; the third-party mods are resolved from Modrinth's
maven and installed into `run/*/mods` automatically.

## How it works (for developers)

* `capture/` – hooks `StructureTemplate.fillFromWorld/save` while Sable's schematic SAVE context is active: strips
  stored contents and records the source plot (center, square, origin), the source sub-level id and the wires.
* `place/PlacementSession` – allocates new sub-levels and prints every block at the **same offset from the plot
  center** as in the original, so absolute positions are fixed by one translation and center-relative ones stay valid.
  Block entity data is saved under Sable's PLACE context (schematic-aware mods re-target themselves), sanitized,
  re-targeted by `nbt/LinkRemapper`, then run through Create's `NBTProcessors`.
* `print/SubLevelPrintStage` – the schematicannon stage: seeds every structure first, then prints blocks in
  connectivity order (`place/ConnectivityOrder`, so Sable never splits a half-built ship), entities, wires.
* `nbt/NbtSanitizer` + `nbt/SanitizerRules` – the anti-duplication rules.
