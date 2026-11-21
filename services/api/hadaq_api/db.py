"""Storage. SQLite by default (zero setup); set DATABASE_URL to Postgres/TimescaleDB later.

Each table keeps a few indexed columns for queries and the full message as JSON in `data`.
"""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import JSON, Column
from sqlmodel import Field, Session, SQLModel, create_engine, select

from hadaq_api.settings import settings
from hadaq_schemas import Detection, Event, Photo, SensorReading, SimulationResult


class ReadingRow(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    sensor_id: str = Field(index=True)
    building_id: str = Field(index=True)
    ts: datetime = Field(index=True)
    data: dict = Field(sa_column=Column(JSON))


class EventRow(SQLModel, table=True):
    event_id: str = Field(primary_key=True)
    building_id: str = Field(index=True)
    type: str = Field(index=True)
    ts: datetime = Field(index=True)
    data: dict = Field(sa_column=Column(JSON))


class PhotoRow(SQLModel, table=True):
    photo_id: str = Field(primary_key=True)
    building_id: str = Field(index=True)
    camera_id: str = Field(index=True)
    captured_at: datetime = Field(index=True)
    status: str = Field(index=True, default="new")
    file_path: str
    data: dict = Field(sa_column=Column(JSON))


class DetectionRow(SQLModel, table=True):
    detection_id: str = Field(primary_key=True)
    photo_id: str = Field(index=True)
    building_id: str = Field(index=True)
    zone_id: str = Field(index=True)
    created_at: datetime = Field(index=True)
    data: dict = Field(sa_column=Column(JSON))


class SimulationRow(SQLModel, table=True):
    run_id: str = Field(primary_key=True)
    building_id: str = Field(index=True)
    status: str = Field(index=True)
    created_at: datetime = Field(index=True)
    request: dict = Field(sa_column=Column(JSON))
    result: dict | None = Field(default=None, sa_column=Column(JSON, nullable=True))
    error: str | None = None


class EvidenceRow(SQLModel, table=True):
    evidence_id: str = Field(primary_key=True)
    building_id: str = Field(index=True)
    kind: str = Field(index=True)
    created_at: datetime = Field(index=True)
    data: dict = Field(sa_column=Column(JSON))


def _make_engine():
    url = settings.database_url
    if url.startswith("sqlite:///"):
        Path(url.removeprefix("sqlite:///")).parent.mkdir(parents=True, exist_ok=True)
        return create_engine(url, connect_args={"check_same_thread": False})
    return create_engine(url, pool_pre_ping=True)


engine = _make_engine()


def init_db() -> None:
    SQLModel.metadata.create_all(engine)


def _json(model) -> dict:
    return model.model_dump(mode="json")


# ---------- writes ----------

def save_reading(reading: SensorReading) -> int:
    with Session(engine) as s:
        row = ReadingRow(sensor_id=reading.sensor_id, building_id=reading.building_id, ts=reading.ts, data=_json(reading))
        s.add(row)
        s.commit()
        s.refresh(row)
        return row.id


def save_event(event: Event) -> bool:
    """Idempotent: returns False if this event_id was already stored (MQTT echo)."""
    with Session(engine) as s:
        if s.get(EventRow, event.event_id):
            return False
        s.add(EventRow(event_id=event.event_id, building_id=event.building_id, type=event.type, ts=event.ts, data=_json(event)))
        s.commit()
        return True


def save_photo(photo: Photo, file_path: str) -> None:
    with Session(engine) as s:
        s.add(PhotoRow(photo_id=photo.photo_id, building_id=photo.building_id, camera_id=photo.camera_id,
                       captured_at=photo.captured_at, status=photo.status, file_path=file_path, data=_json(photo)))
        s.commit()


def save_detections(photo_id: str, detections: list[Detection]) -> None:
    with Session(engine) as s:
        for det in detections:
            s.merge(DetectionRow(detection_id=det.detection_id, photo_id=det.photo_id, building_id=det.building_id,
                                 zone_id=det.zone_id, created_at=datetime.now(timezone.utc), data=_json(det)))
        row = s.get(PhotoRow, photo_id)
        if row:
            row.status = "processed"
            row.data = {**row.data, "status": "processed"}
            s.add(row)
        s.commit()


def previous_detections(building_id: str, camera_id: str, before_photo_id: str) -> list[dict]:
    """Detections of the last processed photo from the same camera (for change detection in vision)."""
    with Session(engine) as s:
        rows = s.exec(select(DetectionRow).where(DetectionRow.building_id == building_id, DetectionRow.photo_id != before_photo_id)
                      .order_by(DetectionRow.created_at.desc()).limit(200)).all()
        rows = [r for r in rows if r.data.get("camera_id") == camera_id]
        if not rows:
            return []
        last_photo = rows[0].photo_id
        return [r.data for r in rows if r.photo_id == last_photo]


def create_simulation(run_id: str, building_id: str, request: dict) -> None:
    with Session(engine) as s:
        s.add(SimulationRow(run_id=run_id, building_id=building_id, status="running", created_at=datetime.now(timezone.utc), request=request))
        s.commit()


def finish_simulation(run_id: str, result: SimulationResult | None, error: str | None = None) -> None:
    with Session(engine) as s:
        row = s.get(SimulationRow, run_id)
        if not row:
            return
        row.status = "done" if result else "failed"
        row.result = _json(result) if result else None
        row.error = error
        s.add(row)
        s.commit()


def save_evidence(evidence_id: str, building_id: str, kind: str, data: dict) -> None:
    with Session(engine) as s:
        s.merge(EvidenceRow(evidence_id=evidence_id, building_id=building_id, kind=kind, created_at=datetime.now(timezone.utc), data=data))
        s.commit()


# ---------- reads ----------

def latest_readings(building_id: str, sensor_ids: list[str]) -> list[dict]:
    out = []
    with Session(engine) as s:
        for sensor_id in sensor_ids:
            row = s.exec(select(ReadingRow).where(ReadingRow.sensor_id == sensor_id).order_by(ReadingRow.ts.desc(), ReadingRow.id.desc()).limit(1)).first()
            if row:
                out.append({**row.data, "evidence_id": f"rd_{row.id}"})
    return out


def reading_history(building_id: str, sensor_id: str | None, limit: int) -> list[dict]:
    with Session(engine) as s:
        q = select(ReadingRow).where(ReadingRow.building_id == building_id)
        if sensor_id:
            q = q.where(ReadingRow.sensor_id == sensor_id)
        rows = s.exec(q.order_by(ReadingRow.ts.desc(), ReadingRow.id.desc()).limit(limit)).all()
        return [{**r.data, "evidence_id": f"rd_{r.id}"} for r in rows]


def recent_events(building_id: str, limit: int) -> list[dict]:
    with Session(engine) as s:
        rows = s.exec(select(EventRow).where(EventRow.building_id == building_id).order_by(EventRow.ts.desc()).limit(limit)).all()
        return [r.data for r in rows]


def list_photos(building_id: str, status: str | None, limit: int) -> list[dict]:
    with Session(engine) as s:
        q = select(PhotoRow).where(PhotoRow.building_id == building_id)
        if status:  # work queue: oldest first
            q = q.where(PhotoRow.status == status).order_by(PhotoRow.captured_at.asc())
        else:  # browsing: newest first
            q = q.order_by(PhotoRow.captured_at.desc())
        rows = s.exec(q.limit(limit)).all()
        return [r.data for r in rows]


def get_photo_row(photo_id: str) -> PhotoRow | None:
    with Session(engine) as s:
        return s.get(PhotoRow, photo_id)


def latest_detections(building_id: str) -> list[dict]:
    """All detections of the most recent processed photo."""
    with Session(engine) as s:
        last = s.exec(select(DetectionRow).where(DetectionRow.building_id == building_id).order_by(DetectionRow.created_at.desc()).limit(1)).first()
        if not last:
            return []
        rows = s.exec(select(DetectionRow).where(DetectionRow.photo_id == last.photo_id)).all()
        return [r.data for r in rows]


def get_simulation(run_id: str) -> SimulationRow | None:
    with Session(engine) as s:
        return s.get(SimulationRow, run_id)


def latest_simulation(building_id: str) -> SimulationRow | None:
    with Session(engine) as s:
        return s.exec(select(SimulationRow).where(SimulationRow.building_id == building_id, SimulationRow.status == "done")
                      .order_by(SimulationRow.created_at.desc()).limit(1)).first()


def get_evidence(evidence_id: str) -> dict | None:
    with Session(engine) as s:
        if evidence_id.startswith("rd_"):
            row = s.get(ReadingRow, int(evidence_id.removeprefix("rd_")))
            return {"evidence_id": evidence_id, "kind": "sensor_reading", "value": row.data} if row else None
        if evidence_id.startswith("evt_"):
            row = s.get(EventRow, evidence_id)
            return {"evidence_id": evidence_id, "kind": "event", "value": row.data} if row else None
        if evidence_id.startswith("ph_"):
            row = s.get(PhotoRow, evidence_id)
            return {"evidence_id": evidence_id, "kind": "photo", "value": row.data} if row else None
        if evidence_id.startswith("sim_"):
            row = s.get(SimulationRow, evidence_id)
            if not row:
                return None
            result = row.result or {}
            return {"evidence_id": evidence_id, "kind": "simulation", "status": row.status, "request": row.request,
                    "value": {k: result.get(k) for k in ("scenario", "engine", "summary", "threshold", "assumptions", "weather_source")}}
        row = s.get(EvidenceRow, evidence_id) or s.get(DetectionRow, evidence_id)
        if row is None:
            return None
        return {"evidence_id": evidence_id, "kind": getattr(row, "kind", "detection"), "value": row.data}
