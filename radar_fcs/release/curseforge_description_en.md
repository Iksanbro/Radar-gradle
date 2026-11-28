# Create: Radar Fire Control

**Automatic fire control for Create Big Cannons.** Radars from **Create: Radars** find the targets, the **Fire Control Computer** tracks them, solves the ballistics with lead, turns your cannons through shaft-driven aim controllers and fires — only when the barrel is really on target and no friendlies are in the line of fire.

From anti-aircraft autocannon batteries to big guns firing at airships — and even shooting down incoming shells and rockets.

---

## ✨ Features

### 🎯 Fire Control Computer
- Tracks targets from Create: Radars and **leads moving targets** using real CBC projectile physics (drag, gravity, barrel length, charges).
- **Learns from its own shots**: muzzle velocity, drag corrections, rocket acceleration.
- Fires only when the predicted miss is within tolerance; checks the line of fire for **terrain and friendlies**.
- Hits targets moving at **70–100 m/s** (tested with autocannons and big cannons).
- Settings screen with tabs: **fire mode** (single, automatic, bursts with pause), **target types** with priority levels and speed windows, **white/black lists**, **target priority** (nearest, farthest, fastest, largest…), **target dropping and switching** rules, **per-cannon targets**, status report with Assemble/Disassemble buttons.
- Works with Engineer's Goggles, Display Links and comparators.

### ⚙️ Aim Controllers
- **Yaw** and **pitch** controllers placed next to a CBC cannon mount. The shaft speed is the turn speed — just like CBC's own shafts (256 RPM = 192°/s).
- **Rotation limits** for each direction (left/right, up/down, 1–360°) — set with a Create slider, just like the Torsion Spring. Cannons never swing through a forbidden arc.

### 📡 Radar Network & Radar Monitor
- **Radar Links** work like Create's Stock Links: everything on one network is connected — at any distance.
- Multiblock **Radar Monitor**: a live **map** with radar beams synced to the radar's rotation, target icons, names, friendly/hostile colors, player pins, range scale and a full GUI (designate a target, exclude a target, filters, zoom up to 1,000,000 blocks).
- Pick a target on your monitor (or on a Create: Radars monitor) — the guns engage it out of turn.
- **Laser Target Designator**: bind it to a monitor, aim like a spyglass (zoom, laser beam, target name and range) and designate a mob, projectile or ship block from afar; a mouse wheel click excludes the target from fire.
- **Memory Blocks** extend list capacity and network size.

### 🛡️ IFF Transponders (friend or foe)
- Transponders with **codes**, **protection zones** (ship, radius or both) and **inheritance rules** (which types inside the zone are protected).
- Whitelist or blacklist of codes per computer. Ships and contraptions carrying an accepted transponder are friendly.
- The computer never fires **through** a friendly zone.

### 🎥 Cameras
- Security-style cameras that rotate and remember their view. Look through a camera from the **Camera Monitor**: the guns follow your view, **left-click fires** (hold for continuous fire).
- Choose which guns each camera controls. Works with targets on moving Sable ships, with lead. With the cursor in the air the guns point along your view.

### 🔧 Custom guns (Create Aeronautics / Sable)
- Build your own turret on **Swivel Bearings** or this mod's **Gun Bearings**: a **Sight**, **Gun Drives** (yaw and pitch from any shafts, gear ratio learned automatically) and **Gun Links** that join all parts into one gun.
- **Gun Bearing** (only with Create Aeronautics; crafted from an Aeronautics Swivel Bearing and 4 springs): works like the Aeronautics swivel bearing — cog ring, shaft through the plate, lock modes — but damps inertia, so the turret does not overshoot or sway even with a heavy barrel.
- Gun settings: type (autocannon, medium, big, custom), auto-aim on/off, firing on/off, main cannon, name.

### 🛩️ Autopilot Bridge (requires Create: Flight Control)
- The block exists **only when Create: Flight Control is installed** and is crafted from its parts.
- Flies a Flight Control ship to the **radar network's target**: the one designated on a monitor (own or Create: Radars), otherwise the fire control computer's target.
- **Deflections used to track the target** are toggled separately: **yaw** (nose on the target bearing), **roll** (bank into the turn) and **pitch** (nose on the target elevation), with roll and pitch limits. The nose comes from the Flight Control **Forward Indicator** (linked with the Flight Control Tool Gun).
- Modes **"turn only"**, **"distance only"** and **"distance and altitude"** — distance, height above the target and speed limit are typed in.
- Works like the Flight Control **Navigation Terminal**: flies the ship through a **Velocity Coupler** and an **Angular Velocity Coupler** linked to the Flight Control Computer and switches the stabilizers off while flying to the target. It chases nothing else: with no target the ship **hovers** or flies an **orbit** around a point or an **Orbit Beacon** of its network (radius, height, direction, orbit speed, where the nose points). Redstone pauses it.

### ⚓ Docking Controller (requires Create: Flight Control and Create Aeronautics)
- One block, two roles: **port** (next to the connectors ships dock to — ground, station, another ship) and **plug** (on the ship next to its own connectors). Up to **8 Docking Connectors** per controller, linked with the Flight Control Tool Gun and highlighted like Flight Control links.
- The bridge takes the ship to the **nearest free port with the plug's number**, turning in advance so every connector meets its own port, keeping the **clearance distance** and flying around the port until it is on the port axis. Controllers extend the connectors; the connectors lock on their own.
- When to dock: when there is no target or on a redstone signal. While docked, the ship's Flight Control engines are off and its guns are inactive.
- Redstone on a port controller locks it: a docked ship cannot undock, a free port cannot be docked to.
- Docking on "no target" with an orbit set: while all ports are busy or locked, the ship flies the orbit.

### 🚀 Rocket noses (requires CBC Enchanced Shells)
- **Heat-seeking rocket nose**: after launch it finds a target of the chosen types ahead (Sable ships, players, hostile mobs, other entities, shells, rockets — the computer's target types) and steers the rocket to it; the locked target never changes.
- **Programmable rocket nose**: joins a radar network like a camera (right-click a network link with it) and sends the rocket to the network's selected target — designated on a monitor or chosen by the fire control computer, even beyond gun range. The target is locked at launch and never changes, even if the network drops it.
- Modes: **automatic launch** when the network has a target of the allowed type (any or one type), **automatic launch only on a target designated by hand** (monitor or Laser Designator), or **launch by redstone**.
- The computer's **Rockets** tab sets automatic launches: **single** (one rocket per target, time between launches) or **queue** (rockets per queue, delay between rockets, pause between queues).

### 💥 Shell & rocket interception (requires CBC Interception)
- Shoots down incoming **big cannon shells** and **rockets** (CBC, CBC Advanced Technologies and other addons): threat filter, types and priorities, "first to arrive first".
- **Requires the additional mod CBC Interception.** Without it CBC projectiles pass through each other, so interception is disabled and its settings tab is greyed out.

### 🎬 Ponder scenes
Hold **W** over any item of the mod — Create-style animated tutorials for every block and every settings tab.

---

## 🚀 Quick start
1. Put a cannon on a **CBC cannon mount** (or a CBC: Firepower Components compact mount).
2. Place the **yaw controller** under the mount and the **pitch controller** on its side; feed them rotation.
3. Build a working **radar** from Create: Radars.
4. Place a **Fire Control Computer** and put **Radar Links** on the computer, the mount and the radar — all on one network.
5. Give the computer **power (FE)** — e.g. from Create Crafts & Additions.

Done — the battery will engage hostile mobs and other targets you allow. Hold **W** over the computer for the full tutorial.

---

## 📦 Requirements
- Minecraft **1.21.1**, NeoForge **21.1.228+**
- **Create** 6.0.x
- **Create Big Cannons** 5.10+
- **Create: Radars** 0.4.9.x or 5.x

## 🔌 Optional
- **Create Aeronautics / Sable** — ships as targets, transponders on ships, custom turrets on Swivel Bearings and Gun Bearings
- **CBC Interception** — shooting down shells and rockets
- **Create: Flight Control** — the Autopilot Bridge, Docking Controller and Orbit Beacon (0.8.3+; docking also needs Create Aeronautics)
- **CBC Enchanced Shells** — heat-seeking and programmable rocket noses (tested 1.2.2 and 1.5.1)
- **CBC Advanced Technologies** — rifled barrels, heavy/twin autocannons, rockets (its reworked cannon physics is taken into account)
- **CBC Neo Warfare** — rotary and medium cannons
- **CBC: Firepower Components** — compact mounts
- Any **FE** power mod, e.g. **Create Crafts & Additions** (power can be disabled in the server config: `energy.requirePower`)

## ⚠️ Known issues
- **CBC Advanced Technologies 0.1.4c** is not compatible with CBC 5.11.4+ on its own (crash on firing and when its rockets hit a block). Use **CBC: Advanced technologies crash fix** (tested 1.1.4 with CBC 5.11.7) or CBC 5.11.3. This is not caused by this mod.
- Don't put Create: Radars' own auto yaw/pitch controllers on the same mount — the computer leaves such mounts to them.

---

## 🌐 Languages
English, Russian.

## 📄 License
MIT. The only library bundled inside the jar is Sable companion (MIT); all other mods are separate dependencies.
