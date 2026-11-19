"""Pydantic models for every message in Hadaq. Mirror: apps/web/src/types/contracts.ts."""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, Field

from hadaq_schemas.ids import new_id


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


class Provenance(str, Enum):
    MEASURED = "measured"
    DERIVED = "derived"
    MODEL_OUTPUT = "model_output"
    ASSUMED = "assumed"
    SYNTHETIC = "synthetic"


SensorKind = Literal["moisture", "temperature", "humidity", "crack_width", "tilt"]
DefectClass = Literal["crack", "erosion", "plaster_loss", "moisture_stain", "missing_material"]
EventType = Literal[
    "storm_started",
    "storm_ended",
    "new_crack",
    "sensor_fault",
    "demo_reset",
    "moisture_anomaly",
    "new_photo",
    "detection_ready",
    "simulation_done",
    "recommendation_ready",
    "agent_note",
]
EventSource = Literal["scenario_panel", "monitoring", "vision", "simulation", "agent", "api", "device"]
Scenario = Literal["baseline", "storm_replay", "rain_x2", "drainage_fixed"]


class SensorReading(BaseModel):
    sensor_id: str
    building_id: str
    zone_id: str
    kind: SensorKind
    value: float
    unit: str
    ts: datetime
    provenance: Provenance
    device_id: str | None = None
    quality: Literal["ok", "suspect", "fault"] = "ok"


class Event(BaseModel):
    event_id: str = Field(default_factory=lambda: new_id("evt"))
    type: EventType
    building_id: str
    ts: datetime = Field(default_factory=now_utc)
    source: EventSource
    payload: dict[str, Any] = Field(default_factory=dict)
    provenance: Provenance


class Photo(BaseModel):
    photo_id: str = Field(default_factory=lambda: new_id("ph"))
    camera_id: str
    building_id: str
    captured_at: datetime = Field(default_factory=now_utc)
    provenance: Provenance
    file_url: str | None = None
    sequence: str | None = None
    frame: int | None = None
    status: Literal["new", "processed"] = "new"


class Detection(BaseModel):
    detection_id: str = Field(default_factory=lambda: new_id("det"))
    photo_id: str
    camera_id: str
    building_id: str
    zone_id: str
    cls: DefectClass
    confidence: float = Field(ge=0, le=1)
    bbox_xyxy: list[float] = Field(min_length=4, max_length=4)
    polygon: list[list[float]] | None = None
    length_px: float | None = None
    area_px: float | None = None
    length_mm: float | None = None
    area_cm2: float | None = None
    model: str
    provenance: Provenance = Provenance.MODEL_OUTPUT
    change: Literal["new", "grown", "unchanged", "repaired"] | None = None
    growth_pct: float | None = None


class DetectionBatch(BaseModel):
    """What the vision worker posts for one photo (an empty list means: looked, found nothing)."""

    photo_id: str
    building_id: str
    camera_id: str
    model: str
    detections: list[Detection] = Field(default_factory=list)


class WeatherSpec(BaseModel):
    mode: Literal["replay", "synthetic_storm"] = "synthetic_storm"
    date: str | None = Field(default=None, description="YYYY-MM-DD, required for replay")
    hours: int = Field(default=72, ge=6, le=24 * 14)
    peak_rain_mm_h: float = 12.0
    wind_ms: float = 8.0
    wind_dir_deg: float = 270.0
    rain_multiplier: float = 1.0


class SimulationRequest(BaseModel):
    building_id: str
    wall_id: str = "W01"
    scenario: Scenario = "storm_replay"
    weather: WeatherSpec = Field(default_factory=WeatherSpec)
    requested_by: str = "user"


class ZoneSummary(BaseModel):
    peak_moisture: float
    hours_above_threshold: int
    erosion_index: float


class SimulationResult(BaseModel):
    run_id: str = Field(default_factory=lambda: new_id("sim"))
    building_id: str
    wall_id: str
    scenario: Scenario
    engine: str
    provenance: Provenance = Provenance.MODEL_OUTPUT
    created_at: datetime = Field(default_factory=now_utc)
    times: list[datetime]
    zones: dict[str, list[float]]
    summary: dict[str, ZoneSummary]
    threshold: float
    assumptions: list[str]
    weather_source: str


class RiskScore(BaseModel):
    risk_id: str = Field(default_factory=lambda: new_id("risk"))
    building_id: str
    zone_id: str
    hazard: Literal["rain"] = "rain"
    A: float
    B: float
    C: float
    magnitude: float
    level: Literal["low", "medium", "high", "very_high"]
    evidence_ids: list[str] = Field(default_factory=list)
    method: str = "ABC method (adapted, see hadaq_sim.risk)"
    provenance: Provenance = Provenance.MODEL_OUTPUT


class Evidence(BaseModel):
    evidence_id: str
    building_id: str
    zone_id: str | None = None
    kind: Literal["sensor_reading", "detection", "simulation", "risk", "event", "photo"]
    provenance: Provenance
    source: str
    observed_at: datetime | None = None
    created_at: datetime = Field(default_factory=now_utc)
    value: dict[str, Any]
    uncertainty: dict[str, Any] | None = None
    expert_reviewed: bool = False


class LiveMessage(BaseModel):
    channel: Literal["reading", "event", "photo", "detection", "simulation", "risk"]
    data: dict[str, Any]


class ChatRequest(BaseModel):
    message: str
    building_id: str = "B01"
    session_id: str = "demo"


class ChatResponse(BaseModel):
    answer: str
    evidence_ids: list[str] = Field(default_factory=list)
    tool_calls: list[dict[str, Any]] = Field(default_factory=list)
    needs_approval: bool = False
    unverified_numbers: list[str] = Field(default_factory=list)
