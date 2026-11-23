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
- v2.0.0 (Standalone Rust Launcher, Dual-Sided Client/Server Mod, Reverse WebSocket RPC, Modrinth API, Ely.by & Headless xvfb, Deployed to nodefrankfurt & Dirom Connected) - COMPLETE & VERIFIED.
  * Verified live headless client launch on Minecraft 1.20.1 (crow-test with Fabric + KubeJS + Rhino).
  * Auto-connected to remote server `188.186.25.220:25565`, spawned in world at `X:-115.5 Y:68 Z:387.3`, broadcasted chat message via reverse WebSocket RPC.
  * Synapse report delivered to Dirom tablet (`192.168.1.102:8767/api/v1/event`).

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
- [x] Full Inventory & Survival System (`bot.select_slot`, `bot.swap_slots`, `bot.use_item`, `bot.clear_inventory`, `bot.craft`, `bot.break_block`, `bot.find_blocks`, automatic ground pickup)
- [x] Expressive Emotes & Body Movements (`twerk` / `teabag`, `nod`, `shake`, `spin`)
- [x] World Building Engine (`bot.place_block` with 3D audio & block state placement)
- [x] Container & Chest System (`bot.container_interact` for chest item list, deposit, and withdraw)
- [x] Autonomous Bodyguard & Combat AI (`bot.guard` auto-targets hostile mobs in 10m, auto-eats food on low HP)
- [x] Autonomous Woodchopper Routine (`bot.auto_chop` scans, navigates, and mines tree logs)
- [x] Dynamic Autonomous Chunk Loader Engine (`bot.chunk_loader` configurable 0..8 chunk radius, tickets and forceloads 3D area around bot)
- [x] Built and deployed `unai-bridge-forge-1.21.1-1.0.0.jar` to Frankfurt server (`nodefrankfurt.kasperstudios.xyz`)
- [x] Verified end-to-end via Python Minecraft Workspace test runner
- [x] Modern Minecraft (>= 1.20) version matrix enforcement with deterministic rejection of deprecated legacy versions
- [x] Dynamic Client Java Code Injection (`eval` / `minecraft.client.eval`) with CrashGuard, main-thread execution, and stacktrace diagnostics
- [x] Comprehensive Autonomous Agent Mod Debugging API Guide (`AGENT_API_GUIDE.md`)
- [x] Standalone Launcher Documentation (`launcher/README.md`)
- [x] Launcher unit test suite for version support matrix, embedded bridge resolution, and RPC responses (100% passing)

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

---

## 6. v2.0.0 Client Bridge & Standalone Launcher Architecture

### Key Requirements
1. **Repo & Releases**:
   - Launcher source lives in workspace repo: `wsrepos/unai-minecraft-workspace/launcher/` (`unai-mc-launcher` Rust crate).
   - Pre-compiled binaries published directly to GitHub releases alongside mod jars.
2. **Dual-Sided Unified Mod & Multi-Loader Auto-Installation**:
   - Single `.jar` per loader supports both server-side and client-side operation.
   - Built-in bridge JARs for **Fabric 1.21.1** and **Forge 1.21.1** are embedded directly inside the launcher binary (`include_bytes!`).
   - **Auto-Installation on Instance Creation**: When an instance is created (`instance create <name> --loader <fabric|forge|neoforge>`), the launcher dynamically detects the requested loader and auto-places the matching bridge jar (`unai-bridge-fabric-...` or `unai-bridge-forge-...`) into the instance's `mods/` directory.
   - For versions or loaders not bundled in the binary (e.g. 1.20.1 or future drops), the launcher automatically fetches the matching jar from GitHub Releases (`kasper-studios/unai-minecraft-workspace/releases`) into the local bridges cache. Zero manual setup required.
3. **Full Mod Lifecycle & Modrinth Integration**:
   - Agents can install any mod by Modrinth slug/name (`unai-mc-launcher mod install <instance> sodium`), direct HTTP URL, or local file.
   - Search mods on Modrinth via REST (`GET /api/modrinth/search`) or CLI (`unai-mc-launcher mod search`).
   - Enable/disable mods dynamically (`.jar` <-> `.jar.disabled`) and remove mods.
4. **Standalone UnAI Launcher (`unai-mc-launcher`)**:
   - Zero UnAI runtime / Python dependency: runnable standalone by custom agent systems or CLI scripts.
   - Default instances directory: `~/.unai/minecraft/instances/`.
   - Fully configurable via CLI (`unai-mc-launcher config set ...`) and global flags (`--data-dir`, `--port`).
   - Supports Ely.by skins via `-javaagent:authlib-injector.jar=https://auth.ely.by` and offline/cracked auth.
   - Headless client mode via `xvfb-run` for low-resource 24/7 background operation.

### Reverse WebSocket RPC Protocol
- Mod connects to Launcher: `ws://127.0.0.1:<port>/game_bridge`
- Handshake: `{"op": "handshake", "token": "<token>", "version": "1.21.1", "side": "client"}`
- Request-Response RPC with correlation `id`:
  - Launcher -> Mod: `{"id": "req_1", "method": "<name>", "params": {...}}`
  - Mod -> Launcher: `{"id": "req_1", "ok": true, "result": {...}}` or `{"id": "req_1", "ok": false, "error": {"code": "...", "message": "..."}}`
- Asynchronous push events (chat, health/damage, disconnect, death):
  - Mod -> Launcher: `{"event": "chat", "data": {...}}`

### Client gameplay control expansion (in progress; not E2E-verified)
- The Fabric client bridge now exposes actual client inventory snapshots, open-screen/container contents, loaded and tab-list players, held-item use, container-slot clicks, bounded normal keyboard input (including hotbar selection), melee attack/swing, chat/commands, and server connection RPCs.
- Unsupported `action` values now return `UNSUPPORTED_ACTION` instead of a fabricated `executed: true` result.
- The launcher daemon binds to loopback and no longer enables permissive CORS. A daemon-specific port override is propagated into the launched client bridge endpoint.
- The active Fabric client jar was built through KasHub's existing `buildUnaiBridgeJar` source set and embedded into the launcher asset. This repository's `server-bridge/fabric-mod` currently has no standalone Gradle build files.
- Launcher RPC envelope tests cover flattened success payloads and error responses. They do not prove live client behavior.
- Live game verification is still pending: no client is attached. The local launcher configuration currently defaults to `offline` auth; do not treat that as an authenticated Microsoft/Mojang profile or impersonate a player. Confirm the usable MineBlaze login path before the live test.

