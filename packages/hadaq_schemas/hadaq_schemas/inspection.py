"""Contracts v2: field inspections (surveys, captures, findings, reviews, work orders).

Mirror: apps/web/src/types/contracts.ts (section "v2"). Spec: contracts/README.md, section "v2".
JSON Schemas for the Java side: contracts/v2/schemas/ (python -m hadaq_schemas.export_schemas).

Flow: the surveyor app POSTs a capture to the core API (Spring), the core API sends it to the
AI worker, the AI worker POSTs CaptureResults back, the conservator reviews each finding and
approves the inspection.

Wall coordinates: u = metres from the LEFT end of the wall (the C1 side, facing the facade),
v = metres up from the base. A zone R{row}C{col} follows from (u, v) and the wall's size.
"""

from __future__ import annotations

import math
import re
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator

from hadaq_schemas.ids import new_id
from hadaq_schemas.models import Provenance, now_utc

# ---------------------------------------------------------------- enums

# DGDA glossary: the six priority damage types. Names must match the glossary verbatim.
CORE_DAMAGE_TYPES = (
    "plaster_loss",  # فقد اللياسة
    "crack",  # التشققات
    "base_erosion",  # تآكل القاعدة
    "rain_grooves",  # أخاديد الأمطار
    "salt_efflorescence",  # تزهر الأملاح
    "voids_burrows",  # الفجوات والجحور
)
# Out of single-photo scope: only ever reported as suspected, referred to a specialist.
SUSPECTED_ONLY_TYPES = ("structural_deformation", "incompatible_repair")
# Bonus types (scored as extra, not required).
BONUS_DAMAGE_TYPES = ("parapet_loss", "timber_decay", "biological_growth")

DAMAGE_NAMES_AR = {
    "plaster_loss": "فقد اللياسة",
    "crack": "التشققات",
    "base_erosion": "تآكل القاعدة",
    "rain_grooves": "أخاديد الأمطار",
    "salt_efflorescence": "تزهر الأملاح",
    "voids_burrows": "الفجوات والجحور",
    "structural_deformation": "تشوهات إنشائية",
    "incompatible_repair": "ترميم غير متوافق",
    "parapet_loss": "فقد الشرفات والزخارف",
    "timber_decay": "تلف العناصر الخشبية",
    "biological_growth": "النمو الحيوي",
}

DamageType = Literal[
    "plaster_loss",
    "crack",
    "base_erosion",
    "rain_grooves",
    "salt_efflorescence",
    "voids_burrows",
    "structural_deformation",
    "incompatible_repair",
    "parapet_loss",
    "timber_decay",
    "biological_growth",
]
Severity = Literal[1, 2, 3]  # 1 خفيف, 2 متوسط, 3 شديد
SEVERITY_NAMES_AR = {1: "خفيف", 2: "متوسط", 3: "شديد"}

Escalation = Literal["urgent", "high", "medium", "low"]  # عاجل، مرتفع، متوسط، منخفض
ESCALATION_NAMES_AR = {"urgent": "عاجل", "high": "مرتفع", "medium": "متوسط", "low": "منخفض"}
ESCALATION_ORDER = {"urgent": 0, "high": 1, "medium": 2, "low": 3}

CaptureStatus = Literal["queued", "uploaded", "processing", "analyzed", "failed"]
ReviewStatus = Literal["pending", "accepted", "corrected", "rejected"]
WorkOrderStatus = Literal["draft_needs_field_check", "approved", "rejected"]
InspectionStatus = Literal["open", "approved"]
TaskStatus = Literal["not_started", "partial", "done", "synced"]
Change = Literal["new", "grown", "unchanged", "repaired"]
Role = Literal["surveyor", "conservator", "site_manager"]
CitationStatus = Literal["approved_library", "team_proposal"]
Scale = Literal["absolute", "relative"]

# Live channels added in v2 (the v1 channels stay as they are).
LiveChannelV2 = Literal["capture", "finding", "review", "work_order", "inspection"]


# ---------------------------------------------------------------- helpers


def zone_from_wall_m(
    u: float, v: float, width_m: float, height_m: float, rows: int = 4, cols: int = 8
) -> tuple[int, int]:
    """(u, v) in wall metres -> (row, col), 1-based. R1 = base row, C1 = left end."""
    if width_m <= 0 or height_m <= 0:
        raise ValueError("wall width and height must be positive")
    col = min(cols, max(1, math.floor(u / width_m * cols) + 1))
    row = min(rows, max(1, math.floor(v / height_m * rows) + 1))
    return row, col


def box_center(box: list[float]) -> tuple[float, float]:
    return (box[0] + box[2]) / 2, (box[1] + box[3]) / 2


# ---------------------------------------------------------------- registry (GET /api/v2/registry)


class LonLat(BaseModel):
    lon: float
    lat: float


class BaseLine(BaseModel):
    """start = LEFT end when you face the facade (the C1 side)."""

    start: LonLat
    end: LonLat


class WallInfo(BaseModel):
    id: str  # W01
    building_id: str
    name: str
    azimuth_deg: float  # direction the facade faces, degrees from north
    width_m: float
    height_m: float
    rows: int = 4
    cols: int = 8
    base_line: BaseLine
    main_facade: bool = False
    visitor_route: bool = False  # on or next to a visitor path (priority + escalation)
    original_fabric: Literal["original", "restored", "mixed", "unknown"] = "unknown"
    provenance: Provenance = Provenance.ASSUMED  # how width, height and azimuth were obtained


class BuildingInfo(BaseModel):
    id: str  # B01
    name_ar: str
    name_en: str
    lon: float
    lat: float
    has_model: bool = False  # a 3D model exists in the web app
    walls: list[WallInfo] = Field(default_factory=list)


class Registry(BaseModel):
    site_id: str = "diriyah"
    buildings: list[BuildingInfo]


# ---------------------------------------------------------------- surveys (GET /api/v2/surveys)


class SurveyTask(BaseModel):
    building_id: str
    wall_id: str
    required_shots: list[str] = Field(default_factory=lambda: ["full"])  # "full" or column ranges like "C1-C2"
    done_shots: list[str] = Field(default_factory=list)
    status: TaskStatus = "not_started"

    @property
    def missing_shots(self) -> list[str]:
        return [s for s in self.required_shots if s not in self.done_shots]


class Survey(BaseModel):
    survey_id: str = Field(default_factory=lambda: new_id("srv"))
    title: str
    assignee: str  # user id, e.g. "surveyor"
    due: datetime | None = None
    tasks: list[SurveyTask] = Field(default_factory=list)


SHOT_PATTERN = r"^(full|C\d+-C\d+)$"  # "full" or a column range like "C1-C2"


class SurveyTaskRequest(BaseModel):
    """One wall to photograph in a new survey. done_shots and status are set by the core API."""

    building_id: str
    wall_id: str
    required_shots: list[str] = Field(default_factory=lambda: ["full"], min_length=1)

    @field_validator("required_shots")
    @classmethod
    def _shots(cls, value: list[str]) -> list[str]:
        for shot in value:
            if not re.match(SHOT_PATTERN, shot):
                raise ValueError(f"required_shots: {shot!r} must be 'full' or a column range like 'C1-C2'")
        return value


class SurveyRequest(BaseModel):
    """POST /api/v2/surveys (site_manager only). The core API answers 201 with the Survey."""

    title: str = Field(min_length=1, max_length=200)
    assignee: str = "surveyor"  # user id of the surveyor who gets it in the app
    due: datetime | None = None
    tasks: list[SurveyTaskRequest] = Field(min_length=1)


# ---------------------------------------------------------------- captures (POST /api/v2/captures)


class GeoFix(BaseModel):
    lat: float
    lon: float
    accuracy_m: float | None = None


class CaptureQuality(BaseModel):
    """Checks made by the app at the shutter. Warnings never block a shot; they are recorded."""

    tilt_ok: bool | None = None  # within 5 degrees of vertical
    exposure: Literal["ok", "dark", "bright"] | None = None
    blur_score: float | None = None  # higher = sharper (variance of the Laplacian)
    steady: bool | None = None
    scale_ref: bool = False  # a scale bar on a stand is in frame (nothing touches the wall)
    warnings: list[str] = Field(default_factory=list)


class CaptureMeta(BaseModel):
    """The `meta` JSON part of the multipart upload, written by the surveyor app."""

    client_uuid: str = Field(min_length=8)  # made on the phone; the same value on every retry (dedupe)
    survey_id: str | None = None
    building_id: str
    wall_id: str
    segment: str = "full"  # "full" or a column range like "C1-C2"
    captured_at: datetime
    surveyor: str  # user id
    device: str | None = None  # e.g. "iPhone 15 Pro, iOS 18.2"
    source: Literal["camera", "import"] = "camera"  # import = picked from the photo library
    gps: GeoFix | None = None
    heading_deg: float | None = None
    tilt_deg: float | None = None
    quality: CaptureQuality = Field(default_factory=CaptureQuality)
    # The wall's 4 corners in image pixels, in this order: top-left, top-right, bottom-right,
    # bottom-left of the wall face as seen in the photo (left = the C1 end). None = whole photo.
    corners_px: list[list[float]] | None = None
    image_width_px: int | None = None
    image_height_px: int | None = None
    faces_hidden: int | None = None  # faces the phone hid; None = the phone did not check
    provenance: Provenance = Provenance.MEASURED

    @field_validator("corners_px")
    @classmethod
    def _four_points(cls, value: list[list[float]] | None) -> list[list[float]] | None:
        if value is not None and (len(value) != 4 or any(len(p) != 2 for p in value)):
            raise ValueError("corners_px must be 4 points [x, y]: top-left, top-right, bottom-right, bottom-left")
        return value


class Capture(CaptureMeta):
    """What the core API stores and returns (GET /api/v2/captures/{id})."""

    capture_id: str = Field(default_factory=lambda: new_id("cap"))
    status: CaptureStatus = "uploaded"
    inspection_id: str | None = None
    file_url: str | None = None
    rectified_url: str | None = None
    px_per_m: float | None = None
    scale: Scale | None = None
    faces_hidden_server: int | None = None  # faces the AI worker hid
    findings_count: int = 0
    urgent: bool = False
    error: str | None = None
    created_at: datetime = Field(default_factory=now_utc)


class CaptureAccepted(BaseModel):
    """202 response of POST /api/v2/captures (also returned for a repeated client_uuid)."""

    capture_id: str
    status: CaptureStatus
    duplicate: bool = False


# ---------------------------------------------------------------- findings


class Lookalike(BaseModel):
    """The photo cannot tell two damages apart; the agent asks for evidence instead of guessing."""

    pair_id: str  # id in services/agents/hadaq_agents/lookalikes.json
    options: list[str]  # e.g. ["rising damp (active)", "old dry stain"]
    evidence_needed: list[str]  # e.g. ["moisture at 3 heights", "date of last rain"]
    note_ar: str | None = None


class Review(BaseModel):
    status: ReviewStatus = "pending"
    by: str | None = None
    at: datetime | None = None
    corrected_type: DamageType | None = None
    corrected_severity: Severity | None = None
    note: str | None = None


class PriorityCriteria(BaseModel):
    """DGDA's criteria in their order, each 0..1."""

    safety: float = Field(0, ge=0, le=1)
    active: float = Field(0, ge=0, le=1)
    water_before_rain: float = Field(0, ge=0, le=1)
    severity_x_area: float = Field(0, ge=0, le=1)
    value: float = Field(0, ge=0, le=1)
    location: float = Field(0, ge=0, le=1)


class Priority(BaseModel):
    score: float = Field(ge=0, le=100)
    criteria: PriorityCriteria
    escalation: Escalation
    reason_ar: str
    reason_en: str | None = None
    method: str = "DGDA criteria, weights ASSUMED (hadaq_agents.priority)"


class Finding(BaseModel):
    finding_id: str = Field(default_factory=lambda: new_id("fnd"))
    capture_id: str
    inspection_id: str | None = None
    building_id: str
    wall_id: str
    zone_id: str  # B01-W01-R1C4
    damage_type: DamageType
    severity: Severity
    confidence: float = Field(ge=0, le=1)
    suspected: bool = False  # True for out-of-scope types and anything the agent cannot confirm
    box_px: list[float] | None = Field(default=None, min_length=4, max_length=4)  # in the rectified image
    box_wall_m: list[float] = Field(min_length=4, max_length=4)  # [u0, v0, u1, v1]
    polygon_wall_m: list[list[float]] | None = None
    length_cm: float | None = None
    width_mm: float | None = None
    area_m2: float | None = None
    change: Change | None = None
    previous_finding_id: str | None = None
    lookalike: Lookalike | None = None
    priority: Priority | None = None
    review: Review = Field(default_factory=Review)
    model: str  # detector@version, e.g. "rfdetr-seg-small@turaif_v1"
    provenance: Provenance = Provenance.MODEL_OUTPUT
    evidence_ids: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def _suspected_only(self) -> "Finding":
        if self.damage_type in SUSPECTED_ONLY_TYPES and not self.suspected:
            raise ValueError(f"{self.damage_type} can only be reported as suspected")
        return self

    @property
    def effective_type(self) -> str:
        return self.review.corrected_type or self.damage_type

    @property
    def effective_severity(self) -> int:
        return self.review.corrected_severity or self.severity


# ---------------------------------------------------------------- work orders


class Citation(BaseModel):
    source: str  # e.g. "Crosby, Architectural Conservation, restoration specifications (nomination dossier 2009)"
    section: str | None = None  # never invented: left empty when unknown
    status: CitationStatus


class WorkOrderLocation(BaseModel):
    building_id: str
    wall_id: str
    zone_ids: list[str]
    box_wall_m: list[float] | None = Field(default=None, min_length=4, max_length=4)


class WorkOrderDraft(BaseModel):
    work_order_id: str = Field(default_factory=lambda: new_id("wo"))
    inspection_id: str | None = None
    finding_ids: list[str] = Field(min_length=1)
    location: WorkOrderLocation
    treatment_id: str  # id in services/agents/hadaq_agents/treatment_library.json
    steps_ar: list[str]
    steps_en: list[str] = Field(default_factory=list)
    materials_ar: list[str] = Field(default_factory=list)
    materials_en: list[str] = Field(default_factory=list)
    tools_ar: list[str] = Field(default_factory=list)
    tools_en: list[str] = Field(default_factory=list)
    fix_water_source_first: bool = False
    citation: Citation
    escalation: Escalation
    status: WorkOrderStatus = "draft_needs_field_check"
    created_at: datetime = Field(default_factory=now_utc)


# ---------------------------------------------------------------- AI worker <-> core API


class ProcessRequest(BaseModel):
    """Core API -> AI worker: POST {AI_URL}/ai/process. The worker answers 202 and calls back."""

    capture_id: str
    file_url: str
    callback_url: str  # e.g. http://core:8080/api/v2/captures/cap_.../results
    wall: WallInfo
    corners_px: list[list[float]] | None = None
    previous_findings: list[Finding] = Field(default_factory=list)


class CaptureResults(BaseModel):
    """AI worker -> core API: POST /api/v2/captures/{id}/results.

    Sent as multipart: `results` (this JSON) + `rectified` (the rectified JPEG, optional).
    """

    capture_id: str
    ok: bool = True
    error: str | None = None
    scale: Scale = "relative"
    px_per_m: float | None = None
    faces_hidden_server: int = 0
    findings: list[Finding] = Field(default_factory=list)
    work_orders: list[WorkOrderDraft] = Field(default_factory=list)
    summary_ar: str | None = None
    summary_en: str | None = None
    detector: str
    chain: str = "hadaq_agents.chain@v1"


# ---------------------------------------------------------------- inspections and review


class Inspection(BaseModel):
    """One wall in one survey (GET /api/v2/inspections/{id})."""

    inspection_id: str = Field(default_factory=lambda: new_id("ins"))
    survey_id: str | None = None
    building_id: str
    wall_id: str
    started_at: datetime = Field(default_factory=now_utc)
    captures: list[Capture] = Field(default_factory=list)
    findings: list[Finding] = Field(default_factory=list)
    work_orders: list[WorkOrderDraft] = Field(default_factory=list)
    status: InspectionStatus = "open"
    approved_by: str | None = None
    approved_at: datetime | None = None

    @property
    def can_approve(self) -> bool:
        return self.status == "open" and all(f.review.status != "pending" for f in self.findings)


class ReviewRequest(BaseModel):
    """PATCH /api/v2/findings/{id}/review (conservator only)."""

    status: Literal["accepted", "corrected", "rejected"]
    corrected_type: DamageType | None = None
    corrected_severity: Severity | None = None
    note: str | None = None

    @model_validator(mode="after")
    def _correction_needs_a_value(self) -> "ReviewRequest":
        if self.status == "corrected" and self.corrected_type is None and self.corrected_severity is None:
            raise ValueError("a correction needs corrected_type or corrected_severity")
        return self


class SitePriorityRow(BaseModel):
    """GET /api/v2/site/priority: one row per building, worst first."""

    building_id: str
    name_ar: str
    name_en: str
    worst_escalation: Escalation | None = None  # None = nothing open
    open_findings: dict[str, int] = Field(default_factory=dict)  # escalation -> count
    top_reason_ar: str | None = None
    last_survey_at: datetime | None = None  # None = never surveyed


class LiveMessageV2(BaseModel):
    channel: LiveChannelV2
    data: dict[str, Any]


V2_ID_PREFIXES = {"srv": "survey", "cap": "capture", "fnd": "finding", "wo": "work_order", "ins": "inspection"}
