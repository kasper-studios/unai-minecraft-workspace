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
Enable AI agents to exist in the Minecraft world as full-featured virtual player entities: navigate via 3D A* pathfinding, speak via in-game 3D spatial TTS & Voice Micro-DSL, observe the world via 3D ASCII first-person projection and 2D dynamic rotated radars, buffer perception frames for autonomous decision-making (e.g. noticing caves/ores while walking and interrupting paths), customize appearance/skins, receive contextual in-band HUD overlays (chat/vitals/custom watches piggybacked onto tool responses), execute commands, and interact with players and blocks.

### Success criteria
- Multi-platform server bridges (Paper plugin + Forge 1.21.1 mod) with identical REST API.
- Zero external runtime dependencies on server side (pure Java `com.sun.net.httpserver.HttpServer`).
- Virtual `ServerPlayer` entity with client packet synchronization (tab list, 3D model, animations, inventory, skin).
- In-Game 3D Spatial TTS & Voice Micro-DSL:
  - Neural TTS synthesis with sound effects & background music timing slices from UnAI VoiceEngine.
  - Native 3D proximity voice playback attached to the bot entity (Simple Voice Chat API / Plasmo Voice / Server sound packets).
- Modular In-Band HUD & Telemetry: Every tool response automatically carries unread in-game chat messages, vitals, and user-configured watch modules (radar, target, inventory, POI) without extra polling calls.
- Dynamic Skin System:
  - Default baked-in author skin bundled directly in mod resources.
  - Runtime custom skin support via player nickname (Mojang Session Server property), Mineskin/URL, or local PNG file.
- 3D A* Pathfinding adapted from KasHub engine (obstacles, jump clearances, ladders, swimming, danger avoidance, stuck detection).
- Perception Engine:
  - 3D First-Person ASCII raymarching view (`view_ascii`) with depth shading and entity detection.
  - 2D Dynamically rotated radar (`radar`) relative to bot heading with 8-way entity gaze arrows (`↑ ↗ → ↘ ↓ ↙ ← ↖`) and raycast occlusion (fog of war `?`).
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
- v1.1.0 (Fake Player Avatar, In-Game Spatial TTS, Skin System, Modular HUD Telemetry, KasHub A* Pathfinding, 3D ASCII & Rotated 2D Perception, 60-Frame Ring Buffer) - IN ARCHITECTURE & PLANNING.

### Implemented (v1.0.0)
- [x] Architecture & protocol design
- [x] Python Workspace SDK implementation (`workspace/workspace.py`, `workspace/manifest.toml`, `workspace/run.py`)
- [x] Forge 1.21.1 server bridge mod (`unai_bridge`) with chat & lifecycle events
- [x] Paper/Spigot server bridge plugin (`UnAIBridge`) with chat & lifecycle events
- [x] Proper UTF-8 & Unicode unescape decoding for chat messages and JSON component rendering
- [x] Local builds of both jars in `build-artifacts/`
- [x] Published GitHub repository (`kasper-studios/unai-minecraft-workspace`)
- [x] GitHub Release `v1.0.0` with assets attached
- [x] Deployed and active on Frankfurt server (`nodefrankfurt.kasperstudios.xyz`)
- [x] Indexed in UnAI marketplace (`main/wsmarketplace/index.json`)

### In progress (v1.1.0 Avatar, Voice TTS, Skins, Modular HUD & Perception Engine)
- [ ] Virtual `ServerPlayer` (Fake Player) lifecycle & packet handling (`bot.spawn`, `bot.despawn`, `bot.say`, `bot.action`)
- [ ] In-Game Spatial TTS & Voice Micro-DSL engine (`bot.voice_say`) with Simple Voice Chat / server audio bridge
- [ ] Modular In-band Tool HUD: Configurable telemetry modules (`chat`, `vitals`, `radar_mini`, `target`, `inventory`, `poi`) appended to all tool outputs
- [ ] Skin System: Default embedded author skin + custom Mojang nick / URL / file loader (`bot.skin_set`)
- [ ] 3D A* Pathfinding engine integration from KasHub (`bot.move_to`, `bot.stop_move`, `bot.nav_status`)
- [ ] 3D First-Person ASCII Raymarcher & 2D Dynamic Rotated Radar with LOS raycast
- [ ] 60-Frame ring buffer with ~5 FPS timeline query & POI event notifications

---

## 3. Tech Stack

### Core stack
- Runtime: Python 3.11 (UnAI SDK) / Java 21 (Minecraft Server)
- Language(s): Python / Java
- Platform(s): Forge 1.21.1 / Paper & Spigot
- Voice Engine: UnAI Voice Micro-DSL + Simple Voice Chat API / Plasmo Voice
- HTTP Server: Built-in JDK `com.sun.net.httpserver.HttpServer`
- Build tools: Gradle 8.10+

### Versions
- Minecraft: 1.21.1
- Forge: 52.1.16+
- Java: 21 (JDK 21)

---

## 4. Architecture & Perception Pipeline

### Perception & Navigation Workflow
```txt
[ Agent Goal: Walk to X:100 Z:200 ]
               │
               ▼
[ Start A* Pathfinding ] ──> [ Mod Ticks Movement (KasHub A*) ]
                                       │
                     ┌─────────────────┴─────────────────┐
                     ▼                                   ▼
        [ Capture Visual Frame ]              [ POI Raycast Checks ]
        (Depth / 3D ASCII / Radar)            (Caves, Ores, Players)
                     │                                   │
                     ▼                                   ▼
        [ 60-Frame Ring Buffer ]              [ If POI detected: Emit Alert ]
        (5 FPS playback timeline)                        │
                     │                                   ▼
                     └─────────────────> [ Agent inspects frame & halts path ]
                                         [ Agent reroutes into Cave/Objective ]
```

### In-Game Spatial Voice & Micro-DSL
- **Voice Synthesis:** Python workspace utilizes UnAI's `VoiceEngine` (EdgeTTS + SFX + background music mixing).
- **In-Game Playback:**
  1. If **Simple Voice Chat (SVC)** or **Plasmo Voice** is installed on the server: audio is transmitted as 3D positional Opus frames from the fake player entity's exact head coordinates. Players near the bot hear the AI speaking in directional proximity voice!
  2. **Fallback:** Audio played via server sound broadcast packets with 3D coordinate attenuation.

### Modular In-Band HUD (Universal Watch & Chat Overlay)
The HUD system is completely modular and customizable via `minecraft.bot.hud_config(modules=[...])`.
Whenever the agent executes any tool (`bot.move_to`, `bot.look_at`, `bot.action`, `bot.equip`, `command`), the response automatically appends the configured HUD modules:

Available HUD modules:
- `chat`: Unread in-game chat messages since last call (auto-marked read).
- `vitals`: Health, hunger, air, potion effects, armor durability.
- `position`: Coordinates $(X,Y,Z)$, dimension, current facing yaw/pitch.
- `target`: Entity or block currently in bot's crosshair.
- `radar_mini`: 3x3 or 5x5 ASCII mini-radar of immediate surroundings.
- `inventory`: Mainhand item, offhand, selected hotbar slot.
- `poi`: Nearby points of interest alerts (caves, ores, players in LOS).

### Skin System Specification
- **Default Skin:** Embedded directly in mod resources (`assets/unai_bridge/textures/entity/skin_default.png` / Base64 property). Rendered automatically when no custom skin is provided.
- **Custom Skin Loading:**
  1. `skin_name`: A Minecraft player nickname (e.g. `kasperenok`, `Crow5431`). Bridge fetches official textures property from Mojang Session Server.
  2. `skin_url` / `skin_file`: Custom PNG texture URL or local path processed via Mineskin / textures payload.
- Injected directly into the fake player's `GameProfile.getProperties().put("textures", ...)` and synchronized to clients via `ClientboundPlayerInfoUpdatePacket`.

### Planned MCP Tools (v1.1.0)
1. **Lifecycle & Avatar:**
   - `minecraft.bot.spawn(name, x, y, z, target_player, skin)`
   - `minecraft.bot.despawn()`
   - `minecraft.bot.skin_set(skin)` (Mojang nick, URL, local file, or "default")
   - `minecraft.bot.say(message)` (Text chat)
   - `minecraft.bot.voice_say(text, voice, effects)` (3D Spatial Voice TTS & Micro-DSL)
   - `minecraft.bot.action(action)` (sneak, swing, jump)
   - `minecraft.bot.equip(mainhand, armor)`
   - `minecraft.bot.hud_config(enabled=True, modules=["chat", "vitals", ...])`
2. **Pathfinding & Movement:**
   - `minecraft.bot.move_to(x, y, z, target, radius)`
   - `minecraft.bot.stop_move()`
   - `minecraft.bot.look_at(target | yaw, pitch)`
   - `minecraft.bot.nav_status()`
3. **Perception & Vision:**
   - `minecraft.bot.view_ascii(width, height, fov)` (3D First-Person View)
   - `minecraft.bot.radar(radius)` (2D Dynamic Heading-Rotated Radar)
   - `minecraft.bot.frames(limit, fps)` (Recent cached frame playback)
   - `minecraft.bot.target()` (Crosshair block/entity check)
   - `minecraft.players.radar()` (Line-of-sight & player state radar)
