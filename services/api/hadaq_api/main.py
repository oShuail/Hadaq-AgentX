"""Hadaq API gateway (owner: Turky).

Run:  uvicorn hadaq_api.main:app --reload --port 8000
Docs: http://localhost:8000/docs

The API is the hub: it is the only service that writes to the database. Devices and simulators talk to it
over MQTT; the web app, vision worker and agents talk to it over HTTP; browsers get live updates on /ws/live.
"""

from __future__ import annotations

import asyncio
import logging
import threading
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

import httpx
from fastapi import FastAPI, File, Form, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from hadaq_api import db
from hadaq_api import sensors as sensor_health
from hadaq_api.live import hub
from hadaq_api.mqtt_bridge import MqttBridge
from hadaq_api.settings import settings
from hadaq_schemas import (
    Detection,
    DetectionBatch,
    Event,
    Photo,
    Provenance,
    SensorReading,
    SimulationRequest,
    SimulationResult,
    load_registry,
    new_id,
    parse_zone_id,
)
from hadaq_sim import WallParams, detect_moisture_anomaly, run_simulation, score_building, weather_for_spec

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("hadaq.api")

AGENT_TRIGGERS = {"storm_started", "moisture_anomaly", "detection_ready"}
_last_anomaly: dict[str, float] = {}
_risk_cache: dict[str, list[dict]] = {}
_device_status: dict[str, dict] = {}  # device_id -> {"status": "online" | "offline", "ts": ...} from MQTT (retained)


# ---------- core handlers (used by HTTP routes and the MQTT thread) ----------

def _registry_for(building_id: str) -> dict:
    registry = load_registry()
    if registry["building"]["id"] != building_id:
        raise HTTPException(status_code=404, detail=f"Unknown building {building_id}")
    return registry


def _notify_agents(event: Event) -> None:
    if not settings.agents_enabled:
        return

    def _post() -> None:
        try:
            httpx.post(f"{settings.agents_url}/agents/trigger", json=event.model_dump(mode="json"), timeout=3)
        except httpx.HTTPError as exc:
            log.info("Agents not reachable (%s); event %s not forwarded", exc, event.type)

    threading.Thread(target=_post, daemon=True).start()


def handle_event(event: Event, publish_mqtt: bool) -> None:
    if not db.save_event(event):
        return  # already stored (our own MQTT echo)
    hub.publish_threadsafe("event", event.model_dump(mode="json"))
    if publish_mqtt:
        bridge.publish_event(event)
    worth_investigating = event.type != "detection_ready" or bool(event.payload.get("changed"))
    if event.type in AGENT_TRIGGERS and event.source != "agent" and worth_investigating:
        _notify_agents(event)


def _sensor_spec(reading: SensorReading) -> dict:
    registry = load_registry()
    spec = next((s for s in registry["sensors"] if s["id"] == reading.sensor_id), None)
    return spec or {"id": reading.sensor_id, "kind": reading.kind, "unit": reading.unit, "zone_id": reading.zone_id}


def _peers(building_id: str, spec: dict, histories: dict[str, list[dict]] | None = None) -> list[list[dict]]:
    """Recent histories of the other sensors of the same kind (to tell a stuck sensor from a calm wall)."""
    others = [s["id"] for s in load_registry()["sensors"] if s["kind"] == spec["kind"] and s["id"] != spec["id"]]
    if histories is not None:
        return [histories[o] for o in others if histories.get(o)]
    return [h for h in (db.reading_history(building_id, o, sensor_health.FLATLINE_N * 2) for o in others) if h]


def handle_reading(reading: SensorReading) -> None:
    row_id = db.save_reading(reading)
    evidence_id = f"rd_{row_id}"
    history = db.reading_history(reading.building_id, reading.sensor_id, 120)
    spec = _sensor_spec(reading)
    status, reasons = sensor_health.health(spec, history, peers=_peers(reading.building_id, spec) if sensor_health.is_flat(history) else None)
    hub.publish_threadsafe("reading", {**reading.model_dump(mode="json"), "evidence_id": evidence_id, "status": status, "reasons": reasons})
    if reading.kind != "moisture" or status not in ("ok", "stale"):
        return  # a faulty, implausible or stuck sensor must not raise a moisture alarm (late uploads may)
    anomaly = detect_moisture_anomaly([(datetime.fromisoformat(r["ts"]), r["value"]) for r in history if sensor_health.usable(r)])
    now = time.time()
    if anomaly and now - _last_anomaly.get(reading.sensor_id, 0.0) > settings.anomaly_cooldown_s:
        _last_anomaly[reading.sensor_id] = now
        provenance = Provenance.DERIVED if reading.provenance == Provenance.MEASURED else Provenance.SYNTHETIC
        handle_event(
            Event(
                type="moisture_anomaly",
                building_id=reading.building_id,
                source="monitoring",
                provenance=provenance,
                payload={**anomaly, "sensor_id": reading.sensor_id, "zone_id": reading.zone_id, "reading_evidence_id": evidence_id},
            ),
            publish_mqtt=True,
        )


def compute_risk(building_id: str) -> list[dict]:
    row = db.latest_simulation(building_id)
    if not row or not row.result:
        return []
    registry = load_registry()
    sim = SimulationResult.model_validate(row.result)
    detections = [Detection.model_validate(d) for d in db.latest_detections(building_id)]
    scores = score_building(
        sim,
        detections,
        registry["site"]["heavy_rain_days_per_year"],
        registry["zones"].get("value_weight_overrides") or {},
        registry["zones"].get("value_weight_default", 3),
    )
    out = []
    for score in scores:
        data = score.model_dump(mode="json")
        db.save_evidence(score.risk_id, building_id, "risk", data)
        out.append(data)
    _risk_cache[building_id] = out
    return out


def _run_simulation_job(run_id: str, request: SimulationRequest) -> None:
    try:
        registry = load_registry()
        wall = next(w for w in registry["walls"] if w["id"] == request.wall_id)
        lat, lon = registry["site"]["center"]["lat"], registry["site"]["center"]["lon"]
        if request.weather.mode == "replay" and not request.weather.date:
            request.weather.date = registry["site"].get("demo_storm_date")  # the storm Basma picked
            if not request.weather.date:
                request.weather.mode = "synthetic_storm"
        weather, source = weather_for_spec(request.weather, lat, lon)
        params = WallParams(azimuth_deg=wall["azimuth_deg"], rows=wall["rows"], cols=wall["cols"])
        result = run_simulation(request, weather, params, source)
        result.run_id = run_id
        db.finish_simulation(run_id, result)
        hub.publish_threadsafe("simulation", {"run_id": run_id, "status": "done", "result": result.model_dump(mode="json")})
        hub.publish_threadsafe("risk", {"building_id": request.building_id, "scores": compute_risk(request.building_id)})
        handle_event(
            Event(type="simulation_done", building_id=request.building_id, source="simulation", provenance=Provenance.MODEL_OUTPUT,
                  payload={"run_id": run_id, "scenario": request.scenario, "requested_by": request.requested_by}),
            publish_mqtt=True,
        )
    except Exception as exc:  # noqa: BLE001 - report any failure to the caller instead of hanging
        log.exception("Simulation %s failed", run_id)
        db.finish_simulation(run_id, None, str(exc))
        hub.publish_threadsafe("simulation", {"run_id": run_id, "status": "failed", "error": str(exc)})


def handle_device_status(device_id: str, status: str) -> None:
    _device_status[device_id] = {"status": status, "ts": datetime.now(timezone.utc).isoformat()}


bridge = MqttBridge(on_reading=handle_reading, on_event=lambda e: handle_event(e, publish_mqtt=False), on_device=handle_device_status)


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    settings.photo_dir.mkdir(parents=True, exist_ok=True)
    hub.attach_loop(asyncio.get_running_loop())
    if settings.mqtt_enabled:
        bridge.start()
    yield
    if settings.mqtt_enabled:
        bridge.stop()


app = FastAPI(title="Hadaq API", version="0.1.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ---------- routes ----------

@app.get("/health")
def health() -> dict:
    return {"status": "ok", "mqtt": bridge.connected}


@app.get("/buildings/{building_id}")
def get_building(building_id: str) -> dict:
    return _registry_for(building_id)


@app.get("/buildings/{building_id}/readings/latest")
def readings_latest(building_id: str) -> list[dict]:
    registry = _registry_for(building_id)
    return db.latest_readings(building_id, [s["id"] for s in registry["sensors"]])


@app.get("/buildings/{building_id}/readings")
def readings(building_id: str, sensor_id: str | None = None, limit: int = 200) -> list[dict]:
    _registry_for(building_id)
    return db.reading_history(building_id, sensor_id, min(limit, 2000))


@app.get("/buildings/{building_id}/sensors")
def sensors_overview(building_id: str, window: int = 60) -> list[dict]:
    """Every registered sensor: status (ok, suspect, fault, stale, no_data) with reasons, the latest reading and the
    trend over the last `window` readings. This is what the agents read before they trust a number."""
    registry = _registry_for(building_id)
    window = min(max(window, 2), 2000)
    histories = {spec["id"]: db.reading_history(building_id, spec["id"], max(window, sensor_health.FLATLINE_N * 2)) for spec in registry["sensors"]}
    return [sensor_health.overview(spec, histories[spec["id"]][:window], peers=_peers(building_id, spec, histories))
            for spec in registry["sensors"]]


@app.get("/buildings/{building_id}/sensors/{sensor_id}")
def sensor_detail(building_id: str, sensor_id: str, window: int = 120, points: int = 40) -> dict:
    """One sensor: the overview plus its history over the window, downsampled to `points` (oldest first)."""
    registry = _registry_for(building_id)
    spec = next((s for s in registry["sensors"] if s["id"] == sensor_id), None)
    if not spec:
        raise HTTPException(status_code=404, detail=f"Unknown sensor {sensor_id}")
    history = db.reading_history(building_id, sensor_id, min(max(window, 2), 2000))
    overview = sensor_health.overview(spec, history, peers=_peers(building_id, spec))
    return {**overview, "history": sensor_health.downsample(history, min(max(points, 2), 500))}


@app.get("/buildings/{building_id}/zones/{zone_id}")
def zone_detail(building_id: str, zone_id: str, window: int = 60) -> dict:
    """Everything known about one wall zone: its sensors, the latest camera detections there, its risk score and
    its line in the latest simulation."""
    registry = _registry_for(building_id)
    try:
        _, wall_id, row, col = parse_zone_id(zone_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    wall = next((w for w in registry["walls"] if w["id"] == wall_id), None)
    if not wall or not (1 <= row <= wall["rows"] and 1 <= col <= wall["cols"]):
        raise HTTPException(status_code=404, detail=f"Unknown zone {zone_id}")
    specs = [s for s in registry["sensors"] if s["zone_id"] == zone_id]
    sim_row = db.latest_simulation(building_id)
    summary = (sim_row.result or {}).get("summary", {}).get(zone_id) if sim_row else None
    risk_scores = _risk_cache.get(building_id) or compute_risk(building_id)
    return {
        "zone_id": zone_id,
        "wall_id": wall_id,
        "row": row,
        "col": col,
        "position": "base" if row == 1 else "top" if row == wall["rows"] else "middle",
        "sensors": [sensor_health.overview(s, db.reading_history(building_id, s["id"], min(max(window, 2), 2000)), peers=_peers(building_id, s))
                    for s in specs],
        "detections": [d for d in db.latest_detections(building_id) if d.get("zone_id") == zone_id],
        "risk": next((r for r in risk_scores if r["zone_id"] == zone_id), None),
        "simulation": {"run_id": sim_row.run_id, **summary} if summary else None,
    }


@app.get("/buildings/{building_id}/devices")
def devices(building_id: str) -> list[dict]:
    """Sensor nodes (real or simulated): MQTT online/offline (retained status, last will) and when they last sent data."""
    registry = _registry_for(building_id)
    now = datetime.now(timezone.utc)
    by_device: dict[str, list[str]] = {}
    for spec in registry["sensors"]:
        by_device.setdefault(spec.get("device_id") or "unknown", []).append(spec["id"])
    out = []
    for device_id, sensor_ids in sorted(by_device.items()):
        latest = db.latest_readings(building_id, sensor_ids)
        last = max((sensor_health._ts(r) for r in latest), default=None)
        age = (now - last).total_seconds() if last else None
        reporting = age is not None and age <= sensor_health.STALE_AFTER_S
        mqtt_status = _device_status.get(device_id)
        # A last will ("offline") newer than the last reading means the node just dropped off.
        dropped = bool(mqtt_status and mqtt_status["status"] == "offline" and (not last or datetime.fromisoformat(mqtt_status["ts"]) > last))
        out.append({
            "device_id": device_id,
            "status": "online" if reporting and not dropped else "offline" if last else "never_seen",
            "sensors": sensor_ids,
            "last_seen": last.isoformat() if last else None,
            "last_seen_age_s": round(age, 1) if age is not None else None,
            "mqtt": mqtt_status,
            "provenance": sorted({r["provenance"] for r in latest if r.get("provenance")}),
        })
    return out


@app.post("/readings")
def post_reading(reading: SensorReading) -> dict:
    handle_reading(reading)
    return {"stored": True}


@app.post("/events")
def post_event(event: Event) -> Event:
    _registry_for(event.building_id)
    if event.type == "demo_reset":
        _last_anomaly.clear()
        _risk_cache.pop(event.building_id, None)
    handle_event(event, publish_mqtt=True)
    return event


@app.get("/buildings/{building_id}/events")
def events(building_id: str, limit: int = 50) -> list[dict]:
    return db.recent_events(building_id, min(limit, 500))


@app.post("/photos")
async def upload_photo(
    file: UploadFile = File(...),
    camera_id: str = Form(...),
    building_id: str = Form(...),
    captured_at: str | None = Form(None),
    provenance: Provenance = Form(Provenance.SYNTHETIC),
    sequence: str | None = Form(None),
    frame: int | None = Form(None),
) -> Photo:
    _registry_for(building_id)
    extra = {"captured_at": captured_at} if captured_at else {}
    photo = Photo(camera_id=camera_id, building_id=building_id, provenance=provenance, sequence=sequence, frame=frame, **extra)
    suffix = Path(file.filename or "photo.jpg").suffix.lower() or ".jpg"
    path = settings.photo_dir / f"{photo.photo_id}{suffix}"
    path.write_bytes(await file.read())
    photo.file_url = f"{settings.public_url}/photos/{photo.photo_id}/file"
    db.save_photo(photo, str(path))
    await hub.broadcast("photo", photo.model_dump(mode="json"))
    handle_event(
        Event(type="new_photo", building_id=building_id, source="api", provenance=photo.provenance,
              payload={"photo_id": photo.photo_id, "camera_id": camera_id, "sequence": sequence, "frame": frame}),
        publish_mqtt=True,
    )
    return photo


@app.get("/buildings/{building_id}/photos")
def photos(building_id: str, status: str | None = None, limit: int = 20) -> list[dict]:
    return db.list_photos(building_id, status, min(limit, 200))


@app.get("/photos/{photo_id}/file")
def photo_file(photo_id: str) -> FileResponse:
    row = db.get_photo_row(photo_id)
    if not row or not Path(row.file_path).exists():
        raise HTTPException(status_code=404, detail="Photo not found")
    return FileResponse(row.file_path)


@app.get("/photos/{photo_id}/previous-detections")
def previous_detections(photo_id: str) -> list[dict]:
    row = db.get_photo_row(photo_id)
    if not row:
        raise HTTPException(status_code=404, detail="Photo not found")
    return db.previous_detections(row.building_id, row.camera_id, photo_id)


@app.post("/detections")
async def post_detections(batch: DetectionBatch) -> dict:
    db.save_detections(batch.photo_id, batch.detections)
    data = [d.model_dump(mode="json") for d in batch.detections]
    await hub.broadcast("detection", {"photo_id": batch.photo_id, "camera_id": batch.camera_id, "detections": data})
    _risk_cache.pop(batch.building_id, None)
    changed = [
        {"detection_id": d.detection_id, "zone_id": d.zone_id, "cls": d.cls, "change": d.change, "growth_pct": d.growth_pct}
        for d in batch.detections
        if d.change in ("new", "grown")
    ]
    handle_event(
        Event(type="detection_ready", building_id=batch.building_id, source="vision", provenance=Provenance.MODEL_OUTPUT,
              payload={"photo_id": batch.photo_id, "count": len(batch.detections), "changed": changed, "model": batch.model}),
        publish_mqtt=True,
    )
    return {"stored": len(batch.detections)}


@app.get("/buildings/{building_id}/detections/latest")
def detections_latest(building_id: str) -> list[dict]:
    return db.latest_detections(building_id)


@app.post("/simulations")
async def start_simulation(request: SimulationRequest) -> dict:
    _registry_for(request.building_id)
    run_id = new_id("sim")
    db.create_simulation(run_id, request.building_id, request.model_dump(mode="json"))
    asyncio.get_running_loop().run_in_executor(None, _run_simulation_job, run_id, request)
    return {"run_id": run_id, "status": "running"}


@app.get("/simulations/{run_id}")
def get_simulation(run_id: str) -> dict:
    row = db.get_simulation(run_id)
    if not row:
        raise HTTPException(status_code=404, detail="Unknown run")
    return {"run_id": row.run_id, "status": row.status, "error": row.error, "request": row.request, "result": row.result}


@app.get("/buildings/{building_id}/simulations/latest")
def latest_simulation(building_id: str) -> dict:
    row = db.latest_simulation(building_id)
    if not row:
        raise HTTPException(status_code=404, detail="No finished simulation yet")
    return {"run_id": row.run_id, "status": row.status, "request": row.request, "result": row.result}


@app.get("/buildings/{building_id}/risk")
def risk(building_id: str) -> list[dict]:
    _registry_for(building_id)
    return _risk_cache.get(building_id) or compute_risk(building_id)


@app.get("/evidence/{evidence_id}")
def evidence(evidence_id: str) -> dict:
    record = db.get_evidence(evidence_id)
    if not record:
        raise HTTPException(status_code=404, detail="Unknown evidence id")
    return record


@app.websocket("/ws/live")
async def live(ws: WebSocket) -> None:
    await hub.connect(ws)
    try:
        while True:
            await ws.receive_text()  # clients may send pings; we only push
    except WebSocketDisconnect:
        hub.disconnect(ws)
