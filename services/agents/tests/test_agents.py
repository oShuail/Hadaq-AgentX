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
