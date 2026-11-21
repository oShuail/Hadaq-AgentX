"""Time the B01 demo events while the device simulator supplies the actual readings."""

from __future__ import annotations

import asyncio
import time
from collections.abc import Callable


class SensorRun:
    def __init__(self, duration: float = 120, schedule: tuple[tuple[float, str], ...] | None = None) -> None:
        self.duration = duration
        self.schedule = schedule if schedule is not None else (
            (35, "storm_ended"), (60, "sensor_fault"), (75, "new_crack"),
        )
        self.started_at: float | None = None
        self.task: asyncio.Task[None] | None = None

    def status(self, available: bool) -> dict:
        elapsed = min(self.duration, max(0, time.monotonic() - self.started_at)) if self.started_at else 0
        return {"available": available, "running": self.task is not None and not self.task.done(),
                "elapsed": round(elapsed, 1), "duration": self.duration}

    async def start(self, emit: Callable[[str], None]) -> None:
        await self.stop(emit, reset=False)
        emit("demo_reset")
        emit("storm_started")
        self.started_at = time.monotonic()
        self.task = asyncio.create_task(self._play(emit))

    async def stop(self, emit: Callable[[str], None], reset: bool = True) -> None:
        if self.task and not self.task.done():
            self.task.cancel()
            try:
                await self.task
            except asyncio.CancelledError:
                pass
        self.task = None
        self.started_at = None
        if reset:
            emit("demo_reset")

    async def _play(self, emit: Callable[[str], None]) -> None:
        assert self.started_at is not None
        for seconds, event_type in self.schedule:
            await asyncio.sleep(max(0, self.started_at + seconds - time.monotonic()))
            emit(event_type)
        await asyncio.sleep(max(0, self.started_at + self.duration - time.monotonic()))
