"""Tools the agents can call. Each tool calls the real Hadaq API or the team's treatment table; nothing here makes up data.

Tool outputs are compact JSON strings so the LLM sees exactly the numbers it may quote, with their evidence IDs.
"""

from __future__ import annotations

import json
import os
import time

import httpx
from langchain_core.tools import tool
from pydantic import BaseModel, Field

from hadaq_agents import knowledge as KB

API_URL = os.getenv("API_URL", "http://localhost:8000")
CORE_URL = os.getenv("CORE_URL", "http://localhost:8080")  # Spring core API (v2 inspections)
_client: httpx.Client = httpx.Client(base_url=API_URL, timeout=60)
_core: httpx.Client = httpx.Client(base_url=CORE_URL, timeout=30)
_registry_cache: dict[str, dict] = {}


def set_client(client: httpx.Client) -> None:
    """Tests swap in FastAPI's TestClient here."""
    global _client
    _client = client


def set_core_client(client: httpx.Client) -> None:
    """Tests swap in a stand-in core API here."""
    global _core
    _core = client


def _dumps(data) -> str:
    """JSON for the LLM. ensure_ascii=False keeps Arabic readable (and short) instead of \\u escapes."""
    return json.dumps(data, ensure_ascii=False)


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


def _wall_shape(building_id: str) -> tuple[int, int]:
    """(rows, cols) of the building's main wall from the registry (cached: the registry does not change at runtime)."""
    if building_id not in _registry_cache:
        _registry_cache[building_id] = _get(f"/buildings/{building_id}")
    wall = (_registry_cache[building_id].get("walls") or [{}])[0]
    return int(wall.get("rows", 4)), int(wall.get("cols", 8))


def _crack_width(detection: dict, gauges: dict[str, dict]) -> tuple[float | None, str | None, str | None]:
    """Crack width in mm with its evidence: a crack gauge in the same zone first, else the detection mask (area / length)."""
    gauge = gauges.get(detection["zone_id"])
    if gauge is not None:
        return gauge["value"], gauge.get("evidence_id"), f"crack gauge {gauge['sensor_id']} ({gauge.get('provenance')})"
    if detection.get("area_cm2") and detection.get("length_mm"):
        width = round(detection["area_cm2"] * 100 / detection["length_mm"], 2)
        return width, detection["detection_id"], "estimated from the detection mask (area / length)"
    return None, None, None


def _one_per_zone_and_class(detections: list[dict]) -> list[dict]:
    """A photo analysed twice (worker restart, re-run) stores its detections twice: keep one per zone and class,
    preferring a grown one, then the higher confidence. Order follows the wall: base row first, left to right."""
    best: dict[tuple[str, str], dict] = {}
    for d in detections:
        key = (d["zone_id"], d["cls"])
        rank = (d.get("change") == "grown", d.get("confidence") or 0)
        if key not in best or rank > (best[key].get("change") == "grown", best[key].get("confidence") or 0):
            best[key] = d
    return sorted(best.values(), key=lambda d: (KB.zone_position(d["zone_id"]) or (99, 99), d["cls"]))


def collect_findings(building_id: str) -> dict:
    """The latest photo's detections mapped onto the treatment table: damage type, position, severity, priority, evidence."""
    detections = _one_per_zone_and_class(_get(f"/buildings/{building_id}/detections/latest"))
    if not detections:
        return {"building_id": building_id, "count": 0, "findings": [],
                "note": "No analysed photo with defects yet. Upload a wall photo or start the camera."}
    # Only a healthy crack gauge counts (status "ok": not stale, stuck, faulty or out of range).
    gauges = {s["zone_id"]: {**s["latest"], "sensor_id": s["sensor_id"]}
              for s in _get(f"/buildings/{building_id}/sensors", window=30)
              if s["kind"] == "crack_width" and s["status"] == "ok" and s.get("latest")}
    rows, cols = _wall_shape(building_id)
    findings = []
    for d in detections:
        rule = KB.classify(d["cls"], d["zone_id"], rows)
        position = KB.zone_position(d["zone_id"])
        growing = d.get("change") == "grown"
        finding = {
            "detection_id": d["detection_id"],
            "zone_id": d["zone_id"],
            "position_en": f"row {position[0]} of {rows} from the base, column {position[1]} of {cols} from the left" if position else None,
            "position_ar": f"الصف {position[0]} من {rows} من القاعدة، العمود {position[1]} من {cols} من اليسار" if position else None,
            "detector_class": d["cls"],
            "bbox_xyxy": d.get("bbox_xyxy"),
            "confidence": d.get("confidence"),
            "change": d.get("change"),
            "growth_pct": d.get("growth_pct"),
            "growing": growing,
            "provenance": d.get("provenance"),
            "damage_type": rule["damage_type"],
            "classification_rule": rule["rule"],
            "needs_review": rule["needs_review"],
            "alternatives": rule["alternatives"],
        }
        entry = KB.types().get(rule["damage_type"] or "")
        if entry:
            finding.update(name_en=entry["name_en"], name_ar=entry["name_ar"], source=entry["source"])
        severity = None
        if rule["damage_type"] == "crack":
            width, width_evidence, width_source = _crack_width(d, gauges)
            severity = KB.crack_severity(width)
            finding.update(crack_width_mm=width, crack_width_evidence_id=width_evidence, crack_width_source=width_source,
                           severity=severity, active=growing)
        finding["priority"] = KB.priority(rule["damage_type"], severity, growing and rule["damage_type"] == "crack")
        findings.append(finding)
    return {
        "building_id": building_id,
        "photo_id": detections[0].get("photo_id"),
        "detector_model": detections[0].get("model"),
        "count": len(findings),
        "findings": findings,
        "crack_width_rule": KB.crack_width_rule(),
        "priority_rule": KB.load()["priority_rule"]["note_en"],
        "not_detected_by_camera_yet": [t for t, e in KB.types().items() if not e["detected_by_camera"]],
    }


@tool
def get_building_status(building_id: str = "B01") -> str:
    """Latest sensor readings (with each sensor's health status), latest defect detections and the current top risks
    for a building. Call this first. Do not rely on a reading whose status is not "ok"."""
    sensors = _get(f"/buildings/{building_id}/sensors", window=30)
    detections = _get(f"/buildings/{building_id}/detections/latest")
    risk = _get(f"/buildings/{building_id}/risk")[:5]
    readings = [
        {"sensor_id": s["sensor_id"], "zone_id": s["zone_id"], "kind": s["kind"], "unit": s["unit"], "status": s["status"],
         **{k: s["latest"].get(k) for k in ("value", "ts", "quality", "provenance", "evidence_id")}}
        for s in sensors if s.get("latest")
    ]
    rows, _ = _wall_shape(building_id)
    return json.dumps({
        "readings": readings,
        "detections": [
            {**{k: d.get(k) for k in ("detection_id", "zone_id", "cls", "confidence", "change", "growth_pct", "provenance")},
             "damage_type": KB.classify(d["cls"], d["zone_id"], rows)["damage_type"]}
            for d in detections
        ],
        "top_risk": [{k: s.get(k) for k in ("risk_id", "zone_id", "level", "magnitude", "A", "B", "C")} for s in risk],
    })


def _sensor_brief(s: dict) -> dict:
    latest = s.get("latest") or {}
    window = s.get("window") or {}
    brief = {
        "sensor_id": s["sensor_id"], "label": s.get("label"), "kind": s["kind"], "unit": s["unit"], "zone_id": s["zone_id"],
        "status": s["status"], "reasons": s.get("reasons", []),
        "latest": latest.get("value"), "latest_evidence_id": latest.get("evidence_id"), "provenance": latest.get("provenance"),
    }
    if window:
        brief.update({k: window.get(k) for k in ("trend", "first", "min", "max", "change", "rate_per_min", "readings",
                                                 "from_ts", "to_ts", "first_evidence_id", "max_evidence_id")})
    return brief


@tool
def get_sensor_overview(building_id: str = "B01", window: int = 60) -> str:
    """Every sensor on the building with its health (ok, suspect, fault, stale, no_data and why), the latest value and
    the trend over the last `window` readings (rising, falling or steady, change, min, max, rate per minute), each
    with evidence IDs. Use it to see what is changing on the wall right now and which sensors to distrust."""
    return json.dumps([_sensor_brief(s) for s in _get(f"/buildings/{building_id}/sensors", window=window)])


@tool
def get_sensor_history(building_id: str, sensor_id: str, window: int = 120, points: int = 20) -> str:
    """One sensor's readings over the last `window` readings, downsampled to `points` values (oldest first), plus its
    health and trend. Use it to describe how a value developed (for example before and after a storm)."""
    detail = _get(f"/buildings/{building_id}/sensors/{sensor_id}", window=window, points=points)
    return json.dumps({**_sensor_brief(detail), "history": detail.get("history", [])})


@tool
def get_zone_detail(building_id: str, zone_id: str) -> str:
    """Everything about one wall zone (e.g. B01-W01-R1C3): its sensors with health and trend, the latest camera
    detections there, its risk score and its result in the latest simulation. Use it before explaining one zone."""
    zone = _get(f"/buildings/{building_id}/zones/{zone_id}")
    return json.dumps({
        **{k: zone.get(k) for k in ("zone_id", "row", "col", "position", "simulation")},
        "sensors": [_sensor_brief(s) for s in zone.get("sensors", [])],
        "detections": [{k: d.get(k) for k in ("detection_id", "cls", "confidence", "change", "growth_pct", "provenance")}
                       for d in zone.get("detections", [])],
        "risk": {k: zone["risk"].get(k) for k in ("risk_id", "level", "magnitude", "A", "B", "C")} if zone.get("risk") else None,
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


@tool
def get_damage_findings(building_id: str = "B01") -> str:
    """Damage seen in the latest analysed wall photo, mapped onto the team's treatment table. For each finding: damage type,
    zone (row from the base, column from the left), how it was classified, whether it is growing, the crack width and
    severity (cracks), a priority and the evidence IDs. Call this before talking about damage, treatments, materials or tools."""
    return _dumps(collect_findings(building_id))


@tool
def get_treatment(damage_type: str, severity: str = "") -> str:
    """Treatment, materials and tools for one damage type, from the team's treatment table, in Arabic and English, with its
    source (Heritage authority guidance, or a team proposal a conservator must confirm).
    damage_type: crack | plaster_loss | base_erosion | rain_grooves | salt_efflorescence | voids_burrows | parapet_loss |
    incompatible_repair | timber_decay | biological_growth | structural_deformation.
    severity (cracks only): fine | wide | structural | unmeasured. Use only what this returns; never add materials, tools or steps."""
    try:
        return _dumps(KB.treatment(damage_type.strip(), severity.strip() or None))
    except KeyError:
        return _dumps({"error": f"unknown damage_type '{damage_type}'", "valid_damage_types": KB.type_ids()})


@tool
def list_damage_types() -> str:
    """All damage types in the treatment table: name, where they appear, source, and whether our camera can detect them yet."""
    return _dumps([
        {"damage_type": e["id"], "name_en": e["name_en"], "name_ar": e["name_ar"], "where_en": e["where_en"],
         "where_ar": e["where_ar"], "source": e["source"], "detected_by_camera": e["detected_by_camera"]}
        for e in KB.types().values()
    ])


@tool
def get_latest_inspection(building_id: str = "B01") -> str:
    """The latest field inspection (v2 survey) of a building from the core API: each finding with its zone, damage type,
    severity, escalation (urgent, high, medium, low) and score, the evidence still needed when the photo cannot tell two
    damages apart (lookalike), the conservator's review status, and the work order drafts. Use it for "what did the last
    survey or inspection find?". The final decision is always the conservator's."""
    from hadaq_agents.chain import brief
    from hadaq_schemas import Inspection

    try:
        response = _core.get(f"/api/v2/buildings/{building_id}/inspections")
        response.raise_for_status()
        rows = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        return _dumps({"error": f"core API not reachable at {CORE_URL} ({type(exc).__name__})"})
    if not rows:
        return _dumps({"building_id": building_id, "note": "no inspection yet for this building"})
    try:
        return _dumps(brief(Inspection.model_validate(rows[0])))  # the core lists them newest first
    except Exception as exc:  # noqa: BLE001 - a shape mismatch must not crash the chat
        return _dumps({"error": f"unexpected inspection format from the core API ({type(exc).__name__})"})


class ReportItem(BaseModel):
    detection_id: str = Field(description="A detection_id from get_damage_findings")
    damage_type: str = Field(description="Damage type from the table; normally the one get_damage_findings gave")
    severity: str | None = Field(default=None, description="Cracks only: fine | wide | structural | unmeasured")


@tool
def propose_damage_report(building_id: str = "B01", items: list[ReportItem] | None = None, summary: str = "") -> str:
    """Send the damage report of the latest photo to the conservation expert for review (nothing is carried out).
    items: one entry per finding (detection_id, damage_type, severity for cracks); leave empty to report every finding as
    classified. The tool itself adds each finding's zone, treatment, materials and tools from the treatment table.
    summary: one or two sentences for the expert."""
    found = collect_findings(building_id)
    by_id = {f["detection_id"]: f for f in found["findings"]}
    requested = [i.model_dump() if isinstance(i, BaseModel) else dict(i) for i in (items or [])]
    if not requested:
        requested = [{"detection_id": f["detection_id"], "damage_type": f["damage_type"], "severity": f.get("severity")}
                     for f in found["findings"] if f["damage_type"]]
    report_items, skipped = [], []
    for item in requested:
        finding = by_id.get(item.get("detection_id", ""))
        if finding is None:
            skipped.append(f"unknown detection_id {item.get('detection_id')}")
            continue
        if item.get("damage_type") not in KB.types():
            skipped.append(f"unknown damage_type {item.get('damage_type')} for {finding['detection_id']}")
            continue
        severity = (item.get("severity") or finding.get("severity")) if item["damage_type"] == "crack" else None
        report_items.append(KB.report_item(finding, item["damage_type"], severity))
    if not report_items:
        return _dumps({"error": "nothing to report: " + ("; ".join(skipped) or found.get("note", "no findings")),
                       "valid_detection_ids": list(by_id), "valid_damage_types": KB.type_ids()})

    overall = max((i["priority"] for i in report_items), key=KB.PRIORITY_ORDER.__getitem__)
    zones = list(dict.fromkeys(i["zone_id"] for i in report_items))
    evidence = list(dict.fromkeys([found["photo_id"], *(e for i in report_items for e in i["evidence_ids"])]))
    short = ", ".join(f"{i['name_en']} {i['zone_id'].split('-')[-1]}" for i in report_items)
    short_ar = "، ".join(f"{i['name_ar']} {i['zone_id'].split('-')[-1]}" for i in report_items)
    steps = KB.first_steps(report_items)
    event = {
        "type": "recommendation_ready",
        "building_id": building_id,
        "source": "agent",
        "provenance": "model_output",
        "payload": {
            "kind": "damage_report",
            "action": f"Damage report for expert review, {len(report_items)} finding{'' if len(report_items) == 1 else 's'}: {short}",
            "action_ar": f"تقرير تلف لمراجعة المختص، {KB.count_ar(len(report_items))}: {short_ar}",
            "zone_ids": zones,
            "priority": overall,
            "evidence_ids": evidence,
            "rationale": summary or "Damage found in the latest wall photo, mapped onto the team's treatment table.",
            "status": "pending_expert_approval",
            "report": {
                "photo_id": found["photo_id"],
                "photo_provenance": found["findings"][0].get("provenance") if found["findings"] else None,
                "detector_model": found["detector_model"],
                "items": report_items,
                "first_steps_en": steps["en"],
                "first_steps_ar": steps["ar"],
                "materials_en": KB.merged(report_items, "materials_en"),
                "materials_ar": KB.merged(report_items, "materials_ar"),
                "tools_en": KB.merged(report_items, "tools_en"),
                "tools_ar": KB.merged(report_items, "tools_ar"),
                "crack_width_rule": found["crack_width_rule"],
                "priority_rule": found["priority_rule"],
                "provenance_note": "Classification and priority are model output and assumed rules; treatments come from the team's table. A conservator must approve.",
            },
        },
    }
    posted = _post("/events", event)
    return _dumps({
        "event_id": posted["event_id"],
        "status": "pending_expert_approval",
        "priority": overall,
        "items": [{k: i[k] for k in ("detection_id", "zone_id", "damage_type", "severity", "priority")} for i in report_items],
        "first_steps_en": steps["en"],
        "first_steps_ar": steps["ar"],
        "skipped": skipped,
    })


ORCHESTRATOR_TOOLS = [get_building_status, get_sensor_overview, get_sensor_history, get_zone_detail, get_recent_events, get_risk,
                      get_latest_inspection]
ADVISOR_TOOLS = [get_building_status, get_sensor_overview, get_zone_detail, run_rain_simulation, compare_scenarios, get_risk,
                 propose_recommendation]
DAMAGE_TOOLS = [get_damage_findings, get_treatment, list_damage_types, propose_damage_report]
