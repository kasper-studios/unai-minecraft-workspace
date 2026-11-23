# UnAI Minecraft Launcher (`unai-mc-launcher`)

[![Version: 2.0.0](https://img.shields.io/badge/version-2.0.0-blue.svg)](Cargo.toml)
[![Rust: 2021](https://img.shields.io/badge/rust-2021-orange.svg)](Cargo.toml)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

Autonomous Standalone Minecraft Launcher & Reverse RPC Bridge for AI Agents.

`unai-mc-launcher` is a lightweight, zero-Python-dependency Rust binary designed to run standalone or alongside the UnAI runtime. It manages Minecraft instances, automates loader installations (Fabric, Forge, NeoForge, Quilt), installs and manages mods directly from Modrinth, and bridges the running game process with autonomous AI agents via HTTP REST and bidirectional WebSocket RPC.

---

## Key Features

- **Autonomous Agent-First Design**: Completely scriptable via CLI and JSON HTTP REST API.
- **Modern Minecraft Support (>= 1.20)**: Full native support for Minecraft 1.20.x, 1.21.x, and future releases on Fabric, Forge, NeoForge, and Quilt. Legacy versions (< 1.20) are cleanly rejected.
- **Embedded Multi-Loader Bridges**: Ships with pre-compiled, embedded UnAI Bridge JARs (`unai-bridge-fabric-*.jar` and `unai-bridge-forge-*.jar`). Automatically installs the matching bridge mod into newly created instances.
- **Reverse WebSocket Game Bridge (`ws://.../game_bridge`)**: The in-game bridge mod connects back to the launcher daemon, enabling real-time RPC calls, telemetry push, and live chat events.
- **Headless Mode (`xvfb-run`)**: Allows AI agents to run full Minecraft client instances on headless cloud servers or CI/CD pipelines without a physical monitor or GPU display.
- **Full Mod Lifecycle & Modrinth Integration**: Search, download, install, enable, disable (`.jar` ↔ `.jar.disabled`), and delete mods via simple CLI or API calls.
- **Dynamic Java Code Injection (`eval`)**: Execute arbitrary Java code and reflection snippets directly inside the live client on the Minecraft main thread for rapid, atomic mod debugging.
- **Ely.by Skin Support & Offline Auth**: Bundles `-javaagent:authlib-injector.jar=https://auth.ely.by` for custom skins in offline mode.

---

## Architecture

```
┌────────────────────────────────────────────────────────┐
│                   Autonomous AI Agent                  │
│       (Claude, GPT, Devin, Cursor, MCP Client)         │
└───────────────────────────┬────────────────────────────┘
                            │ HTTP REST / WebSocket
                            ▼
┌────────────────────────────────────────────────────────┐
│           unai-mc-launcher (Rust Daemon)               │
│  - Instance Manager (~/.unai/minecraft/instances/)     │
│  - Downloader (Mojang, Fabric, Forge, NeoForge Meta)   │
│  - Modrinth Client (Mod search, resolve & download)    │
│  - Process Supervisor (Headless xvfb-run / JVM launch) │
│  - Reverse WebSocket Bridge Manager                    │
└───────────────────────────▲────────────────────────────┘
                            │ ws://127.0.0.1:25585/game_bridge
                            │ (Bi-directional RPC & Telemetry)
┌───────────────────────────▼────────────────────────────┐
│              Minecraft Client / Server                 │
│         UnAI Bridge Mod (Fabric / Forge 1.20+)         │
│  - Real-time Player Telemetry & HUD streaming          │
│  - Screen, Inventory, and Container Slot Clicks        │
│  - Keyboard / Action Emulation                         │
│  - Dynamic Java Eval (`doEval` on Main Thread)         │
└────────────────────────────────────────────────────────┘
```

---

## Installation & Build

### Prerequisites
- **Rust & Cargo** (1.75+)
- **Java 21 JDK** (`/usr/lib/jvm/java-21-openjdk-amd64` or system `java`)
- **xvfb** (optional, required only for headless mode): `sudo apt-get install -y xvfb`

### Building from Source

```bash
cd launcher
cargo build --release
```

The compiled binary will be located at:
```bash
./target/release/unai-mc-launcher
```

Optionally symlink or copy to your path:
```bash
sudo cp ./target/release/unai-mc-launcher /usr/local/bin/
```

---

## CLI Reference

### 1. Daemon Mode
Starts the background HTTP REST & Reverse WebSocket daemon:
```bash
unai-mc-launcher daemon
# Or on custom port
unai-mc-launcher daemon --port 25585
```

### 2. Instance Management
```bash
# List all instances
unai-mc-launcher instance list

# Create a new instance (Minecraft >= 1.20)
unai-mc-launcher instance create my-modded-client --version 1.21.1 --loader fabric --memory 4096

# Configure graphics and options
unai-mc-launcher instance configure my-modded-client renderDistance=4 maxFps=60

# Launch instance (interactive or headless)
unai-mc-launcher instance launch my-modded-client --headless --username AgentDirom

# Stop running instance
unai-mc-launcher instance stop

# Delete instance
unai-mc-launcher instance delete my-modded-client
```

### 3. Mod Management & Modrinth Search
```bash
# Search mods on Modrinth
unai-mc-launcher mod search "sodium" --loader fabric --version 1.21.1

# Install mod by Modrinth slug or name
unai-mc-launcher mod install my-modded-client sodium

# Install mod from direct HTTP URL or local .jar file
unai-mc-launcher mod install my-modded-client /path/to/my-custom-mod.jar

# List installed mods
unai-mc-launcher mod list my-modded-client

# Temporarily disable a mod (.jar -> .jar.disabled)
unai-mc-launcher mod disable my-modded-client sodium-fabric-0.5.11.jar

# Re-enable a mod (.jar.disabled -> .jar)
unai-mc-launcher mod enable my-modded-client sodium-fabric-0.5.11.jar

# Remove a mod
unai-mc-launcher mod remove my-modded-client sodium-fabric-0.5.11.jar
```

### 4. Configuration
```bash
unai-mc-launcher config show
unai-mc-launcher config get port
unai-mc-launcher config set port 25585
unai-mc-launcher config set default_version 1.21.1
unai-mc-launcher config set default_loader fabric
```

---

## REST API Reference

The daemon binds to loopback (`127.0.0.1:25585`) by default.

### System & Daemon
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/status` | Launcher status, version, process status, bridge connectivity |

### Instances
| Method | Endpoint | Payload / Query | Description |
|---|---|---|---|
| `GET` | `/api/instances` | - | List all instances |
| `POST` | `/api/instance/create` | `{"name": "...", "version": "1.21.1", "loader": "fabric", "memory_mb": 4096}` | Create new instance |
| `POST` | `/api/instance/delete` | `{"name": "..."}` | Delete instance |
| `POST` | `/api/instance/launch` | `{"name": "...", "headless": true, "username": "...", "auth_type": "offline"}` | Launch instance |
| `POST` | `/api/instance/stop` | - | Stop running instance |
| `POST` | `/api/instance/configure`| `{"name": "...", "settings": {"renderDistance": "4"}}` | Configure `options.txt` |

### Mod Management
| Method | Endpoint | Payload / Query | Description |
|---|---|---|---|
| `GET` | `/api/instance/mods` | `?name=instance_name` | List mods in instance with enabled state & file sizes |
| `POST` | `/api/instance/install_mod`| `{"name": "...", "source": "modrinth_slug_or_url_or_path"}` | Install mod |
| `POST` | `/api/instance/toggle_mod` | `{"name": "...", "filename": "...", "enable": true}` | Enable or disable mod |
| `POST` | `/api/instance/remove_mod` | `{"name": "...", "filename": "..."}` | Delete mod file |
| `GET` | `/api/modrinth/search` | `?query=sodium&loader=fabric&version=1.21.1` | Search Modrinth |

### In-Game Bot & Client Controls (`/api/bot/:method`)
Every method call is proxied directly across the reverse WebSocket bridge to the in-game client on the main Minecraft thread:

| Method | Endpoint | Payload | Description |
|---|---|---|---|
| `GET/POST`| `/api/bot/status` | `{}` | Live coordinates, health, food, dimension, in-game flag |
| `GET` | `/api/bot/hud` | - | Unread chat messages & latest status telemetry |
| `GET/POST`| `/api/bot/screen` | `{}` | Current GUI screen name, container items, buttons |
| `GET/POST`| `/api/bot/inventory` | `{}` | Player hotbar, inventory slots, equipped items |
| `GET/POST`| `/api/bot/players` | `{}` | Online players tab-list and nearby entities |
| `POST` | `/api/bot/use_item` | `{}` | Use held item (right click) |
| `POST` | `/api/bot/click_slot` | `{"slot": 0, "button": 0, "click_type": "PICKUP"}` | Click slot in open container |
| `POST` | `/api/bot/input` | `{"keys": ["forward", "jump"], "hold_ms": 200}` | Press movement/action keys |
| `POST` | `/api/bot/chat` | `{"message": "Hello world"}` | Send in-game chat or slash command |
| `POST` | `/api/bot/connect_server`| `{"address": "127.0.0.1:25565"}` | Connect client to server |
| `POST` | `/api/bot/eval` | `{"code": "player.getHealth()"}` | Dynamic Java / reflection evaluation |

---

## Directory Structure

```
~/.unai/minecraft/
├── config.toml           # Launcher global configuration
├── versions/             # Global cache for Minecraft jars & libraries
│   ├── 1.21.1/
│   ├── libraries/
│   ├── assets/
│   └── authlib-injector.jar
└── instances/            # Game instance directories
    └── my-test-client/
        ├── instance.json # Instance configuration
        ├── options.txt   # Game graphics & keybind settings
        ├── mods/         # Mod jars (with unai-bridge-*.jar auto-injected)
        ├── config/       # Mod configuration files
        ├── logs/         # unai_launcher.log, latest.log
        └── saves/        # Singleplayer worlds
```

---

## License

MIT © [KasperStudios](https://github.com/kasper-studios)
