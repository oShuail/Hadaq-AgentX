"""Websocket fan-out. Any code (including the MQTT thread) can push a LiveMessage to every browser."""

from __future__ import annotations

import asyncio
import logging

from fastapi import WebSocket

log = logging.getLogger("hadaq.live")


class LiveHub:
    def __init__(self) -> None:
        self.clients: set[WebSocket] = set()
        self.loop: asyncio.AbstractEventLoop | None = None

    def attach_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        self.loop = loop

    async def connect(self, ws: WebSocket) -> None:
        await ws.accept()
        self.clients.add(ws)

    def disconnect(self, ws: WebSocket) -> None:
        self.clients.discard(ws)

    async def broadcast(self, channel: str, data: dict) -> None:
        message = {"channel": channel, "data": data}
        dead = []
        for ws in list(self.clients):
            try:
                await ws.send_json(message)
            except Exception:  # noqa: BLE001 - a closed socket should not break the others
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)

    def publish_threadsafe(self, channel: str, data: dict) -> None:
        """Call from non-async code (MQTT thread, simulation thread)."""
        if self.loop is None or self.loop.is_closed():
            return
        asyncio.run_coroutine_threadsafe(self.broadcast(channel, data), self.loop)


hub = LiveHub()
