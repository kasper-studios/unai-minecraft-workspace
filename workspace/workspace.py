"""UnAI Minecraft Workspace implementation.

Subclasses Workspace from UnAI SDK. Interacts natively with Minecraft servers
running the UnAI Bridge (Paper/Spigot plugin or Forge mod) via standard HTTP REST API.

Follows ADR-0004 for one-shot connect tool state management.
Provides tools for:
- Connection and auth session
- Console commands execution
- In-game chat broadcast
- In-game chat and event history inspection
- Real-time notifications feed (chat, joins, deaths)
- Server health and players inspection
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


class MinecraftWorkspace(Workspace):
    """Native Minecraft Server Workspace for autonomous AI agents."""

    def __init__(self, runtime_id: str = "minecraft", bus: Optional[Any] = None, **kwargs: Any):
        super().__init__(runtime_id=runtime_id, bus=bus, **kwargs)
        self._host: Optional[str] = None
        self._port: int = DEFAULT_PORT
        self._api_key: Optional[str] = None
        self._base_url: Optional[str] = None
        self._server_info: Optional[Dict[str, Any]] = None
        self._load_session()

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

    @property
    def is_connected(self) -> bool:
        return bool(self._base_url and self._api_key)

    def _get_headers(self) -> Dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
            headers["X-API-Key"] = self._api_key
        return headers

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

        # If connecting to plain port 80 or default HTTP, format accordingly
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
                return await resp.json()

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

        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15)) as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key.")
                if resp.status >= 400:
                    raise RuntimeError(f"Command execution error ({resp.status}): {await resp.text()}")
                data = await resp.json()
                if not data.get("ok", True):
                    raise RuntimeError(f"Command failed: {data.get('error', 'unknown error')}")
                return data.get("output", "Command executed.")

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

        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8)) as session:
            async with session.post(url, headers=self._get_headers(), json=payload) as resp:
                if resp.status == 401 or resp.status == 403:
                    raise RuntimeError("Session expired or invalid API key.")
                if resp.status >= 400:
                    raise RuntimeError(f"Chat broadcast error ({resp.status}): {await resp.text()}")
                data = await resp.json()
                if not data.get("ok", True):
                    raise RuntimeError(f"Chat failed: {data.get('error', 'unknown error')}")
                return f"Sent to Minecraft chat: [{sender}] {message}"

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
                return await resp.json()
