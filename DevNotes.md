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
Enable AI agents to execute console commands, send in-game chat messages, inspect server status/metrics, and observe player activity through a unified HTTP/REST bridge.

### Success criteria
- Functional UnAI Python workspace registering `@tool` methods (`minecraft.connect`, `minecraft.command`, `minecraft.chat`, `minecraft.status`, `minecraft.players`, `minecraft.disconnect`).
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
Active development (v1.0.0 MVP).

### Implemented
- [x] Architecture & protocol design
- [x] Python Workspace SDK implementation (`workspace/workspace.py`, `workspace/manifest.toml`, `workspace/run.py`)
- [x] Forge 1.21.1 server bridge mod (`unai_bridge`)
- [x] Paper/Spigot server bridge plugin (`UnAIBridge`)

### In progress
- [ ] Local build and testing of jars
- [ ] GitHub repository publishing and release creation
- [ ] Deployment to Frankfurt server (`nodefrankfurt.kasperstudios.xyz`)
- [ ] UnAI marketplace registration (`wsmarketplace/index.json`)

### Planned / TODO
- [ ] Real-time event streaming via WebSocket / Event Bus (chat feed, player joins/deaths)
- [ ] Player inventory and block coordinate queries

---

## 3. Tech Stack

### Core stack
- Runtime: Python 3.11 (UnAI SDK) / Java 21 (Minecraft Server)
- Language(s): Python / Java
- Platform(s): Forge 1.21.1 / Paper & Spigot
- HTTP Server: Built-in JDK `com.sun.net.httpserver.HttpServer` (zero shading, zero conflicts)
- Build tools: Gradle 8.10+ / standard javac

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
  └── minecraft.status                                        └── /api/players
```

### Security & Auth (ADR-0004)
- Server creates `config/unai-bridge.json` (or `plugins/UnAIBridge/config.yml`) with a secure random token `unai_mc_<hex>`.
- Prints token to server console on launch.
- Workspace stores connection in `~/.unai/data/minecraft/session.json`.
- `minecraft.connect` tool vanishes once connected (`enabled_if=lambda ws: not ws.is_connected`).
- Session reset via `unai workspace reset-session minecraft`.

---

## 5. Project Structure

```txt
unai-minecraft-workspace/
├── DevNotes.md
├── README.md
├── pyproject.toml
├── .gitignore
├── workspace/
│   ├── manifest.toml
│   ├── run.py
│   ├── workspace.py
│   └── requirements.txt
└── server-bridge/
    ├── paper-plugin/
    │   ├── build.gradle
    │   └── src/main/java/xyz/kasperstudios/unai/bridge/paper/
    └── forge-mod/
        ├── build.gradle
        ├── gradle.properties
        └── src/main/java/xyz/kasperstudios/unai/bridge/forge/
```

---

## 6. Critical Rules
1. Never hardcode API keys or credentials into repository files.
2. HTTP server must not block the Minecraft main server tick thread; heavy operations run asynchronously, command dispatches execute on main thread.
3. Keep server bridge dependencies minimal (rely on standard Java libraries).
