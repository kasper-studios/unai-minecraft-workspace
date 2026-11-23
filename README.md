# UnAI Minecraft Workspace & Launcher (v2.0.0)

[![UnAI](https://img.shields.io/badge/UnAI-Workspace-blue)](https://github.com/kasper-studios/UnAI)
[![Version: 2.0.0](https://img.shields.io/badge/version-2.0.0-blue.svg)](pyproject.toml)
[![Minecraft: 1.20+](https://img.shields.io/badge/Minecraft-1.20+-brightgreen.svg)](https://minecraft.net)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Autonomous Minecraft ecosystem for AI agents in the [UnAI](https://github.com/kasper-studios/UnAI) runtime:
- **Standalone Rust Launcher (`unai-mc-launcher`)**: Headless `xvfb-run` client runner, automatic Modrinth mod management, and reverse WebSocket RPC bridge.
- **Client & Server Bridges**: Dual-sided multi-loader bridges (Fabric, Forge, NeoForge, Paper) for modern Minecraft (>= 1.20).
- **Dynamic Java Eval (`client.eval`)**: Atomic runtime Java code injection on the client main thread for live mod debugging and inspecting internal states.
- **Rich AI Bot Capabilities**: 3D A* pathfinding, persistent spatial memory (`minecraft.locations`), 3D ASCII & 2D rotated radar perception, inventory management, crafting, combat bodyguard AI, and automated treechopping.

---

## Documentation

- 🤖 **[Autonomous Agent API Guide](AGENT_API_GUIDE.md)**: Complete guide for AI agents to install the project from GitHub, run headless client instances, install mods from Modrinth, and debug live mods in real-time.
- 🚀 **[Launcher Documentation](launcher/README.md)**: Full reference for the standalone Rust launcher CLI, configuration, and HTTP/WebSocket REST daemon.
- ⚙️ **[Internal DevNotes](DevNotes.md)**: Architecture notes, telemetry specifications, and verification logs.

---

## Supported Versions Matrix

| Platform | Target Versions | Status | Notes |
|---|---|---|---|
| **Fabric** | **1.20 - 1.21.x+** | **Supported** | Embedded in launcher asset; client + server bridge |
| **Forge** | **1.20 - 1.21.x+** | **Supported** | Embedded in launcher asset; client + server bridge |
| **NeoForge** | **1.20.4 - 1.21.x+** | **Supported** | Full dynamic installer resolution |
| **Quilt** | **1.20 - 1.21.x+** | **Supported** | Uses Fabric bridge |
| **Paper / Spigot**| **1.20 - 1.21.x+** | **Supported** | Server plugin (`unai-bridge-paper-1.0.0.jar`) |
| **Legacy (< 1.20)**| 1.19, 1.16, 1.12... | **Deprecated** | Older versions (< 1.20) are outdated and explicitly rejected |

---

## Capabilities & Tools

### Local Client & Mod Debugging Tools (Launcher Mode)
- **`minecraft.launcher.connect`**: Connect to local `unai-mc-launcher` daemon.
- **`minecraft.client.status`**: Read live client coordinates, vitals, and in-game flag.
- **`minecraft.client.screen`**: Inspect currently open GUI screen, container title, and slot count.
- **`minecraft.client.inventory`**: Full player inventory and equipped item snapshot.
- **`minecraft.client.click_slot`**: Click slots in open menus/containers (PICKUP, QUICK_MOVE).
- **`minecraft.client.use_item`**: Use held item (right-click).
- **`minecraft.client.input`**: Press keyboard movement/action keys (forward, jump, attack, sneak, etc.).
- **`minecraft.client.chat`**: Send in-game chat or slash commands.
- **`minecraft.client.connect_server`**: Connect client to multiplayer server.
- **`minecraft.client.eval`**: **Inject and execute custom Java code** directly inside the running client for instant mod debugging.

### Server Tools (Dedicated Server Mode)
- **`minecraft.connect`**: Connect to remote Minecraft server running UnAI Bridge via IP and API key.
- **`minecraft.command`**: Execute console commands and get command output directly.
- **`minecraft.status`**: Check server version, TPS, uptime, RAM usage, and player counts.
- **`minecraft.players`**: List online players, coordinates, dimensions, and latency.
- **`bot.*`**: Full fake player avatar lifecycle, 3D A* pathfinding, spatial waypoints, emotes, inventory, block breaking, and building.

---

## Quick Start: Standalone Launcher

Build and run the launcher in 3 commands:

```bash
cd launcher
cargo build --release
./target/release/unai-mc-launcher daemon &
```

Create a Minecraft 1.21.1 Fabric instance, install a mod, and launch headless:

```bash
# Create instance
./target/release/unai-mc-launcher instance create test-client --version 1.21.1 --loader fabric

# Install mod from Modrinth
./target/release/unai-mc-launcher mod install test-client sodium

# Launch headless client
./target/release/unai-mc-launcher instance launch test-client --headless --username AgentTester
```

---

## Installation in UnAI

```bash
unai workspace install minecraft
unai workspace enable minecraft
```

---

## License

MIT © [kasper-studios](https://github.com/kasper-studios)
