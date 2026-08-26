# UnAI Minecraft Workspace DevNotes

> IMPORTANT: This file contains critical project rules, implementation context, and constraints for the author and AI assistants.
> Treat this file as the operational contract of the project.
> If something is unclear, prefer existing code/project patterns over invention.

---

## 0. DevNotes Writing Rules

**DevNotes is an internal operational document, not a marketing page.
Do not add hype, “wow-effect”, promotional language, or exaggerated claims.
Write only what is useful for implementation, review, maintenance, and AI execution.**

---

## 1. Project Overview

### What this project is
Native Minecraft Server Workspace and bridge ecosystem for the UnAI runtime. Allows autonomous AI agents to interact with running Minecraft servers (Paper/Spigot and Forge) via standard MCP tools.

### Core goal
Enable AI agents to exist in the Minecraft world as full-featured virtual player entities: navigate via 3D A* pathfinding, store and navigate persistent spatial waypoints (`minecraft.locations`), observe the world via 3D ASCII first-person projection and 2D dynamic rotated radars, buffer perception frames for autonomous decision-making (e.g. noticing caves/ores while walking and interrupting paths), customize appearance/skins, receive contextual in-band HUD overlays (chat/vitals/custom watches piggybacked onto tool responses), execute commands, and interact with players and blocks.

### Success criteria
- Multi-platform server bridges (Paper plugin + Forge 1.21.1 mod) with identical REST API.
- Zero external runtime dependencies on server side (pure Java `com.sun.net.httpserver.HttpServer`).
- Virtual `ServerPlayer` entity with client packet synchronization (tab list, 3D model, animations, inventory, skin).
- Persistent Spatial Memory Layer (`minecraft.locations`): Saved named waypoints (home, mine, village, portals) with auto-distance sorting and instant `goto` navigation.
- Modular In-Band HUD & Telemetry (ADR-0005): Every tool response automatically carries unread in-game chat messages, vitals, and user-configured watch modules without extra polling calls.
- Dynamic Skin System:
  - Default baked-in author skin (`skinunai.png` cosmic void) bundled directly in mod resources.
  - Runtime custom skin support via player nickname (Mojang Session Server property), Mineskin/URL, or local PNG file.
- 3D A* Pathfinding adapted from KasHub engine (obstacles, jump clearances, ladders, swimming, danger avoidance, stuck detection).
- Perception Engine:
  - 3D First-Person ASCII raymarching view (`bot.view_ascii`) with depth shading and entity detection.
  - 2D Dynamically rotated radar (`bot.radar`) relative to bot heading with 8-way entity gaze arrows (`↑ ↗ → ↘ ↓ ↙ ← ↖`) and raycast occlusion (fog of war `?`).
  - Ring buffer of last 60 visual frames captured at ~5-10 FPS for playback, timeline queries, and autonomous path interruption on POI discovery (caves, ores, players).
- Python Workspace SDK wrapping all capabilities into atomic MCP `@tool` methods.

### Project type
- [x] Bot / automation
- [x] Game / mod
- [x] API / backend

---

## 2. Current Status

### Status summary
- v1.0.0 (Core Server REST Bridge, Chat Events, Unicode Fix) - COMPLETE & DEPLOYED.
- v1.1.0 (Fake Player Avatar, Skin System, Modular HUD Telemetry, KasHub A* Pathfinding, 3D ASCII & Rotated 2D Perception, Spatial Waypoint Memory Layer) - COMPLETE & DEPLOYED LIVE.

### Implemented
- [x] Architecture & protocol design
- [x] Python Workspace SDK implementation (`workspace/workspace.py`, `workspace/manifest.toml`, `workspace/run.py`)
- [x] Forge 1.21.1 server bridge mod (`unai_bridge`) with chat & lifecycle events
- [x] Paper/Spigot server bridge plugin (`UnAIBridge`) with chat & lifecycle events
- [x] Proper UTF-8 & Unicode unescape decoding for chat messages and JSON component rendering
- [x] Virtual `ServerPlayer` (Fake Player) entity lifecycle (`bot.spawn`, `bot.despawn`, `bot.say`, `bot.action`, `bot.equip`, `bot.look_at`)
- [x] Dynamic Skin Injection (Embedded default `skinunai.png` + Mojang Nickname property loader)
- [x] 3D A* Pathfinding engine (`AStarPathfinder.java`) ported from KasHub with jump clearance, ladders, water, hazard avoidance, and stuck detection
- [x] 3D First-Person ASCII Raymarching camera (`bot.view_ascii`) with distance shading (` `, `.`, `:`, `#`, `█`, `~`, `!`) and entity detection
- [x] 2D Heading-Rotated Dynamic Radar (`bot.radar`) with line-of-sight wall occlusion (`?`) and 8-way gaze arrows
- [x] Crosshair Raycast Target Inspector (`bot.target`)
- [x] 60-Frame Perception Ring Buffer (`bot.frames`)
- [x] Persistent Spatial Memory Layer (`minecraft.locations.set/get/list/remove/goto`) stored in `~/.unai/data/minecraft/locations.json`
- [x] In-Band Modular HUD & Piggyback Telemetry (ADR-0005) with unread chat auto-delivery
- [x] TabList registration (`PlayerList.players` reflection + `UPDATE_LISTED` packet)
- [x] Full 3D Skin Layer Customization (`DATA_PLAYER_MODE_CUSTOMISATION = 127`)
- [x] Realistic Player Knockback & Melee Weapon Attack Physics (3.5m reach check)
- [x] Native Inventory Tools (`bot.inventory`, `bot.equip`, `bot.drop` with visual 3D packet sync)
- [x] Built and deployed `unai-bridge-forge-1.21.1-1.0.0.jar` to Frankfurt server (`nodefrankfurt.kasperstudios.xyz`)
- [x] Verified end-to-end via Python Minecraft Workspace test runner

---

## 3. Tech Stack

### Core stack
- Runtime: Python 3.11 (UnAI SDK) / Java 21 (Minecraft Server)
- Language(s): Python / Java
- Platform(s): Forge 1.21.1 / Paper & Spigot
- HTTP Server: Built-in JDK `com.sun.net.httpserver.HttpServer`
- Storage: JSON-backed persistent data (`session.json`, `locations.json`, `hud_config.json`) in `~/.unai/data/minecraft/`
- Build tools: Gradle 8.10+

### Versions
- Minecraft: 1.21.1
- Forge: 52.1.16+
- Java: 21 (JDK 21)

---

## 4. Architecture & Perception Pipeline

### Spatial Memory Tree (`minecraft.locations`)
Saved persistent waypoints in `~/.unai/data/minecraft/locations.json`:
```txt
minecraft.locations
├── home           [-272.0, 60.0, 265.0]  (base, spawn)
├── mine           [-250.0, 45.0, 290.0]  (resources, mine)
├── village        [-120.0, 64.0, 500.0]  (trading)
├── nether_portal  [-280.0, 62.0, 250.0]  (portal)
└── friend_base    [-410.0, 68.0, 115.0]  (crow, kasper)
```
- Auto-detected coordinates if omitted on `locations.set(name)`.
- Real-time distance calculation relative to live bot position on `locations.list()` and `locations.get()`.
- Instant one-step travel via `locations.goto(name)`.

### In-Band Tool HUD (ADR-0005)
Every tool output automatically carries unread in-game chat messages, vitals, and navigation state.
```txt
[Result: Bot performed action: sneak]
────────────────────────────────────────────────────────────────
HUD [HP: 20.0/20 | Food: 20/20 | Pos: -272.0, 60.0, 265.0 | Yaw: 0.0° | Nav: IDLE]
[NEW IN-GAME CHAT]:
  <Crow5431> Диром, стой, я алмазы нашёл!
```

---

## 5. Live Verification Suite
Tested on `nodefrankfurt.kasperstudios.xyz`:
1. `bot.spawn`: Spawned `DiromPrime` with skin `kasperenok` at `X:-272, Y:60, Z:265`.
2. `bot.radar`: Verified 2D rotated heading radar with wall shadows (`?`) and bot position (`▲`).
3. `bot.view_ascii`: Verified 3D first-person raymarch view detecting `minecraft:emerald_block` in crosshair at 4.0m.
4. `bot.target`: Returned `{type: "block", id: "minecraft:emerald_block", dist: 4.0, x: -272, y: 61, z: 269}`.
5. `bot.action`: Executed `sneak` and rotation update.
6. `bot.say`: Broadcast in-game message `<DiromPrime> Тест зрения, 3D A* и HUD пройден успешно!`.
7. `locations.*`: Saved `home`, `mine`, `nether_portal`, computed live Euclidean distance from bot, and executed `locations.goto('home')`.
8. `HUD`: Confirmed automatic attachment of unread chat and vitals to tool results without dedicated polling calls.
