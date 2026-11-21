import os
import time

os.environ.setdefault("MQTT_ENABLED", "false")
os.environ.setdefault("AGENTS_ENABLED", "false")
os.environ.setdefault("DATABASE_URL", "sqlite:///./data/test_hadaq.db")
os.environ.setdefault("PHOTO_DIR", "./data/test_photos")

from pathlib import Path  # noqa: E402

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

Path("./data/test_hadaq.db").unlink(missing_ok=True)

from hadaq_api.main import app  # noqa: E402


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def test_health_and_building(client):
    assert client.get("/health").json()["status"] == "ok"
    building = client.get("/buildings/B01").json()
    assert building["building"]["id"] == "B01"
    assert client.get("/buildings/B99").status_code == 404


def test_readings_and_anomaly(client):
    reading = {"sensor_id": "B01-S04", "building_id": "B01", "zone_id": "B01-W01-R1C6", "kind": "moisture",
               "value": 72.0, "unit": "%rel", "ts": "2026-11-20T10:00:00+03:00", "provenance": "synthetic"}
    assert client.post("/readings", json=reading).json()["stored"]
    latest = client.get("/buildings/B01/readings/latest").json()
    assert latest[0]["evidence_id"].startswith("rd_")
    events = client.get("/buildings/B01/events").json()
    assert any(e["type"] == "moisture_anomaly" for e in events)


def test_simulation_risk_and_evidence(client):
    body = {"building_id": "B01", "scenario": "storm_replay", "weather": {"mode": "synthetic_storm", "hours": 48}}
    run_id = client.post("/simulations", json=body).json()["run_id"]
    for _ in range(50):
        status = client.get(f"/simulations/{run_id}").json()
        if status["status"] != "running":
            break
        time.sleep(0.1)
    assert status["status"] == "done", status
    risk = client.get("/buildings/B01/risk").json()
    assert len(risk) == 32
    assert client.get(f"/evidence/{run_id}").json()["kind"] == "simulation"
    assert client.get(f"/evidence/{risk[0]['risk_id']}").json()["kind"] == "risk"


def test_photo_and_detections(client):
    files = {"file": ("frame_001.jpg", b"fake-jpeg-bytes", "image/jpeg")}
    data = {"camera_id": "B01-CAM01", "building_id": "B01", "provenance": "synthetic", "sequence": "baseline", "frame": "1"}
    photo = client.post("/photos", files=files, data=data).json()
    assert photo["photo_id"].startswith("ph_")
    assert client.get("/buildings/B01/photos?status=new").json()[0]["photo_id"] == photo["photo_id"]
    batch = {"photo_id": photo["photo_id"], "building_id": "B01", "camera_id": "B01-CAM01", "model": "mock-detector",
             "detections": [{"photo_id": photo["photo_id"], "camera_id": "B01-CAM01", "building_id": "B01",
                             "zone_id": "B01-W01-R2C5", "cls": "crack", "confidence": 0.9,
                             "bbox_xyxy": [1, 2, 3, 4], "model": "mock-detector", "provenance": "synthetic",
                             "change": "grown", "growth_pct": 25}]}
    assert client.post("/detections", json=batch).json()["stored"] == 1
    assert client.get("/buildings/B01/photos?status=new").json() == []
    assert client.get("/buildings/B01/detections/latest").json()[0]["cls"] == "crack"
    event = next(e for e in client.get("/buildings/B01/events").json() if e["type"] == "detection_ready")
    assert event["provenance"] == "synthetic"
