import os
from datetime import datetime, timedelta, timezone

os.environ.setdefault("MQTT_ENABLED", "false")
os.environ.setdefault("AGENTS_ENABLED", "false")
os.environ.setdefault("DATABASE_URL", "sqlite:///./data/test_hadaq.db")
os.environ.setdefault("PHOTO_DIR", "./data/test_photos")

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from hadaq_api import sensors as health  # noqa: E402
from hadaq_api.main import app  # noqa: E402

SPEC = {"id": "B01-S07", "kind": "moisture", "unit": "%rel", "zone_id": "B01-W01-R1C1", "device_id": "sim-01"}
NOW = datetime(2026, 11, 21, 12, 0, tzinfo=timezone.utc)


def reading(value: float, seconds_ago: float, quality: str = "ok", n: int = 1) -> dict:
    return {"sensor_id": "B01-S07", "kind": "moisture", "unit": "%rel", "value": value, "quality": quality,
            "ts": (NOW - timedelta(seconds=seconds_ago)).isoformat(), "provenance": "synthetic", "evidence_id": f"rd_{n}"}


def test_health_states():
    assert health.health(SPEC, [], NOW)[0] == "no_data"
    assert health.health(SPEC, [reading(20, 2)], NOW) == ("ok", [])
    assert health.health(SPEC, [reading(100, 2, "fault")], NOW)[0] == "fault"
    status, reasons = health.health(SPEC, [reading(250, 2)], NOW)
    assert status == "suspect" and "plausible" in reasons[0]
    stuck = [reading(31.4, 2 * i) for i in range(health.FLATLINE_N)]
    calm_peer = [{**reading(12.0, 2 * i), "sensor_id": "B01-S04"} for i in range(5)]
    rising_peer = [{**reading(40.0 - i, 2 * i), "sensor_id": "B01-S04"} for i in range(5)]
    assert health.health(SPEC, stuck, NOW)[0] == "ok"  # nothing to compare with: do not accuse it
    assert health.health(SPEC, stuck, NOW, peers=[calm_peer])[0] == "ok"  # a calm wall is flat everywhere
    status, reasons = health.health(SPEC, stuck, NOW, peers=[rising_peer])
    assert status == "suspect" and "B01-S04 is changing" in reasons[0]
    status, reasons = health.health(SPEC, [reading(20, 120)], NOW)
    assert status == "stale" and reasons[0].startswith("no reading for 120")


def test_window_trend_skips_bad_readings():
    history = [reading(40, 0, n=5), reading(100, 2, "fault", n=4), reading(30, 4, n=3), reading(25, 6, n=2), reading(20, 8, n=1)]
    stats = health.window_stats(SPEC, history)
    assert stats["trend"] == "rising" and stats["change"] == 20 and stats["skipped"] == 1
    assert stats["max"] == 40 and stats["max_evidence_id"] == "rd_5" and stats["first_evidence_id"] == "rd_1"
    assert stats["rate_per_min"] == 150.0  # 20 %rel over 8 s
    assert [p["value"] for p in health.downsample(history, 3)] == [20, 30, 40]


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def post(client, sensor_id: str, zone_id: str, value: float, kind: str = "moisture", unit: str = "%rel") -> None:
    body = {"sensor_id": sensor_id, "building_id": "B01", "zone_id": zone_id, "kind": kind, "value": value, "unit": unit,
            "ts": datetime.now(timezone.utc).isoformat(), "provenance": "synthetic", "device_id": "sim-01"}
    assert client.post("/readings", json=body).json()["stored"]


def test_sensor_overview_detail_zone_and_devices(client):
    for value in (20.0, 24.0, 29.0, 35.0):
        post(client, "B01-S07", "B01-W01-R1C1", value)
    post(client, "B01-S09", "B01-W01-R4C5", 0.121, kind="tilt", unit="deg")

    overview = {s["sensor_id"]: s for s in client.get("/buildings/B01/sensors").json()}
    assert len(overview) == 9
    s07 = overview["B01-S07"]
    assert s07["status"] == "ok" and s07["window"]["trend"] == "rising" and s07["window"]["change"] == 15.0
    assert s07["latest"]["evidence_id"].startswith("rd_")
    assert overview["B01-S08"]["status"] == "no_data"

    detail = client.get("/buildings/B01/sensors/B01-S07?points=3").json()
    assert [p["value"] for p in detail["history"]] == [20.0, 29.0, 35.0]
    assert client.get("/buildings/B01/sensors/B01-S99").status_code == 404

    zone = client.get("/buildings/B01/zones/B01-W01-R1C1").json()
    assert zone["position"] == "base" and [s["sensor_id"] for s in zone["sensors"]] == ["B01-S07"]
    assert client.get("/buildings/B01/zones/B01-W01-R9C1").status_code == 404
    assert client.get("/buildings/B01/zones/nonsense").status_code == 404

    devices = {d["device_id"]: d for d in client.get("/buildings/B01/devices").json()}
    assert devices["sim-01"]["status"] == "online" and "B01-S09" in devices["sim-01"]["sensors"]
    assert devices["sim-01"]["provenance"] == ["synthetic"]


def test_faulty_sensor_does_not_raise_a_moisture_alarm(client):
    before = len([e for e in client.get("/buildings/B01/events?limit=500").json() if e["type"] == "moisture_anomaly"])
    body = {"sensor_id": "B01-S08", "building_id": "B01", "zone_id": "B01-W01-R2C3", "kind": "moisture", "value": 100.0,
            "unit": "%rel", "ts": datetime.now(timezone.utc).isoformat(), "provenance": "synthetic", "quality": "fault"}
    client.post("/readings", json=body)
    body.update(value=250.0, quality="ok", ts=(datetime.now(timezone.utc) + timedelta(seconds=1)).isoformat())  # implausible, device says ok
    client.post("/readings", json=body)
    after = len([e for e in client.get("/buildings/B01/events?limit=500").json() if e["type"] == "moisture_anomaly"])
    assert after == before
    assert {s["sensor_id"]: s for s in client.get("/buildings/B01/sensors").json()}["B01-S08"]["status"] == "suspect"
