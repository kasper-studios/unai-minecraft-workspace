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
Enable AI agents to execute console commands, broadcast chat messages, inspect real-time server health (TPS, memory, uptime), observe player activity, and receive in-game chat notifications/events.

### Success criteria
- Functional UnAI Python workspace registering `@tool` methods (`minecraft.connect`, `minecraft.command`, `minecraft.chat`, `minecraft.status`, `minecraft.players`, `minecraft.messages_history`, `minecraft.notifications_feed`, `minecraft.notifications_clear`, `minecraft.disconnect`).
- Multi-platform server bridges (Paper plugin + Forge 1.21.1 mod) with identical REST API.
- Zero external runtime dependencies on server side (pure Java `com.sun.net.httpserver.HttpServer`).
- Secure auto-generated API key on first startup with console notice.
- Seamless installation via `unai workspace install minecraft`.

### Project type
- [x] Bot / automation
- [x] Game / mod
- [x] API / backend

---

## 2. Current Status

### Status summary
Completed & Deployed v1.0.0 (Live on `nodefrankfurt.kasperstudios.xyz`).

### Implemented
- [x] Architecture & protocol design
- [x] Python Workspace SDK implementation (`workspace/workspace.py`, `workspace/manifest.toml`, `workspace/run.py`)
- [x] Forge 1.21.1 server bridge mod (`unai_bridge`) with chat & lifecycle events
- [x] Paper/Spigot server bridge plugin (`UnAIBridge`) with chat & lifecycle events
- [x] Local builds of both jars in `build-artifacts/`
- [x] Published GitHub repository (`kasper-studios/unai-minecraft-workspace`)
- [x] GitHub Release `v1.0.0` with assets attached
- [x] Deployed and active on Frankfurt server (`nodefrankfurt.kasperstudios.xyz`)
- [x] Indexed in UnAI marketplace (`main/wsmarketplace/index.json`)
- [x] Installed and verified via `unai workspace install minecraft` and live test suite

---

## 3. Tech Stack

### Core stack
- Runtime: Python 3.11 (UnAI SDK) / Java 21 (Minecraft Server)
- Language(s): Python / Java
- Platform(s): Forge 1.21.1 / Paper & Spigot
- HTTP Server: Built-in JDK `com.sun.net.httpserver.HttpServer`
- Build tools: Gradle 8.10+

### Versions
- Minecraft: 1.21.1
- Forge: 52.1.16+
- Java: 21 (JDK 21)

---

## 4. Architecture Notes

### High-level architecture
```txt
[ UnAI Python Workspace ] ──(HTTP JSON / Bearer Auth)──> [ Minecraft Server (Port 25585) ]
  ├── minecraft.connect                                       ├── /api/status
  ├── minecraft.command                                       ├── /api/command
  ├── minecraft.chat                                          ├── /api/chat
  ├── minecraft.messages_history                              ├── /api/chat/history
  ├── minecraft.notifications_feed                            ├── /api/notifications/feed
  ├── minecraft.notifications_clear                           ├── /api/notifications/clear
  └── minecraft.status / players                              └── /api/players
```

### Security & Auth (ADR-0004)
- Server creates `config/unai-bridge.json` (or `plugins/UnAIBridge/config.yml`) with random token `unai_mc_<hex>`.
- Prints token to server console on launch.
- Workspace stores connection in `~/.unai/data/minecraft/session.json`.
- `minecraft.connect` tool vanishes once connected (`enabled_if=lambda ws: not ws.is_connected`).
- Session reset via `unai workspace reset-session minecraft`.
