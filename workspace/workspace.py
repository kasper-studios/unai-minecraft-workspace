"""UnAI Minecraft Workspace implementation.

Subclasses Workspace from UnAI SDK. Interacts natively with Minecraft servers
running the UnAI Bridge (Paper/Spigot plugin or Forge mod) via standard HTTP REST API.

Follows ADR-0004 for one-shot connect tool state management.
Follows ADR-0005 for In-Band HUD & contextual chat/vitals auto-delivery.
"""

import asyncio
import json
import os
from pathlib import Path
import time
from typing import Any, Dict, List, Optional
import aiohttp

from unai.sdk import Workspace, tool

DEFAULT_PORT = 25585


def _get_data_dir() -> Path:
    d = Path.home() / ".unai" / "data" / "minecraft"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _get_session_file() -> Path:
    return _get_data_dir() / "session.json"


def _get_hud_config_file() -> Path:
    return _get_data_dir() / "hud_config.json"


def _get_locations_file() -> Path:
    return _get_data_dir() / "locations.json"


def _read_locations() -> Dict[str, Any]:
    lf = _get_locations_file()
    if lf.exists():
        try:
            return json.loads(lf.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def _write_locations(locs: Dict[str, Any]) -> None:
    _get_locations_file().write_text(json.dumps(locs, indent=2, ensure_ascii=False), encoding="utf-8")


class MinecraftWorkspace(Workspace):
    """Native Minecraft Server Workspace for autonomous AI agents."""

    def __init__(self, runtime_id: str = "minecraft", bus: Optional[Any] = None, **kwargs: Any):
        super().__init__(runtime_id=runtime_id, bus=bus, **kwargs)
        self._host: Optional[str] = None
        self._port: int = DEFAULT_PORT
        self._api_key: Optional[str] = None
        self._base_url: Optional[str] = None
        self._server_info: Optional[Dict[str, Any]] = None
        self._hud_enabled: bool = True
        self._hud_modules: List[str] = ["chat", "vitals", "position", "target"]
        self._load_session()
        self._load_hud_config()

    def _load_session(self) -> None:
        sf = _get_session_file()
        if sf.exists():
            try:
                data = json.loads(sf.read_text())
                self._host = data.get("host")
                self._port = int(data.get("port", DEFAULT_PORT))
                self._api_key = data.get("api_key")
                self._base_url = data.get("base_url") or f"http://{self._host}:{self._port}"
                self._server_info = data.get("server_info")
            except Exception:
                pass

    def _load_hud_config(self) -> None:
        hf = _get_hud_config_file()
        if hf.exists():
            try:
                data = json.loads(hf.read_text())
                self._hud_enabled = bool(data.get("enabled", True))
                self._hud_modules = list(data.get("modules", ["chat", "vitals", "position", "target"]))
            except Exception:
                pass

    @property
    def is_connected(self) -> bool:
        return bool(self._base_url and self._api_key)

    def _get_headers(self) -> Dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
            headers["X-API-Key"] = self._api_key
        return headers

    async def _append_hud_if_enabled(self, result: Any) -> Any:
        if not self._hud_enabled or not self.is_connected:
            return result

        hud_sections = []
        try:
            url_hud = f"{self._base_url}/api/bot/hud"
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=2)) as session:
                async with session.get(url_hud, headers=self._get_headers()) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        st = data.get("status", {})
                        if st.get("spawned") and any(m in self._hud_modules for m in ["vitals", "position", "target"]):
                            hp = st.get("health", 20.0)
                            hunger = st.get("hunger", 20)
                            x, y, z = st.get("x", 0.0), st.get("y", 0.0), st.get("z", 0.0)
                            yaw, pitch = st.get("yaw", 0.0), st.get("pitch", 0.0)
                            nav = st.get("nav_status", "IDLE")
                            hud_sections.append(f"HUD [HP: {hp:.1f}/20 | Food: {hunger}/20 | Pos: {x:.1f}, {y:.1f}, {z:.1f} | Yaw: {yaw:.1f}° | Nav: {nav}]")

                        if "chat" in self._hud_modules:
                            unread = data.get("unread_chat", [])
                            chat_lines = []
                            for n in unread:
                                sender = n.get("sender", "Unknown")
                                msg = n.get("message", "")
                                ntype = n.get("type", "chat")
                                if ntype in ("chat", "chat_out"):
                                    chat_lines.append(f"<{sender}> {msg}")
                                elif ntype == "join":
                                    chat_lines.append(f"-> {sender} joined the game")
                                elif ntype == "leave":
                                    chat_lines.append(f"<- {sender} left the game")
                                elif ntype == "death":
                                    chat_lines.append(f"[DEATH] {msg}")
                                else:
                                    chat_lines.append(f"[{sender}]: {msg}")
                            if chat_lines:
                                hud_sections.append("[NEW IN-GAME CHAT]:\n  " + "\n  ".join(chat_lines))

            if hud_sections:
                hud_text = "\n────────────────────────────────────────────────────────────────\n" + "\n".join(hud_sections)
                if isinstance(result, str):
                    return result + hud_text
                elif isinstance(result, dict):
                    result["_hud"] = "\n".join(hud_sections)
                    return result
        except Exception:
            pass

        return result

    # ====================================================================
    # Auth & Connection Tools (ADR-0004)
    # ====================================================================

    @tool(
        "minecraft.connect",
        description="Connect to a Minecraft server running UnAI Bridge using server IP and API Key",
        arguments={
            "host": {
                "type": "string",
                "description": "Server IP or domain (e.g. 'nodefrankfurt.kasperstudios.xyz' or '127.0.0.1:25585')",
            },
            "api_key": {
                "type": "string",
                "description": "Server API Key from server console log or config/unai-bridge.json",
            },
            "port": {
                "type": "integer",
                "description": "Optional port override if not specified in host (default: 25585)",
            },
        },
        enabled_if=lambda ws: not ws.is_connected,
    )
    async def connect(
        self, host: str, api_key: str, port: Optional[int] = None, reason: Optional[str] = None
    ) -> str:
        clean_host = host.strip()
        clean_key = api_key.strip()

        target_port = port or DEFAULT_PORT
        if ":" in clean_host:
            parts = clean_host.split(":", 1)
            clean_host = parts[0]
            try:
                target_port = int(parts[1])
            except ValueError:
                pass

        if target_port == 80:
            base_url = f"http://{clean_host}"
        else:
            base_url = f"http://{clean_host}:{target_port}"

        status_url = f"{base_url}/api/status"

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {clean_key}",
            "X-API-Key": clean_key,
        }

        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as session:
                async with session.get(status_url, headers=headers) as resp:
                    resp_text = await resp.text()
                    if resp.status == 401 or resp.status == 403:
                        raise RuntimeError(
                            f"Authentication failed: Invalid API Key. Check server logs for the correct unai_mc_* key."
                        )
                    if resp.status >= 400:
                        raise RuntimeError(f"Failed to connect to Minecraft server ({resp.status}): {resp_text}")
                    data = json.loads(resp_text)
                    if not data.get("ok", True):
                        raise RuntimeError(f"Server returned error: {data.get('error', resp_text)}")
        except aiohttp.ClientConnectorError as e:
            raise RuntimeError(
                f"Could not connect to Minecraft server at {base_url}. Ensure UnAI Bridge is running. Error: {e}"
            )

        self._host = clean_host
        self._port = target_port
        self._api_key = clean_key
        self._base_url = base_url
        self._server_info = data

        session_data = {
            "host": clean_host,
            "port": target_port,
            "api_key": clean_key,
            "base_url": base_url,
            "server_info": data,
            "connected_at": time.time(),
        }
        _get_session_file().write_text(json.dumps(session_data, indent=2))

        version = data.get("version", "unknown")
        platform = data.get("platform", "Minecraft")
        tps = data.get("tps", 20.0)
        players = data.get("online_players", len(data.get("players", [])))
        max_players = data.get("max_players", 20)

        return (
            f"Successfully connected to {platform} server ({version}) at {clean_host}!\n"
            f"TPS: {tps} | Online: {players}/{max_players}"
        )

    @tool(
        "minecraft.disconnect",
        description="Disconnect from the Minecraft server and clear session credentials",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def disconnect(self, reason: Optional[str] = None) -> str:
        self._host = None
        self._port = DEFAULT_PORT
        self._api_key = None
        self._base_url = None
        self._server_info = None

        sf = _get_session_file()
        if sf.exists():
            sf.unlink()

        return "Disconnected from Minecraft server and cleared session credentials."

    # ====================================================================
    # Virtual Avatar (Bot Player) Tools
    # ====================================================================

    @tool(
        "minecraft.bot.spawn",
        description="Spawn the virtual bot player entity into the Minecraft world",
        arguments={
            "name": {"type": "string", "description": "Optional bot display nickname (default: 'DiromPrime')"},
            "x": {"type": "number", "description": "Optional spawn X coordinate"},
            "y": {"type": "number", "description": "Optional spawn Y coordinate"},
            "z": {"type": "number", "description": "Optional spawn Z coordinate"},
            "skin": {"type": "string", "description": "Optional skin spec (player nickname e.g. 'kasperenok' or 'default')"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_spawn(
        self, name: str = "", x: Optional[float] = None, y: Optional[float] = None, z: Optional[float] = None, skin: str = "", reason: Optional[str] = None
    ) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/spawn"
        payload = {"name": name, "skin": skin, "x": str(x) if x is not None else "", "y": str(y) if y is not None else "", "z": str(z) if z is not None else ""}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "spawn failed"))
                res = data.get("message", "Spawned")
                return await self._append_hud_if_enabled(f"Bot '{name or 'DiromPrime'}' spawned successfully: {res}")

    @tool(
        "minecraft.bot.despawn",
        description="Remove the virtual bot player entity from the world",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_despawn(self, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/despawn"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                return data.get("message", "Despawned")

    @tool(
        "minecraft.bot.status",
        description="Get bot player status, location, health, hunger, and navigation state",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_status(self, reason: Optional[str] = None) -> Dict[str, Any]:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/status"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                res = data.get("status", {})
                return await self._append_hud_if_enabled(res)

    @tool(
        "minecraft.bot.say",
        description="Make the bot speak in Minecraft chat from its in-game entity (<DiromPrime> message)",
        arguments={
            "message": {"type": "string", "description": "Message text to broadcast"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_say(self, message: str, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/say"
        payload = {"message": message}
        body_data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), data=body_data) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "say failed"))
                return await self._append_hud_if_enabled(f"Bot spoke: {message}")

    @tool(
        "minecraft.bot.action",
        description="Perform in-game physical action or emote e.g. 'jump', 'swing', 'attack', 'sneak', 'spin', 'twerk', 'teabag', 'nod', 'shake'",
        arguments={
            "action": {"type": "string", "description": "Action name: 'sneak' (toggle shift), 'swing' / 'attack', 'jump', 'spin' (360°), 'twerk' / 'teabag' (rapid crouch emote), 'nod' (head nod), 'shake' (head shake)"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_action(self, action: str, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/action"
        payload = {"action": action}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "action failed"))
                return await self._append_hud_if_enabled(f"Bot performed action: {action}")

    @tool(
        "minecraft.bot.inventory",
        description="Inspect the bot's inventory items, equipped armor, and held items",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_inventory(self, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/inventory"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                res_str = json.dumps(data, indent=2, ensure_ascii=False)
                return await self._append_hud_if_enabled(res_str)

    @tool(
        "minecraft.bot.equip",
        description="Equip an item into bot armor slot or main/offhand with live visual broadcast",
        arguments={
            "slot": {"type": "string", "description": "Slot name: 'mainhand', 'offhand', 'head', 'chest', 'legs', 'feet'"},
            "item": {"type": "string", "description": "Item ResourceLocation e.g. 'minecraft:netherite_sword', 'minecraft:diamond_chestplate', or 'air'"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_equip(self, slot: str, item: str, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/equip"
        payload = {"slot": slot, "item": item}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "equip failed"))
                return await self._append_hud_if_enabled(f"Bot equipped {item} in slot {slot}")

    @tool(
        "minecraft.bot.drop",
        description="Drop an item from the bot's inventory onto the ground in front of it",
        arguments={
            "slot": {"type": "integer", "description": "Inventory slot index (0-35), or -1 for current mainhand item", "default": -1},
            "count": {"type": "integer", "description": "Number of items to drop (default: 1)", "default": 1}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_drop(self, slot: int = -1, count: int = 1, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/drop"
        payload = {"slot": str(slot), "count": str(count)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "drop failed"))
                return await self._append_hud_if_enabled(f"Bot dropped item (slot: {slot}, count: {count})")

    @tool(
        "minecraft.bot.select_slot",
        description="Select active hotbar slot index (0-8) to hold that item in mainhand",
        arguments={
            "slot": {"type": "integer", "description": "Hotbar slot index (0-8)"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_select_slot(self, slot: int, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/select_slot"
        payload = {"slot": str(slot)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "select slot failed"))
                return await self._append_hud_if_enabled(f"Bot selected hotbar slot {slot}")

    @tool(
        "minecraft.bot.swap_slots",
        description="Move or swap items between any two inventory/armor/offhand slots",
        arguments={
            "from_slot": {"type": "integer", "description": "Source slot index (0-35 inventory, 36-39 armor, 40 offhand)"},
            "to_slot": {"type": "integer", "description": "Destination slot index"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_swap_slots(self, from_slot: int, to_slot: int, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/swap_slots"
        payload = {"from_slot": str(from_slot), "to_slot": str(to_slot)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "swap slots failed"))
                return await self._append_hud_if_enabled(f"Bot swapped slot {from_slot} with slot {to_slot}")

    @tool(
        "minecraft.bot.use_item",
        description="Use or consume the currently held item (eat food, drink potion, shoot bow, interact)",
        arguments={
            "hand": {"type": "string", "description": "Hand to use: 'mainhand' or 'offhand'", "default": "mainhand"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_use_item(self, hand: str = "mainhand", reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/use_item"
        payload = {"hand": hand}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "use item failed"))
                return await self._append_hud_if_enabled(f"Bot used item in {hand}")

    @tool(
        "minecraft.bot.clear_inventory",
        description="Clear all items from bot inventory and armor slots",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_clear_inventory(self, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/clear_inventory"
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "clear inventory failed"))
                return await self._append_hud_if_enabled("Bot inventory cleared.")

    @tool(
        "minecraft.bot.craft",
        description="Craft an item recipe using ingredients currently in the bot's inventory",
        arguments={
            "recipe": {"type": "string", "description": "Recipe ID or target item ID, e.g. 'minecraft:oak_planks', 'minecraft:crafting_table', 'minecraft:stick'"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_craft(self, recipe: str, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/craft"
        payload = {"recipe": recipe}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "craft failed"))
                msg = data.get("message", "Crafted")
                return await self._append_hud_if_enabled(f"Bot crafting result: {msg}")

    @tool(
        "minecraft.bot.break_block",
        description="Break or mine a block at specified world coordinates (within reach)",
        arguments={
            "x": {"type": "integer", "description": "Block X coordinate"},
            "y": {"type": "integer", "description": "Block Y coordinate"},
            "z": {"type": "integer", "description": "Block Z coordinate"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_break_block(self, x: int, y: int, z: int, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/break_block"
        payload = {"x": str(x), "y": str(y), "z": str(z)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "break block failed"))
                msg = data.get("message", "Mined")
                return await self._append_hud_if_enabled(f"Bot mined block: {msg}")

    @tool(
        "minecraft.bot.place_block",
        description="Place a block from inventory at specified world coordinates",
        arguments={
            "x": {"type": "integer", "description": "Target X coordinate"},
            "y": {"type": "integer", "description": "Target Y coordinate"},
            "z": {"type": "integer", "description": "Target Z coordinate"},
            "block_id": {"type": "string", "description": "Optional block ID e.g. 'minecraft:oak_planks', 'minecraft:blackstone' (or empty for current held block)"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_place_block(self, x: int, y: int, z: int, block_id: str = "", reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/place_block"
        payload = {"x": str(x), "y": str(y), "z": str(z), "block_id": block_id}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "place block failed"))
                msg = data.get("message", "Placed")
                return await self._append_hud_if_enabled(f"Bot placed block: {msg}")

    @tool(
        "minecraft.bot.fill_area",
        description="Fill or repair a 3D box area with blocks from inventory (e.g. patching explosion craters or building floors/walls)",
        arguments={
            "x1": {"type": "integer", "description": "Start X coordinate"},
            "y1": {"type": "integer", "description": "Start Y coordinate"},
            "z1": {"type": "integer", "description": "Start Z coordinate"},
            "x2": {"type": "integer", "description": "End X coordinate"},
            "y2": {"type": "integer", "description": "End Y coordinate"},
            "z2": {"type": "integer", "description": "End Z coordinate"},
            "block_id": {"type": "string", "description": "Block ID e.g. 'minecraft:blackstone', 'minecraft:cobblestone', 'minecraft:acacia_planks' (or empty for any block in inventory)", "default": ""},
            "replace_air_only": {"type": "boolean", "description": "True to only replace air/replaceable blocks, False to overwrite all (default: True)", "default": True}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_fill_area(self, x1: int, y1: int, z1: int, x2: int, y2: int, z2: int, block_id: str = "", replace_air_only: bool = True, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/fill_area"
        payload = {
            "x1": str(x1), "y1": str(y1), "z1": str(z1),
            "x2": str(x2), "y2": str(y2), "z2": str(z2),
            "block_id": block_id,
            "replace_air_only": "true" if replace_air_only else "false"
        }
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "fill area failed"))
                msg = data.get("message", "Filled area")
                return await self._append_hud_if_enabled(f"Bot area building: {msg}")

    @tool(
        "minecraft.bot.container_interact",
        description="Interact with a chest or container (list items, deposit, withdraw). Omit x,y,z to auto-target closest chest!",
        arguments={
            "action": {"type": "string", "description": "Action: 'list' (view items), 'deposit' (put items into container), 'withdraw' (take items from container)"},
            "item_id": {"type": "string", "description": "Item ID to deposit/withdraw e.g. 'minecraft:oak_log' or 'all' for all items", "default": "all"},
            "count": {"type": "integer", "description": "Number of items to deposit/withdraw (default: 64)", "default": 64},
            "x": {"type": "integer", "description": "Container X coordinate (optional, 0 for closest chest)", "default": 0},
            "y": {"type": "integer", "description": "Container Y coordinate (optional, 0 for closest chest)", "default": 0},
            "z": {"type": "integer", "description": "Container Z coordinate (optional, 0 for closest chest)", "default": 0}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_container_interact(self, action: str = "list", item_id: str = "all", count: int = 64, x: int = 0, y: int = 0, z: int = 0, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/container_interact"
        payload = {"x": str(x), "y": str(y), "z": str(z), "action": action, "item_id": item_id, "count": str(count)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if isinstance(data, list):
                    res_str = json.dumps(data, indent=2, ensure_ascii=False)
                elif not data.get("ok"):
                    raise RuntimeError(data.get("error", "container interact failed"))
                else:
                    res_str = data.get("message", "Container action completed")
                return await self._append_hud_if_enabled(res_str)

    @tool(
        "minecraft.bot.guard",
        description="Toggle autonomous bodyguard & auto-attack mode to defend player/bot from hostile mobs",
        arguments={
            "enabled": {"type": "boolean", "description": "True to enable bodyguard mode, False to disable"},
            "target": {"type": "string", "description": "Optional player nickname to guard (or empty to guard the bot itself)"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_guard(self, enabled: bool, target: str = "", reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/guard"
        payload = {"enabled": "true" if enabled else "false", "target": target}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "guard mode failed"))
                msg = data.get("message", "Guard mode updated")
                return await self._append_hud_if_enabled(msg)

    @tool(
        "minecraft.bot.auto_chop",
        description="Autonomous tree woodchopping routine (finds logs, navigates, breaks blocks)",
        arguments={
            "count": {"type": "integer", "description": "Target number of logs to chop (default: 5, max: 32)"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_auto_chop(self, count: int = 5, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/auto_chop"
        payload = {"count": str(count)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "auto chop failed"))
                msg = data.get("message", "Auto chop started")
                return await self._append_hud_if_enabled(msg)

    @tool(
        "minecraft.bot.chunk_loader",
        description="Configure autonomous chunk loader radius around the bot (0 to disable, 1..8 radius in chunks)",
        arguments={
            "radius": {"type": "integer", "description": "Chunk radius around bot (0 to disable, 1 = 3x3=9 chunks, 2 = 5x5=25 chunks, up to 8)", "default": 2}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_chunk_loader(self, radius: int = 2, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/chunk_loader"
        payload = {"radius": str(radius)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "chunk loader failed"))
                msg = data.get("message", "Chunk loader updated")
                return await self._append_hud_if_enabled(msg)

    @tool(
        "minecraft.bot.autonomous",
        description="Configure autonomous living and idle life engine (organic roaming, player mirroring, head turning, casual chores)",
        arguments={
            "enabled": {"type": "boolean", "description": "True to enable autonomous living mode, False to freeze bot in place", "default": True},
            "radius": {"type": "integer", "description": "Tether radius around home base in blocks (default: 8)", "default": 8}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_autonomous(self, enabled: bool = True, radius: int = 8, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/autonomous"
        payload = {"enabled": "true" if enabled else "false", "radius": str(radius)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "autonomous config failed"))
                msg = data.get("message", "Autonomous mode updated")
                return await self._append_hud_if_enabled(msg)

    @tool(
        "minecraft.bot.find_blocks",
        description="Scan nearby area around the bot to find specific blocks (e.g. 'log', 'ore', 'table', 'dirt')",
        arguments={
            "query": {"type": "string", "description": "Filter substring e.g. 'log', 'wood', 'ore', 'leaves', 'stone'", "default": ""},
            "radius": {"type": "integer", "description": "Search radius in blocks (1-24, default: 16)", "default": 16}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_find_blocks(self, query: str = "", radius: int = 16, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/find_blocks"
        payload = {"query": query, "radius": str(radius)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                res_str = json.dumps(data, indent=2, ensure_ascii=False)
                return await self._append_hud_if_enabled(res_str)

    @tool(
        "minecraft.bot.look_at",
        description="Rotate the bot's head to look at coordinates or set explicit yaw/pitch angles",
        arguments={
            "x": {"type": "number", "description": "Target point X"},
            "y": {"type": "number", "description": "Target point Y"},
            "z": {"type": "number", "description": "Target point Z"},
            "yaw": {"type": "number", "description": "Optional direct yaw angle (-180 to 180)"},
            "pitch": {"type": "number", "description": "Optional direct pitch angle (-90 to 90)"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_look_at(
        self, x: Optional[float] = None, y: Optional[float] = None, z: Optional[float] = None,
        yaw: Optional[float] = None, pitch: Optional[float] = None, reason: Optional[str] = None
    ) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/look_at"
        payload = {}
        if x is not None: payload["x"] = str(x)
        if y is not None: payload["y"] = str(y)
        if z is not None: payload["z"] = str(z)
        if yaw is not None: payload["yaw"] = str(yaw)
        if pitch is not None: payload["pitch"] = str(pitch)
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "look failed"))
                return await self._append_hud_if_enabled("Bot rotation updated.")

    @tool(
        "minecraft.bot.navigate",
        description="Navigate the bot to target coordinates using full 3D A* Pathfinding (obstacles, jumps, ladders, hazards)",
        arguments={
            "x": {"type": "number", "description": "Target X coordinate"},
            "y": {"type": "number", "description": "Target Y coordinate"},
            "z": {"type": "number", "description": "Target Z coordinate"},
            "radius": {"type": "number", "description": "Target reach radius in blocks (default: 1.2)", "default": 1.2}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_navigate(self, x: float, y: float, z: float, radius: float = 1.2, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/navigate"
        payload = {"x": str(x), "y": str(y), "z": str(z), "radius": str(radius)}
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "navigate failed"))
                msg = data.get("message", "Navigating")
                return await self._append_hud_if_enabled(f"3D A* Navigation started: {msg}")

    @tool(
        "minecraft.bot.stop_move",
        description="Stop bot movement immediately and abort active pathfinding",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_stop_move(self, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/stop_move"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "stop failed"))
                return await self._append_hud_if_enabled("Bot movement halted.")

    @tool(
        "minecraft.bot.nav_status",
        description="Get detailed 3D A* pathfinding navigation status and progress",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_nav_status(self, reason: Optional[str] = None) -> Dict[str, Any]:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/nav_status"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                res = data.get("nav", {})
                return await self._append_hud_if_enabled(res)

    @tool(
        "minecraft.bot.view_ascii",
        description="Render a 3D First-Person ASCII view of what the bot sees in front of its eyes (depth buffer, raymarch, entities)",
        arguments={
            "width": {"type": "integer", "description": "ASCII grid width (default: 36)", "default": 36},
            "height": {"type": "integer", "description": "ASCII grid height (default: 18)", "default": 18},
            "fov": {"type": "number", "description": "Horizontal FOV degrees (default: 70.0)", "default": 70.0}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_view_ascii(self, width: int = 36, height: int = 18, fov: float = 70.0, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/view_ascii?w={width}&h={height}&fov={fov}"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "view failed"))
                view = data.get("view", "")
                return await self._append_hud_if_enabled(view)

    @tool(
        "minecraft.bot.radar",
        description="Render a 2D Heading-Rotated ASCII Radar centered on the bot (forward is always UP ▲, with 8-way gaze arrows and line-of-sight fog-of-war ?)",
        arguments={
            "radius": {"type": "integer", "description": "Radar radius in blocks (default: 10)", "default": 10}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_radar(self, radius: int = 10, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/radar?r={radius}"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "radar failed"))
                radar = data.get("radar", "")
                return await self._append_hud_if_enabled(radar)

    @tool(
        "minecraft.bot.target",
        description="Inspect the block or entity directly in the bot's crosshair (raycast up to 32 blocks)",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_target(self, reason: Optional[str] = None) -> Dict[str, Any]:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/target"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                if not data.get("ok"): raise RuntimeError(data.get("error", "target failed"))
                res = data.get("target", {})
                return await self._append_hud_if_enabled(res)

    @tool(
        "minecraft.bot.frames",
        description="Retrieve recent perception frame snapshots from the 60-frame ring buffer (5 FPS timeline)",
        arguments={
            "limit": {"type": "integer", "description": "Number of recent frames (default: 10)", "default": 10}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_frames(self, limit: int = 10, reason: Optional[str] = None) -> List[Dict[str, Any]]:
        if not self._base_url or not self._api_key: raise RuntimeError("Not connected")
        url = f"{self._base_url}/api/bot/frames?limit={limit}"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                data = await resp.json()
                return data.get("frames", [])

    @tool(
        "minecraft.bot.hud_config",
        description="Configure In-Band HUD delivery settings and telemetry modules (ADR-0005)",
        arguments={
            "enabled": {"type": "boolean", "description": "Enable or disable in-band HUD overlay", "default": True},
            "modules": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Active HUD modules (e.g. ['chat', 'vitals', 'position', 'target'])"
            }
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def bot_hud_config(self, enabled: bool = True, modules: Optional[List[str]] = None, reason: Optional[str] = None) -> str:
        self._hud_enabled = enabled
        if modules is not None:
            self._hud_modules = list(modules)
        _get_hud_config_file().write_text(json.dumps({"enabled": self._hud_enabled, "modules": self._hud_modules}, indent=2))
        return f"HUD configured: enabled={self._hud_enabled}, modules={self._hud_modules}"

    # ====================================================================
    # Persistent Spatial Memory Layer (minecraft.locations)
    # ====================================================================

    @tool(
        "minecraft.locations.set",
        description="Save or update a named persistent spatial waypoint (home, mine, village, portal, etc.). If coordinates are omitted, saves current bot location.",
        arguments={
            "name": {"type": "string", "description": "Waypoint name (e.g. 'home', 'mine', 'nether_portal', 'kasper_base')"},
            "x": {"type": "number", "description": "Optional X coordinate (defaults to current bot X)"},
            "y": {"type": "number", "description": "Optional Y coordinate (defaults to current bot Y)"},
            "z": {"type": "number", "description": "Optional Z coordinate (defaults to current bot Z)"},
            "dimension": {"type": "string", "description": "Dimension identifier (default: 'minecraft:overworld')", "default": "minecraft:overworld"},
            "description": {"type": "string", "description": "Optional description of what is at this location"},
            "tags": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Optional categorization tags (e.g. ['base', 'resources', 'portal'])"
            }
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def locations_set(
        self, name: str, x: Optional[float] = None, y: Optional[float] = None, z: Optional[float] = None,
        dimension: str = "minecraft:overworld", description: str = "", tags: Optional[List[str]] = None,
        reason: Optional[str] = None
    ) -> str:
        clean_name = name.strip().lower().replace(" ", "_")
        if not clean_name:
            raise RuntimeError("Location name cannot be empty")

        final_x, final_y, final_z = x, y, z
        if final_x is None or final_y is None or final_z is None:
            # Query current bot position
            bot_st = await self.bot_status()
            if not bot_st.get("spawned"):
                raise RuntimeError("Bot is not spawned. Please specify explicit x, y, z coordinates.")
            final_x = bot_st.get("x", 0.0)
            final_y = bot_st.get("y", 64.0)
            final_z = bot_st.get("z", 0.0)

        locs = _read_locations()
        locs[clean_name] = {
            "name": clean_name,
            "x": round(float(final_x), 2),
            "y": round(float(final_y), 2),
            "z": round(float(final_z), 2),
            "dimension": dimension,
            "description": description,
            "tags": tags or [],
            "updated_at": time.time()
        }
        _write_locations(locs)

        res = f"Saved location '{clean_name}' at ({final_x:.1f}, {final_y:.1f}, {final_z:.1f}) in {dimension}"
        return await self._append_hud_if_enabled(res)

    @tool(
        "minecraft.locations.get",
        description="Retrieve a saved spatial waypoint by name with coordinates and distance from bot",
        arguments={
            "name": {"type": "string", "description": "Waypoint name (e.g. 'home', 'mine')"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def locations_get(self, name: str, reason: Optional[str] = None) -> Dict[str, Any]:
        clean_name = name.strip().lower().replace(" ", "_")
        locs = _read_locations()
        if clean_name not in locs:
            raise RuntimeError(f"Location '{clean_name}' not found in persistent memory")

        item = dict(locs[clean_name])
        try:
            bot_st = await self.bot_status()
            if bot_st.get("spawned"):
                bx, by, bz = bot_st.get("x", 0.0), bot_st.get("y", 0.0), bot_st.get("z", 0.0)
                dx = item["x"] - bx
                dy = item["y"] - by
                dz = item["z"] - bz
                item["distance_from_bot"] = round((dx*dx + dy*dy + dz*dz)**0.5, 2)
        except Exception:
            pass

        return await self._append_hud_if_enabled(item)

    @tool(
        "minecraft.locations.list",
        description="List all persistent spatial waypoints with coordinates and tags",
        arguments={
            "tag": {"type": "string", "description": "Optional tag filter (e.g. 'base', 'portal')"},
            "dimension": {"type": "string", "description": "Optional dimension filter"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def locations_list(self, tag: str = "", dimension: str = "", reason: Optional[str] = None) -> List[Dict[str, Any]]:
        locs = _read_locations()
        result = []

        bot_pos = None
        try:
            bot_st = await self.bot_status()
            if bot_st.get("spawned"):
                bot_pos = (bot_st.get("x", 0.0), bot_st.get("y", 0.0), bot_st.get("z", 0.0))
        except Exception:
            pass

        for k, v in locs.items():
            if tag and tag not in v.get("tags", []):
                continue
            if dimension and v.get("dimension") != dimension:
                continue
            item = dict(v)
            if bot_pos:
                dx = item["x"] - bot_pos[0]
                dy = item["y"] - bot_pos[1]
                dz = item["z"] - bot_pos[2]
                item["distance_from_bot"] = round((dx*dx + dy*dy + dz*dz)**0.5, 2)
            result.append(item)

        result.sort(key=lambda x: x.get("distance_from_bot", 999999))
        return result

    @tool(
        "minecraft.locations.remove",
        description="Delete a saved spatial waypoint from persistent memory",
        arguments={
            "name": {"type": "string", "description": "Waypoint name to remove"}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def locations_remove(self, name: str, reason: Optional[str] = None) -> str:
        clean_name = name.strip().lower().replace(" ", "_")
        locs = _read_locations()
        if clean_name not in locs:
            raise RuntimeError(f"Location '{clean_name}' does not exist")
        del locs[clean_name]
        _write_locations(locs)
        return f"Removed location '{clean_name}' from persistent memory."

    @tool(
        "minecraft.locations.goto",
        description="Navigate bot directly to a named saved waypoint (e.g. 'goto home') using 3D A* Pathfinding",
        arguments={
            "name": {"type": "string", "description": "Waypoint name to travel to"},
            "radius": {"type": "number", "description": "Target radius in blocks (default: 1.2)", "default": 1.2}
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def locations_goto(self, name: str, radius: float = 1.2, reason: Optional[str] = None) -> str:
        clean_name = name.strip().lower().replace(" ", "_")
        locs = _read_locations()
        if clean_name not in locs:
            raise RuntimeError(f"Location '{clean_name}' not found. Use locations.list to see available waypoints.")

        target = locs[clean_name]
        tx, ty, tz = target["x"], target["y"], target["z"]
        nav_msg = await self.bot_navigate(x=tx, y=ty, z=tz, radius=radius)
        return f"Traveling to '{clean_name}' ({tx}, {ty}, {tz}): {nav_msg}"

    # ====================================================================
    # Server Operations Tools
    # ====================================================================

    @tool(
        "minecraft.status",
        description="Get current Minecraft server status (TPS, memory, uptime, online players)",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def status(self, reason: Optional[str] = None) -> Dict[str, Any]:
        if not self._base_url or not self._api_key:
            raise RuntimeError("Not connected to any Minecraft server. Call minecraft.connect first.")

        url = f"{self._base_url}/api/status"
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8)) as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key. Reconnect via minecraft.connect.")
                if resp.status >= 400:
                    raise RuntimeError(f"Server error ({resp.status}): {await resp.text()}")
                res = await resp.json()
                return await self._append_hud_if_enabled(res)

    @tool(
        "minecraft.command",
        description="Execute a console command on the Minecraft server and receive its output",
        arguments={
            "command": {
                "type": "string",
                "description": "The Minecraft command to run (e.g. 'list', 'tps', 'give Steve diamond 1', 'time set day')",
            }
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def command(self, command: str, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key:
            raise RuntimeError("Not connected to any Minecraft server. Call minecraft.connect first.")

        clean_cmd = command.strip()
        if clean_cmd.startswith("/"):
            clean_cmd = clean_cmd[1:]

        url = f"{self._base_url}/api/command"
        payload = {"command": clean_cmd}
        body_data = json.dumps(payload, ensure_ascii=False).encode("utf-8")

        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15)) as session:
            async with session.post(url, headers=self._get_headers(), data=body_data) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key.")
                if resp.status >= 400:
                    raise RuntimeError(f"Command execution error ({resp.status}): {await resp.text()}")
                data = await resp.json()
                if not data.get("ok", True):
                    raise RuntimeError(f"Command failed: {data.get('error', 'unknown error')}")
                out = data.get("output", "Command executed.")
                return await self._append_hud_if_enabled(out)

    @tool(
        "minecraft.chat",
        description="Broadcast a chat message to all players on the Minecraft server",
        arguments={
            "message": {
                "type": "string",
                "description": "Message text to broadcast to players",
            },
            "sender": {
                "type": "string",
                "description": "Optional sender display name (default: 'Dirom')",
            },
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def chat(self, message: str, sender: str = "Dirom", reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key:
            raise RuntimeError("Not connected to any Minecraft server. Call minecraft.connect first.")

        url = f"{self._base_url}/api/chat"
        payload = {"message": message, "sender": sender}
        body_data = json.dumps(payload, ensure_ascii=False).encode("utf-8")

        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8)) as session:
            async with session.post(url, headers=self._get_headers(), data=body_data) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key.")
                if resp.status >= 400:
                    raise RuntimeError(f"Chat broadcast error ({resp.status}): {await resp.text()}")
                data = await resp.json()
                if not data.get("ok", True):
                    raise RuntimeError(f"Chat failed: {data.get('error', 'unknown error')}")
                return await self._append_hud_if_enabled(f"Sent to Minecraft chat: [{sender}] {message}")

    @tool(
        "minecraft.messages_history",
        description="Retrieve recent in-game chat messages, joins, leaves, and death events from the server",
        arguments={
            "limit": {
                "type": "integer",
                "description": "Number of recent events to retrieve (default: 50)",
                "default": 50,
            }
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def messages_history(self, limit: int = 50, reason: Optional[str] = None) -> List[Dict[str, Any]]:
        if not self._base_url or not self._api_key:
            raise RuntimeError("Not connected to any Minecraft server. Call minecraft.connect first.")

        url = f"{self._base_url}/api/chat/history?limit={limit}"
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8)) as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key.")
                if resp.status >= 400:
                    raise RuntimeError(f"Failed to fetch chat history ({resp.status}): {await resp.text()}")
                data = await resp.json()
                return data.get("messages", [])

    @tool(
        "minecraft.notifications_feed",
        description="Read cached real-time incoming Minecraft notifications (chat, player joins/leaves, deaths). Automatically marks items as read.",
        arguments={
            "unread_only": {
                "type": "boolean",
                "description": "Filter to return only unread notifications (default: true)",
                "default": True,
            },
            "limit": {
                "type": "integer",
                "description": "Max notifications to return (default: 20)",
                "default": 20,
            },
        },
        enabled_if=lambda ws: ws.is_connected,
    )
    async def notifications_feed(
        self, unread_only: bool = True, limit: int = 20, reason: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        if not self._base_url or not self._api_key:
            raise RuntimeError("Not connected to any Minecraft server. Call minecraft.connect first.")

        url = f"{self._base_url}/api/notifications/feed?unread_only={str(unread_only).lower()}&limit={limit}"
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8)) as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key.")
                if resp.status >= 400:
                    raise RuntimeError(f"Failed to fetch notifications feed ({resp.status}): {await resp.text()}")
                data = await resp.json()
                return data.get("notifications", [])

    @tool(
        "minecraft.notifications_clear",
        description="Clear cached notifications list on the Minecraft server",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def notifications_clear(self, reason: Optional[str] = None) -> str:
        if not self._base_url or not self._api_key:
            raise RuntimeError("Not connected to any Minecraft server. Call minecraft.connect first.")

        url = f"{self._base_url}/api/notifications/clear"
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8)) as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key.")
                if resp.status >= 400:
                    raise RuntimeError(f"Failed to clear notifications ({resp.status}): {await resp.text()}")
                data = await resp.json()
                return data.get("message", "Minecraft notifications cleared.")

    @tool(
        "minecraft.players",
        description="List online players with their details (dimension, coordinates, ping, health)",
        arguments={},
        enabled_if=lambda ws: ws.is_connected,
    )
    async def players(self, reason: Optional[str] = None) -> Dict[str, Any]:
        if not self._base_url or not self._api_key:
            raise RuntimeError("Not connected to any Minecraft server. Call minecraft.connect first.")

        url = f"{self._base_url}/api/players"
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8)) as session:
            async with session.get(url, headers=self._get_headers()) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key.")
                if resp.status >= 400:
                    raise RuntimeError(f"Failed to fetch players ({resp.status}): {await resp.text()}")
                res = await resp.json()
                return await self._append_hud_if_enabled(res)
