import os

os.environ.setdefault("MQTT_ENABLED", "false")
os.environ.setdefault("AGENTS_ENABLED", "false")
os.environ.setdefault("DATABASE_URL", "sqlite:///./data/test_agents.db")
os.environ.setdefault("PHOTO_DIR", "./data/test_photos")

from pathlib import Path  # noqa: E402

from fastapi.testclient import TestClient  # noqa: E402

Path("./data/test_agents.db").unlink(missing_ok=True)

from hadaq_agents import tools  # noqa: E402
from hadaq_agents.server import run_mock  # noqa: E402
from hadaq_agents.verifier import verify  # noqa: E402
from hadaq_api.main import app as api_app  # noqa: E402


def test_verifier_flags_invented_numbers():
    outputs = ['{"run_id": "sim_0123456789ab", "peak_moisture": 0.7934, "hours": 53}']
    unverified, cited = verify("Peak 0.79 for 53 h [sim_0123456789ab], loss 42.5%", outputs)
    assert unverified == ["42.5"]
    assert cited == ["sim_0123456789ab"]


def test_mock_agent_runs_real_tools_end_to_end():
    with TestClient(api_app) as client:
        tools.set_client(client)
        response = run_mock("Simulate three days of heavy rain", "B01")
    names = [c["name"] for c in response.tool_calls]
    assert "run_rain_simulation" in names and "get_risk" in names
    assert any(e.startswith("sim_") for e in response.evidence_ids)
    assert response.unverified_numbers == []
    assert response.needs_approval


def _post_reading(client, sensor_id, zone_id, value, quality="ok", kind="moisture", unit="%rel"):
    from datetime import datetime, timezone

    client.post("/readings", json={"sensor_id": sensor_id, "building_id": "B01", "zone_id": zone_id, "kind": kind,
                                   "value": value, "unit": unit, "ts": datetime.now(timezone.utc).isoformat(),
                                   "provenance": "synthetic", "device_id": "sim-01", "quality": quality})


def test_sensor_tools_and_mock_agent_skip_faulty_sensors():
    import json

    with TestClient(api_app) as client:
        tools.set_client(client)
        for value in (18.0, 28.0, 38.0, 48.0):
            _post_reading(client, "B01-S05", "B01-W01-R3C4", value)
        _post_reading(client, "B01-S04", "B01-W01-R1C6", 100.0, quality="fault")

        overview = {s["sensor_id"]: s for s in json.loads(tools.get_sensor_overview.invoke({"building_id": "B01"}))}
        assert overview["B01-S05"]["trend"] == "rising" and overview["B01-S04"]["status"] == "fault"
        args = {"building_id": "B01", "sensor_id": "B01-S05", "window": 4, "points": 4}
        history = json.loads(tools.get_sensor_history.invoke(args))
        assert [p["value"] for p in history["history"]] == [18.0, 28.0, 38.0, 48.0]
        zone = json.loads(tools.get_zone_detail.invoke({"building_id": "B01", "zone_id": "B01-W01-R3C4"}))
        assert zone["position"] == "middle" and zone["sensors"][0]["sensor_id"] == "B01-S05"

        response = run_mock("What is happening to this wall?", "B01")
    assert "**Fastest rising:** B01-S05" in response.answer
    assert "B01-S04 (Base moisture, east side) fault" in response.answer
    assert "100.0" not in response.answer.split("Left out")[0]
    assert response.unverified_numbers == []
