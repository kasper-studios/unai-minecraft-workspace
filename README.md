# UnAI Minecraft Workspace

[![UnAI](https://img.shields.io/badge/UnAI-Workspace-blue)](https://github.com/kasper-studios/UnAI)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Native Minecraft Server Workspace for autonomous AI agents in the [UnAI](https://github.com/kasper-studios/UnAI) ecosystem.

Provides MCP tools to connect to Minecraft servers running the **UnAI Bridge** (Paper/Spigot plugin or Forge mod) to execute console commands, broadcast in-game chat messages, monitor server health (TPS, memory, uptime), and inspect online players.

---

## Capabilities & Tools

- **`minecraft.connect`**: Connect to any Minecraft server running the UnAI Bridge via IP and API key (one-shot tool).
- **`minecraft.disconnect`**: Disconnect and clear session credentials.
- **`minecraft.command`**: Execute console commands and get command output directly.
- **`minecraft.chat`**: Broadcast styled chat messages to in-game players.
- **`minecraft.status`**: Check server version, TPS, uptime, RAM usage, and player counts.
- **`minecraft.players`**: List online players, coordinates, dimensions, and latency.

---

## Server Bridges

Download the pre-built bridge jar for your server platform from [Releases](https://github.com/kasper-studios/unai-minecraft-workspace/releases):

1. **Forge 1.21.1 Mod**: Drop `unai-bridge-forge-1.21.1-1.0.0.jar` into your server's `mods/` directory.
2. **Paper / Spigot Plugin**: Drop `unai-bridge-paper-1.0.0.jar` into your server's `plugins/` directory.

On the first server launch, UnAI Bridge will generate a secure random API key and print it to the server console:
```log
[UnAI-Bridge] Server bridge listening on http://0.0.0.0:25585
[UnAI-Bridge] API Key: unai_mc_xxxxxxxxxxxxxxxx
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
