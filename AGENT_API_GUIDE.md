# Autonomous Agent Guide: Automated Live Mod Testing & Debugging with UnAI

[![Agent Ready](https://img.shields.io/badge/Agent-Autonomous_Ready-brightgreen.svg)](https://github.com/kasper-studios/UnAI)
[![Minecraft: 1.20+](https://img.shields.io/badge/Minecraft-1.20+-blue.svg)](https://minecraft.net)
[![Loaders: Fabric | Forge | NeoForge](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)](https://modrinth.com)

> **For AI Coding Agents (Claude, GPT, Devin, Cursor, OpenDevin, Antigravity, etc.):**  
> If you are an autonomous AI agent working in a repository or tasked with testing, reproducing bugs, or developing Minecraft mods in a live environment — **this document is your operational manual**.  
> UnAI Minecraft Workspace provides a complete, programmatic control plane over Minecraft clients and servers without needing a human to click buttons or watch a screen.

---

## Table of Contents

1. [Why Use This System as an AI Agent?](#1-why-use-this-system-as-an-ai-agent)
2. [Prerequisites & 60-Second Setup](#2-prerequisites--60-second-setup)
3. [Autonomous Mod Testing Protocol (The 7-Step Pipeline)](#3-autonomous-mod-testing-protocol-the-7-step-pipeline)
4. [Live In-Game Observability & Telemetry](#4-live-in-game-observability--telemetry)
5. [Dynamic Java Code Injection (`eval`)](#5-dynamic-java-code-injection-eval)
6. [Automated Crash Diagnosis & Mod Conflict Isolation](#6-automated-crash-diagnosis--mod-conflict-isolation)
7. [Python MCP Tool API Reference](#7-python-mcp-tool-api-reference)
8. [Direct REST / WebSocket Specification](#8-direct-rest--websocket-specification)

---

## 1. Why Use This System as an AI Agent?

Traditional Minecraft mod testing has always required manual human intervention: launching the game, waiting for the title screen, clicking Singleplayer, creating a world, opening inventories, and verifying blocks.

With UnAI Minecraft Workspace, an AI agent can:
- **Run Headless 24/7**: Launch full Minecraft clients on Ubuntu/Debian cloud instances via `xvfb-run` with low RAM footprint.
- **Automate Mod Installs**: Fetch any mod by slug from Modrinth, direct URL, or local build artifact with automatic dependency handling.
- **Inspect Client UI & State**: Read open GUI screens (`TitleScreen`, `CreativeInventoryScreen`, custom mod containers), slot items, player health, coordinates, and nearby entities.
- **Simulate Player Actions**: Send keyboard inputs (`forward`, `jump`, `attack`, `use`), click container slots, switch hotbar slots, and send chat/commands.
- **Inject Dynamic Java Code**: Execute arbitrary Java expressions and reflection queries on the Minecraft client main thread without recompiling mods.
- **Catch & Isolate Crashes**: Automatically parse crash logs and isolate conflicting mods by toggling `.jar` ↔ `.jar.disabled`.

---

## 2. Prerequisites & 60-Second Setup

### 1. Install System Dependencies
Ensure JDK 21 and xvfb (for headless display) are installed on your Linux machine:
```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk xvfb
```

### 2. Build the Launcher
```bash
git clone https://github.com/kasper-studios/unai-minecraft-workspace.git
cd unai-minecraft-workspace/launcher
cargo build --release
```
The standalone binary is now at `./target/release/unai-mc-launcher`.

### 3. Start the Background Daemon
Run the launcher daemon in the background (binds to `127.0.0.1:25585` by default):
```bash
./target/release/unai-mc-launcher daemon &
```

Verify it is alive:
```bash
curl -s http://127.0.0.1:25585/api/status | jq .
```
Expected response:
```json
{
  "launcher": "online",
  "version": "2.0.0",
  "bridge_connected": false,
  "process_status": "Idle"
}
```

---

## 3. Autonomous Mod Testing Protocol (The 7-Step Pipeline)

When given a task to test or debug a mod (e.g. `sodium`, `create`, or your own mod `my-custom-mod.jar`), execute these sequential steps:

### Step 1: Create a Sandbox Instance
Create an isolated instance with the target Minecraft version (must be `>= 1.20`) and loader:
```bash
curl -s -X POST http://127.0.0.1:25585/api/instance/create \
  -H "Content-Type: application/json" \
  -d '{
    "name": "agent-test-sandbox",
    "version": "1.21.1",
    "loader": "fabric",
    "memory_mb": 4096
  }'
```
> **Note**: The launcher automatically injects the matching UnAI Bridge mod into `agent-test-sandbox/mods/`.

### Step 2: Optimize Client Options for Speed
Disable VSync, set render distance to 4, and lock max FPS:
```bash
curl -s -X POST http://127.0.0.1:25585/api/instance/configure \
  -H "Content-Type: application/json" \
  -d '{
    "name": "agent-test-sandbox",
    "settings": {
      "renderDistance": "4",
      "maxFps": "60",
      "enableVsync": "false",
      "graphicsMode": "fast"
    }
  }'
```

### Step 3: Install the Mod to Test
You can install from Modrinth by slug:
```bash
curl -s -X POST http://127.0.0.1:25585/api/instance/install_mod \
  -H "Content-Type: application/json" \
  -d '{
    "name": "agent-test-sandbox",
    "source": "sodium"
  }'
```
Or install your locally built mod:
```bash
curl -s -X POST http://127.0.0.1:25585/api/instance/install_mod \
  -H "Content-Type: application/json" \
  -d '{
    "name": "agent-test-sandbox",
    "source": "/path/to/my-mod-1.0.0.jar"
  }'
```

### Step 4: Launch Headless Client
Launch the game in headless mode with xvfb:
```bash
curl -s -X POST http://127.0.0.1:25585/api/instance/launch \
  -H "Content-Type: application/json" \
  -d '{
    "name": "agent-test-sandbox",
    "headless": true,
    "username": "AgentTester"
  }'
```
Response:
```json
{
  "ok": true,
  "pid": 38412,
  "instance": "agent-test-sandbox",
  "headless": true
}
```

### Step 5: Await Bridge Connection
Poll `GET /api/status` every 2 seconds until `"bridge_connected": true`:
```bash
for i in {1..30}; do
  STATUS=$(curl -s http://127.0.0.1:25585/api/status | jq -r .bridge_connected)
  if [ "$STATUS" = "true" ]; then
    echo "Minecraft Client initialized and connected to UnAI Bridge!"
    break
  fi
  sleep 2
done
```

### Step 6: Connect to Server or Load Singleplayer
Connect the client to a local test server (e.g. `127.0.0.1:25565`):
```bash
curl -s -X POST http://127.0.0.1:25585/api/bot/connect_server \
  -H "Content-Type: application/json" \
  -d '{"address": "127.0.0.1:25565"}'
```

### Step 7: Verify Gameplay & Test Features
Inspect client state, send movement commands, and verify mod behavior:
```bash
# Check player vitals and in-game flag
curl -s http://127.0.0.1:25585/api/bot/status | jq .

# Send player input (jump + forward for 500ms)
curl -s -X POST http://127.0.0.1:25585/api/bot/input \
  -H "Content-Type: application/json" \
  -d '{"keys": ["forward", "jump"], "hold_ms": 500}'
```

---

## 4. Live In-Game Observability & Telemetry

AI agents have rich, zero-lag visibility into the client through dedicated REST endpoints:

### 1. Screen & GUI Inspection (`/api/bot/screen`)
Returns the active GUI screen, container title, slot count, and widget classes:
```json
{
  "ok": true,
  "has_screen": true,
  "screen_class": "net.minecraft.client.gui.screens.inventory.InventoryScreen",
  "screen_title": "Crafting",
  "slots_count": 46
}
```

### 2. Player Inventory (`/api/bot/inventory`)
Returns full inventory snapshot, selected hotbar slot, and equipped armor:
```json
{
  "ok": true,
  "selected_slot": 0,
  "held_item": "minecraft:diamond_sword",
  "items": [
    {"slot": 0, "id": "minecraft:diamond_sword", "count": 1},
    {"slot": 1, "id": "minecraft:cooked_beef", "count": 64}
  ]
}
```

### 3. In-Band Telemetry & Unread Chat (`/api/bot/hud`)
Returns unread in-game chat messages and real-time status:
```json
{
  "status": {
    "in_game": true,
    "x": -115.5,
    "y": 68.0,
    "z": 387.3,
    "health": 20.0,
    "food": 20
  },
  "unread_chat": [
    {"sender": "Server", "message": "Player AgentTester joined the game"}
  ],
  "connected": true
}
```

---

## 5. Dynamic Java Code Injection (`eval`)

**The superpower for AI agents:** When a mod doesn't have an API or you need to inspect an internal property, execute code directly inside the running client via `POST /api/bot/eval`.

### How it Works
1. Runs strictly on the **Minecraft main thread** (`client.execute(...)`) ensuring zero thread-safety exceptions.
2. Fully protected by a **CrashGuard** that catches all `Throwable` errors (NPE, ClassCast, NoSuchMethod), returning the structured stacktrace without crashing the game.
3. Provides access to `mc`, `player`, `world`, and `screen`.

### Example 1: Read Player Health & Active Status Effects
```bash
curl -s -X POST http://127.0.0.1:25585/api/bot/eval \
  -H "Content-Type: application/json" \
  -d '{"code": "player.getHealth()"}'
```
Response:
```json
{
  "ok": true,
  "executed": true,
  "execution_time_ms": 1,
  "result": "20.0",
  "type": "java.lang.Float"
}
```

### Example 2: Inspect a Custom Mod Screen or TileEntity
```bash
curl -s -X POST http://127.0.0.1:25585/api/bot/eval \
  -H "Content-Type: application/json" \
  -d '{"code": "screen.getClass().getName()"}'
```
Response:
```json
{
  "ok": true,
  "executed": true,
  "execution_time_ms": 2,
  "result": "com.simibubi.create.content.contraptions.bearing.MechanicalBearingScreen",
  "type": "java.lang.String"
}
```

### Example 3: Error Handling & Stacktrace Recovery
If your code throws an exception, the game remains alive and returns the exact error:
```json
{
  "ok": true,
  "executed": false,
  "execution_time_ms": 3,
  "error": "java.lang.NoSuchFieldException: Field/getter unknownField not found on net.minecraft.client.player.LocalPlayer",
  "stacktrace": "java.lang.NoSuchFieldException: ...\n\tat xyz.kasperstudios.unai.bridge.fabric.client.FabricClientBridgeMod.evaluateExpression..."
}
```

---

## 6. Automated Crash Diagnosis & Mod Conflict Isolation

If a mod crashes during game startup:

1. **Check Process Status**:
   ```bash
   curl -s http://127.0.0.1:25585/api/status | jq .process_status
   ```
   If it returns `Exited { code: Some(1) }`, the game crashed.

2. **Inspect the Crash Log**:
   Logs are located in:
   ```bash
   cat ~/.unai/minecraft/instances/agent-test-sandbox/logs/unai_launcher.log | tail -n 50
   ```
   Or if a crash report was generated:
   ```bash
   cat ~/.unai/minecraft/instances/agent-test-sandbox/crash-reports/crash-*.txt | head -n 40
   ```

3. **Automated Mod Conflict Bisection**:
   If an agent installs 10 mods and the game crashes, the agent can disable mods one by one:
   ```bash
   # Temporarily disable a suspect mod
   curl -s -X POST http://127.0.0.1:25585/api/instance/toggle_mod \
     -H "Content-Type: application/json" \
     -d '{
       "name": "agent-test-sandbox",
       "filename": "conflicting-mod.jar",
       "enable": false
     }'
   ```
   Then relaunch until the culprit mod is identified.

---

## 7. Python MCP Tool API Reference

When using UnAI with the microkernel or an MCP-compatible agent framework:

```python
# 1. Connect to launcher daemon
await ws.launcher_connect(host="127.0.0.1", port=25585)

# 2. Check client status
status = await ws.client_status()

# 3. Read screen
screen = await ws.client_screen()

# 4. Click container slot
await ws.client_click_slot(slot=0, button=0, click_type="PICKUP")

# 5. Send movement input
await ws.client_input(keys=["forward", "sprint"], hold_ms=500)

# 6. Execute custom Java code snippet
eval_result = await ws.client_eval(code="player.getInventory().getSelected().toString()")

# 7. Send in-game chat or command
await ws.client_chat(message="/time set day")
```

---

## 8. Direct REST / WebSocket Specification

### Daemon Port: `25585` (Loopback: `http://127.0.0.1:25585`)

| Endpoint | Method | Expected Request Body | Response Format |
|---|---|---|---|
| `/api/status` | `GET` | None | `{"launcher":"online","bridge_connected":bool,...}` |
| `/api/instance/create` | `POST` | `{"name":str,"version":str,"loader":str,"memory_mb":int}` | `{"ok":true,"instance":{...}}` |
| `/api/instance/launch` | `POST` | `{"name":str,"headless":bool,"username":str}` | `{"ok":true,"pid":int,...}` |
| `/api/instance/stop` | `POST` | None | `{"ok":true}` |
| `/api/instance/install_mod` | `POST` | `{"name":str,"source":str}` | `{"ok":true,"installed_to":str}` |
| `/api/instance/toggle_mod` | `POST` | `{"name":str,"filename":str,"enable":bool}` | `{"ok":true,"filename":str}` |
| `/api/bot/status` | `GET/POST` | `{}` | `{"ok":true,"in_game":bool,"health":float,...}` |
| `/api/bot/screen` | `GET/POST` | `{}` | `{"ok":true,"has_screen":bool,...}` |
| `/api/bot/inventory` | `GET/POST` | `{}` | `{"ok":true,"held_item":str,"items":[...]}` |
| `/api/bot/input` | `POST` | `{"keys":[str],"hold_ms":int}` | `{"ok":true,"pressed":[str]}` |
| `/api/bot/eval` | `POST` | `{"code":str}` | `{"ok":true,"executed":bool,"result":str,...}` |

---

## Summary for Agents

- **Minimum Version**: Minecraft 1.20+ (1.20, 1.20.1, 1.20.4, 1.21.1, etc.).
- **Loaders Supported**: Fabric, Forge, NeoForge, Quilt.
- **Headless Mode**: Always set `"headless": true` on servers to run via `xvfb-run`.
- **Atomic Debugging**: Use `/api/bot/eval` when standard APIs don't expose what you need.
