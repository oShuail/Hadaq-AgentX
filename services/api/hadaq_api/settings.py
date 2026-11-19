"""All configuration comes from environment variables (see .env.example at the repo root)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    return default if value is None else value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    database_url: str = os.getenv("DATABASE_URL", "sqlite:///./data/hadaq.db")
    mqtt_enabled: bool = _bool("MQTT_ENABLED", True)
    mqtt_host: str = os.getenv("MQTT_HOST", "localhost")
    mqtt_port: int = int(os.getenv("MQTT_PORT", "1883"))
    photo_dir: Path = Path(os.getenv("PHOTO_DIR", "./data/photos"))
    agents_url: str = os.getenv("AGENTS_URL", "http://localhost:8100")
    agents_enabled: bool = _bool("AGENTS_ENABLED", True)
    public_url: str = os.getenv("API_PUBLIC_URL", "http://localhost:8000")
    anomaly_cooldown_s: int = int(os.getenv("ANOMALY_COOLDOWN_S", "600"))


settings = Settings()
