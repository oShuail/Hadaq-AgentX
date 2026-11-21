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
from datetime import datetime
from pathlib import Path

import httpx
from fastapi import FastAPI, File, Form, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from hadaq_api import db
from hadaq_api.live import hub
from hadaq_api.mqtt_bridge import MqttBridge
from hadaq_api.sensor_run import SensorRun
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
)
from hadaq_sim import WallParams, detect_moisture_anomaly, run_simulation, score_building, weather_for_spec

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("hadaq.api")

AGENT_TRIGGERS = {"storm_started", "moisture_anomaly", "detection_ready"}
_last_anomaly: dict[str, float] = {}
_risk_cache: dict[str, list[dict]] = {}


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


def handle_reading(reading: SensorReading) -> None:
    row_id = db.save_reading(reading)
    evidence_id = f"rd_{row_id}"
    hub.publish_threadsafe("reading", {**reading.model_dump(mode="json"), "evidence_id": evidence_id})
    if reading.kind != "moisture" or reading.quality == "fault":
        return
    history = [(datetime.fromisoformat(r["ts"]), r["value"]) for r in db.reading_history(reading.building_id, reading.sensor_id, 120)]
    anomaly = detect_moisture_anomaly(history)
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


bridge = MqttBridge(on_reading=handle_reading, on_event=lambda e: handle_event(e, publish_mqtt=False))
sensor_run = SensorRun()


def _emit_sensor_event(event_type: str) -> None:
    payload = {"peak_rain_mm_h": 12, "wind_ms": 8, "wind_dir_deg": 270} if event_type == "storm_started" else {}
    handle_event(Event(type=event_type, building_id="B01", source="scenario_panel",
                       provenance=Provenance.SYNTHETIC, payload=payload), publish_mqtt=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    settings.photo_dir.mkdir(parents=True, exist_ok=True)
    hub.attach_loop(asyncio.get_running_loop())
    if settings.mqtt_enabled:
        bridge.start()
    yield
    await sensor_run.stop(_emit_sensor_event, reset=sensor_run.task is not None and not sensor_run.task.done())
    if settings.mqtt_enabled:
        bridge.stop()


app = FastAPI(title="Hadaq API", version="0.1.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ---------- routes ----------

@app.get("/health")
def health() -> dict:
    return {"status": "ok", "mqtt": bridge.connected}


@app.get("/buildings/{building_id}/demo/sensor-run")
def sensor_run_status(building_id: str) -> dict:
    _registry_for(building_id)
    return sensor_run.status(bridge.connected and bridge.devices.get("sim-01") == "online")


@app.post("/buildings/{building_id}/demo/sensor-run")
async def start_sensor_run(building_id: str) -> dict:
    _registry_for(building_id)
    if not bridge.connected or bridge.devices.get("sim-01") != "online":
        raise HTTPException(status_code=503, detail="Start the MQTT broker and sensor simulator first")
    await sensor_run.start(_emit_sensor_event)
    return sensor_run.status(True)


@app.post("/buildings/{building_id}/demo/sensor-run/stop")
async def stop_sensor_run(building_id: str) -> dict:
    _registry_for(building_id)
    await sensor_run.stop(_emit_sensor_event)
    return sensor_run.status(bridge.connected and bridge.devices.get("sim-01") == "online")


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
    detection_provenance = (Provenance.SYNTHETIC if batch.model == "mock-detector" or
                            (batch.detections and all(d.provenance == Provenance.SYNTHETIC for d in batch.detections))
                            else Provenance.MODEL_OUTPUT)
    handle_event(
        Event(type="detection_ready", building_id=batch.building_id, source="vision", provenance=detection_provenance,
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
