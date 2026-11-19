"""IDs, zone naming, MQTT topics and the building registry.

Rules (see contracts/README.md):
- Zone ID: {building}-{wall}-R{row}C{col}, e.g. B01-W01-R1C3
- R1 is the base row (rows counted from the bottom), C1 is the left column.
"""

from __future__ import annotations

import json
import os
import re
from functools import lru_cache
from pathlib import Path
from uuid import uuid4

BUILDING_ID = "B01"
WALL_ID = "W01"
ROWS = 4
COLS = 8

TOPIC_ROOT = "hadaq/v1"
TOPIC_ALL = f"{TOPIC_ROOT}/#"

_ZONE_RE = re.compile(r"^(B\d{2})-(W\d{2})-R(\d+)C(\d+)$")


def new_id(prefix: str) -> str:
    """Generated IDs: prefix + 12 hex chars, e.g. new_id('evt') -> 'evt_3f9a1c2b7d4e'."""
    return f"{prefix}_{uuid4().hex[:12]}"


def zone_id(building_id: str, wall_id: str, row: int, col: int) -> str:
    return f"{building_id}-{wall_id}-R{row}C{col}"


def parse_zone_id(value: str) -> tuple[str, str, int, int]:
    match = _ZONE_RE.match(value)
    if not match:
        raise ValueError(f"Bad zone id: {value!r} (expected like B01-W01-R1C3)")
    building, wall, row, col = match.groups()
    return building, wall, int(row), int(col)


def all_zone_ids(
    building_id: str = BUILDING_ID, wall_id: str = WALL_ID, rows: int = ROWS, cols: int = COLS
) -> list[str]:
    return [zone_id(building_id, wall_id, r, c) for r in range(1, rows + 1) for c in range(1, cols + 1)]


def topic_reading(building_id: str, sensor_id: str) -> str:
    return f"{TOPIC_ROOT}/{building_id}/sensors/{sensor_id}"


def topic_event(building_id: str, event_type: str) -> str:
    return f"{TOPIC_ROOT}/{building_id}/events/{event_type}"


def topic_device_status(building_id: str, device_id: str) -> str:
    return f"{TOPIC_ROOT}/{building_id}/devices/{device_id}/status"


def _default_registry_path() -> Path:
    env = os.getenv("HADAQ_REGISTRY")
    if env:
        return Path(env)
    # packages/hadaq_schemas/hadaq_schemas/ids.py -> repo root is parents[3]
    return Path(__file__).resolve().parents[3] / "contracts" / "building_b01.json"


@lru_cache(maxsize=4)
def load_registry(path: str | None = None) -> dict:
    """Load contracts/building_b01.json (or the file in HADAQ_REGISTRY)."""
    registry_path = Path(path) if path else _default_registry_path()
    with registry_path.open(encoding="utf-8") as handle:
        return json.load(handle)
