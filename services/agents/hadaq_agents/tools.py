"""Tools the agents can call. Each tool calls the real Hadaq API; nothing here makes up data.

Tool outputs are compact JSON strings so the LLM sees exactly the numbers it may quote, with their evidence IDs.
"""

from __future__ import annotations

import json
import os
import time

import httpx
from langchain_core.tools import tool

API_URL = os.getenv("API_URL", "http://localhost:8000")
_client: httpx.Client = httpx.Client(base_url=API_URL, timeout=60)


def set_client(client: httpx.Client) -> None:
    """Tests swap in FastAPI's TestClient here."""
    global _client
    _client = client


def _get(path: str, **params):
    response = _client.get(path, params=params or None)
    response.raise_for_status()
    return response.json()


def _post(path: str, body: dict):
    response = _client.post(path, json=body)
    response.raise_for_status()
    return response.json()


def _run_and_wait(body: dict, timeout_s: float = 120.0) -> dict:
    run_id = _post("/simulations", body)["run_id"]
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        status = _get(f"/simulations/{run_id}")
        if status["status"] != "running":
            return status
        time.sleep(0.5)
    return {"run_id": run_id, "status": "timeout"}


def _top_zones(result: dict, n: int = 6) -> list[dict]:
    rows = sorted(result["summary"].items(), key=lambda kv: (kv[1]["hours_above_threshold"], kv[1]["peak_moisture"]), reverse=True)
    return [{"zone_id": z, **s} for z, s in rows[:n]]


@tool
def get_building_status(building_id: str = "B01") -> str:
    """Latest sensor readings, latest defect detections and the current top risks for a building. Call this first."""
    readings = _get(f"/buildings/{building_id}/readings/latest")
    detections = _get(f"/buildings/{building_id}/detections/latest")
    risk = _get(f"/buildings/{building_id}/risk")[:5]
    return json.dumps({
        "readings": [{k: r.get(k) for k in ("sensor_id", "zone_id", "kind", "value", "unit", "ts", "provenance", "evidence_id")} for r in readings],
        "detections": [{k: d.get(k) for k in ("detection_id", "zone_id", "cls", "confidence", "change", "growth_pct", "provenance")} for d in detections],
        "top_risk": [{k: s.get(k) for k in ("risk_id", "zone_id", "level", "magnitude", "A", "B", "C")} for s in risk],
    })


@tool
def get_recent_events(building_id: str = "B01", limit: int = 10) -> str:
    """Recent events for a building (storms started, anomalies, new photos, finished simulations)."""
    events = _get(f"/buildings/{building_id}/events", limit=limit)
    return json.dumps([{k: e.get(k) for k in ("event_id", "type", "ts", "source", "provenance", "payload")} for e in events])


@tool
def run_rain_simulation(
    building_id: str = "B01",
    scenario: str = "storm_replay",
    mode: str = "synthetic_storm",
    date: str | None = None,
    peak_rain_mm_h: float = 12.0,
    hours: int = 72,
) -> str:
    """Run the wall moisture simulation on the server and wait for the result.
    scenario: baseline | storm_replay | rain_x2 | drainage_fixed.
    mode: synthetic_storm, or replay with date=YYYY-MM-DD for a real past storm.
    Returns the run_id (an evidence ID) and the wettest zones."""
    weather = {"mode": mode, "date": date, "hours": hours, "peak_rain_mm_h": peak_rain_mm_h}
    status = _run_and_wait({"building_id": building_id, "scenario": scenario, "weather": weather, "requested_by": "agent"})
    if status.get("status") != "done":
        return json.dumps({"run_id": status.get("run_id"), "status": status.get("status"), "error": status.get("error")})
    result = status["result"]
    return json.dumps({
        "run_id": status["run_id"],
        "scenario": scenario,
        "engine": result["engine"],
        "provenance": result["provenance"],
        "threshold": result["threshold"],
        "weather_source": result["weather_source"],
        "wettest_zones": _top_zones(result),
        "assumptions": result["assumptions"][:3],
    })


@tool
def compare_scenarios(building_id: str = "B01", scenario_a: str = "storm_replay", scenario_b: str = "drainage_fixed") -> str:
    """Run two scenarios with the same weather and compare the base row (R1). Use for 'what if' and intervention questions."""
    runs = {}
    for scenario in (scenario_a, scenario_b):
        status = _run_and_wait({"building_id": building_id, "scenario": scenario, "weather": {"mode": "synthetic_storm"}, "requested_by": "agent"})
        if status.get("status") != "done":
            return json.dumps({"error": f"{scenario} failed", "status": status.get("status")})
        runs[scenario] = status
    a, b = runs[scenario_a]["result"]["summary"], runs[scenario_b]["result"]["summary"]
    base = sorted(z for z in a if "-R1C" in z)
    return json.dumps({
        "run_ids": {scenario_a: runs[scenario_a]["run_id"], scenario_b: runs[scenario_b]["run_id"]},
        "base_row_hours_above_threshold": {
            scenario_a: sum(a[z]["hours_above_threshold"] for z in base),
            scenario_b: sum(b[z]["hours_above_threshold"] for z in base),
        },
        "base_row_peak_moisture_max": {
            scenario_a: max(a[z]["peak_moisture"] for z in base),
            scenario_b: max(b[z]["peak_moisture"] for z in base),
        },
        "note": "Model output from the uncalibrated bucket_v0 model; differences are indicative, not guaranteed.",
    })


@tool
def get_risk(building_id: str = "B01", top: int = 8) -> str:
    """Risk per zone (ABC method, adapted): A frequency, B loss per event, C share of value; level low..very_high."""
    scores = _get(f"/buildings/{building_id}/risk")[:top]
    return json.dumps([{k: s.get(k) for k in ("risk_id", "zone_id", "level", "magnitude", "A", "B", "C", "evidence_ids")} for s in scores])


@tool
def propose_recommendation(
    building_id: str,
    action: str,
    zone_ids: list[str],
    priority: str,
    evidence_ids: list[str],
    rationale: str,
) -> str:
    """Propose a conservation action for expert review (it is NOT carried out). priority: low | medium | high."""
    event = {
        "type": "recommendation_ready",
        "building_id": building_id,
        "source": "agent",
        "provenance": "model_output",
        "payload": {
            "action": action,
            "zone_ids": zone_ids,
            "priority": priority,
            "evidence_ids": evidence_ids,
            "rationale": rationale,
            "status": "pending_expert_approval",
        },
    }
    posted = _post("/events", event)
    return json.dumps({"event_id": posted["event_id"], "status": "pending_expert_approval"})


ORCHESTRATOR_TOOLS = [get_building_status, get_recent_events, get_risk]
ADVISOR_TOOLS = [get_building_status, run_rain_simulation, compare_scenarios, get_risk, propose_recommendation]
