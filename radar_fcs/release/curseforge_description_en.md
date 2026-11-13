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
- **Memory Blocks** extend list capacity and network size.

### 🛡️ IFF Transponders (friend or foe)
- Transponders with **codes**, **protection zones** (ship, radius or both) and **inheritance rules** (which types inside the zone are protected).
- Whitelist or blacklist of codes per computer. Ships and contraptions carrying an accepted transponder are friendly.
- The computer never fires **through** a friendly zone.

### 🎥 Cameras
- Security-style cameras that rotate and remember their view. Look through a camera from the **Camera Monitor**: the guns follow your view, **left-click fires** (hold for continuous fire).
- Choose which guns each camera controls. Works with targets on moving Sable ships, with lead.

### 🔧 Custom guns (Create Aeronautics / Sable)
- Build your own turret on **Swivel Bearings**: a **Sight**, **Gun Drives** (yaw and pitch from any shafts, gear ratio learned automatically), **Gun Rotators** and **Gun Links** that join all parts into one gun.
- Gun settings: type (autocannon, medium, big, custom), auto-aim on/off, firing on/off, main cannon, name.

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
- **Create Aeronautics / Sable** — ships as targets, transponders on ships, custom turrets on Swivel Bearings
- **CBC Interception** — shooting down shells and rockets
- **CBC Advanced Technologies** — rifled barrels, heavy/twin autocannons, rockets (its reworked cannon physics is taken into account)
- **CBC Neo Warfare** — rotary and medium cannons
- **CBC: Firepower Components** — compact mounts
- Any **FE** power mod, e.g. **Create Crafts & Additions** (power can be disabled in the server config: `energy.requirePower`)

## ⚠️ Known issues
- **CBC Advanced Technologies 0.1.4c** is not compatible with CBC 5.11.4+ on its own (crash when a shell hits a block). Use **CBC: Advanced technologies crash fix** or CBC 5.11.3. This is not caused by this mod.
- Don't put Create: Radars' own auto yaw/pitch controllers on the same mount — the computer leaves such mounts to them.

---

## 🌐 Languages
English, Russian.

## 📄 License
MIT. The only library bundled inside the jar is Sable companion (MIT); all other mods are separate dependencies.
