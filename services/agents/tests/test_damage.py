"""Damage and Treatment agent: treatment table, detection rules, tools, mock plans, triggers and the LLM graph wiring.

The API runs in-process (FastAPI TestClient), so every tool call below goes through the real routes and database.
The LLM tests use a scripted chat model: no network and no key, but the real graph, tools, delegation and verifier.
"""

import os

os.environ.setdefault("MQTT_ENABLED", "false")
os.environ.setdefault("AGENTS_ENABLED", "false")
os.environ.setdefault("DATABASE_URL", "sqlite:///./data/test_agents.db")
os.environ.setdefault("PHOTO_DIR", "./data/test_photos")

import json  # noqa: E402
from datetime import datetime, timezone  # noqa: E402

import pytest  # noqa: E402
from fastapi import BackgroundTasks  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel  # noqa: E402
from langchain_core.messages import AIMessage  # noqa: E402

from hadaq_agents import knowledge as KB  # noqa: E402
from hadaq_agents import server, tools  # noqa: E402
from hadaq_agents.graph import build_agents  # noqa: E402
from hadaq_api.main import app as api_app  # noqa: E402
from hadaq_schemas import Event, new_id  # noqa: E402


class ScriptedChatModel(GenericFakeChatModel):
    """Replies with the given messages in order; tool calls in them are executed by the real graph."""

    def bind_tools(self, tools, **kwargs):  # noqa: ARG002 - the script already knows the tools
        return self


@pytest.fixture()
def api():
    with TestClient(api_app) as client:
        tools.set_client(client)
        yield client


def seed_wall(client, crack_width: float | None = 1.55) -> dict:
    """One analysed wall photo with four findings (a growing crack, plaster loss, base erosion, a stain) and a crack gauge."""
    photo = client.post("/photos", files={"file": ("frame.jpg", b"\xff\xd8 not a real jpeg", "image/jpeg")},
                        data={"camera_id": "B01-CAM01", "building_id": "B01", "provenance": "synthetic"}).json()
    ids = {name: new_id("det") for name in ("crack", "plaster", "erosion", "stain")}

    def det(name, cls, zone, confidence, change, growth=None):
        return {"detection_id": ids[name], "photo_id": photo["photo_id"], "camera_id": "B01-CAM01", "building_id": "B01",
                "zone_id": zone, "cls": cls, "confidence": confidence, "bbox_xyxy": [0, 0, 10, 10], "model": "mock-detector",
                "provenance": "synthetic", "change": change, "growth_pct": growth}

    batch = [det("crack", "crack", "B01-W01-R2C5", 0.83, "grown", 32.0), det("plaster", "plaster_loss", "B01-W01-R1C2", 0.71, "new"),
             det("erosion", "erosion", "B01-W01-R1C6", 0.66, "new"), det("stain", "moisture_stain", "B01-W01-R1C3", 0.58, "new")]
    assert client.post("/detections", json={"photo_id": photo["photo_id"], "building_id": "B01", "camera_id": "B01-CAM01",
                                            "model": "mock-detector", "detections": batch}).status_code == 200
    if crack_width is not None:
        client.post("/readings", json={"sensor_id": "B01-S06", "building_id": "B01", "zone_id": "B01-W01-R2C5", "kind": "crack_width",
                                       "value": crack_width, "unit": "mm", "ts": datetime.now(timezone.utc).isoformat(),
                                       "provenance": "synthetic"})
    return {"photo_id": photo["photo_id"], **ids}


def event_payload(client, event_id: str) -> dict:
    return client.get(f"/evidence/{event_id}").json()["value"]["payload"]


# ---------- the treatment table ----------

def test_table_has_every_damage_type_in_both_languages():
    table = KB.load()
    entries = table["damage_types"]
    assert len(entries) == 11
    assert sum(e["source"] == "heritage_authority" for e in entries) == 8
    assert sum(e["source"] == "team_proposal" for e in entries) == 3
    for e in entries:
        for field in ("name", "where", "treatment"):
            assert e[f"{field}_ar"].strip() and e[f"{field}_en"].strip(), (e["id"], field)
        for field in ("materials", "tools"):
            assert e[f"{field}_ar"] and len(e[f"{field}_ar"]) == len(e[f"{field}_en"]), (e["id"], field)
        assert e["source"] in table["sources"]
    known = set(KB.type_ids())
    for rule in table["detector_rules"]:
        assert rule["damage_type"] in known and set(rule.get("alternatives", [])) <= known


@pytest.mark.parametrize(("cls", "zone", "expected", "needs_review"), [
    ("crack", "B01-W01-R3C1", "crack", False),
    ("plaster_loss", "B01-W01-R4C8", "plaster_loss", False),
    ("erosion", "B01-W01-R1C6", "base_erosion", False),
    ("erosion", "B01-W01-R3C2", "rain_gullies", False),
    ("missing_material", "B01-W01-R4C2", "parapet_loss", False),
    ("missing_material", "B01-W01-R2C2", "holes_burrows", False),
    ("moisture_stain", "B01-W01-R1C3", "salt_efflorescence", True),
    ("moisture_stain", "B01-W01-R4C3", "biological_growth", True),
    ("graffiti", "B01-W01-R1C1", None, True),
])
def test_detector_rules_use_the_zone_row(cls, zone, expected, needs_review):
    result = KB.classify(cls, zone, rows=4)
    assert result["damage_type"] == expected
    assert result["needs_review"] is needs_review


def test_crack_width_picks_the_treatment_step_and_priority():
    assert KB.crack_severity(1.5) == "fine"
    assert KB.crack_severity(2.4) == "wide"
    assert KB.crack_severity(12.0) == "structural"
    assert KB.crack_severity(None) == "unmeasured"
    assert KB.treatment("crack", "fine")["step_for_severity_en"].startswith("Inject")
    assert KB.treatment("crack", "wide")["step_for_severity_en"].startswith("Stitch")
    assert KB.treatment("crack", "structural")["step_for_severity_en"].startswith("Refer")
    assert "measure" in KB.treatment("crack")["step_for_severity_en"]
    assert KB.priority("crack", "fine") == "low"
    assert KB.priority("crack", "fine", active=True) == "high"
    assert KB.priority("structural_deformation") == "high"
    assert KB.priority("base_erosion") == "medium"


def test_unknown_damage_type_is_refused_with_the_valid_list():
    out = json.loads(tools.get_treatment.invoke({"damage_type": "rust"}))
    assert "error" in out and "crack" in out["valid_damage_types"]


# ---------- tools against the real API ----------

def test_findings_use_the_crack_gauge_and_the_report_carries_materials_and_tools(api):
    ids = seed_wall(api, crack_width=1.55)
    found = json.loads(tools.get_damage_findings.invoke({"building_id": "B01"}))
    assert found["photo_id"] == ids["photo_id"] and found["count"] == 4
    crack = next(f for f in found["findings"] if f["detection_id"] == ids["crack"])
    assert (crack["damage_type"], crack["severity"], crack["active"], crack["priority"]) == ("crack", "fine", True, "high")
    assert crack["crack_width_mm"] == 1.55 and crack["crack_width_evidence_id"].startswith("rd_")
    stain = next(f for f in found["findings"] if f["detection_id"] == ids["stain"])
    assert stain["damage_type"] == "salt_efflorescence" and stain["needs_review"]

    report = json.loads(tools.propose_damage_report.invoke({"building_id": "B01"}))
    assert report["status"] == "pending_expert_approval" and report["priority"] == "high"
    payload = event_payload(api, report["event_id"])
    assert payload["kind"] == "damage_report" and payload["status"] == "pending_expert_approval"
    items = {i["detection_id"]: i for i in payload["report"]["items"]}
    assert set(items) == {ids["crack"], ids["plaster"], ids["erosion"], ids["stain"]}
    assert items[ids["crack"]]["tools_ar"] == ["محقان", "مسطرين", "فرش ناعمة"]
    assert items[ids["crack"]]["refer_to_engineer"]  # growing crack: engineer before filling
    assert items[ids["erosion"]]["avoid_en"] == ["Waterproofing layers", "Bitumen"]
    assert "Mud mortar" in payload["report"]["materials_en"]
    assert ids["photo_id"] in payload["evidence_ids"]


def test_report_refuses_unknown_findings(api):
    seed_wall(api)
    out = json.loads(tools.propose_damage_report.invoke(
        {"building_id": "B01", "items": [{"detection_id": "det_ffffffffffff", "damage_type": "crack"}]}))
    assert "error" in out and out["valid_detection_ids"]


def test_a_photo_analysed_twice_gives_one_finding_per_zone_and_class():
    base = {"photo_id": "ph_x", "zone_id": "B01-W01-R2C5", "cls": "crack"}
    kept = tools._one_per_zone_and_class([
        {**base, "detection_id": "det_a", "confidence": 0.9, "change": "new"},
        {**base, "detection_id": "det_b", "confidence": 0.8, "change": "grown"},
        {**base, "detection_id": "det_c", "zone_id": "B01-W01-R1C2", "cls": "plaster_loss", "confidence": 0.7},
    ])
    assert [d["detection_id"] for d in kept] == ["det_c", "det_b"]  # base row first; the grown crack wins


def test_mask_width_is_used_when_there_is_no_gauge():
    width, evidence, source = tools._crack_width(
        {"detection_id": "det_aaaaaaaaaaaa", "zone_id": "B01-W01-R3C1", "area_cm2": 6.0, "length_mm": 200.0}, gauges={})
    assert width == 3.0 and evidence == "det_aaaaaaaaaaaa" and "mask" in source


# ---------- mock agent (no LLM key) ----------

def test_mock_answers_damage_questions_in_english_and_arabic(api):
    ids = seed_wall(api)
    en = server.run_mock("What damage is on this wall, and where?", "B01")
    assert en.unverified_numbers == [] and not en.needs_approval
    assert ids["crack"] in en.evidence_ids and "Grout syringe" in en.answer and "Heritage authority" in en.answer

    ar = server.run_mock("وش المعالجة للتشققات؟ ووش المواد والأدوات؟", "B01")
    assert ar.unverified_numbers == []
    assert "التشققات" in ar.answer and "محقان" in ar.answer and "طين محلي مطابق للتركيبة الأصلية" in ar.answer
    assert "فقدان اللياسة" not in ar.answer  # only the asked type


def test_mock_report_waits_for_expert_approval(api):
    seed_wall(api)
    response = server.run_mock("ارفع تقرير التلف للمختص", "B01")
    assert response.needs_approval and response.unverified_numbers == []
    assert any(e.startswith("evt_") for e in response.evidence_ids)
    assert "بانتظار اعتماد المختص" in response.answer
    assert [c["name"] for c in response.tool_calls][-1] == "propose_damage_report"


def test_mock_rain_questions_still_run_the_simulation(api):
    response = server.run_mock("What if we fix the drainage?", "B01")
    names = [c["name"] for c in response.tool_calls]
    assert "compare_scenarios" in names and "get_damage_findings" not in names
    assert response.unverified_numbers == []


# ---------- autonomy: triggers ----------

def test_photo_trigger_writes_a_damage_report_with_nobody_typing(api):
    seed_wall(api)
    trigger = Event(type="detection_ready", building_id="B01", source="vision", provenance="model_output",
                    payload={"changed": [{"cls": "crack", "change": "grown"}]})
    server._investigate(trigger)
    events = api.get("/buildings/B01/events", params={"limit": 10}).json()
    note = next(e for e in events if e["type"] == "agent_note" and e["payload"].get("trigger_event_id") == trigger.event_id)
    assert note["payload"]["track"] == "damage" and note["payload"]["needs_approval"]
    assert note["payload"]["unverified_numbers"] == []
    report = next(e for e in events if e["type"] == "recommendation_ready" and e["payload"].get("kind") == "damage_report")
    assert report["payload"]["status"] == "pending_expert_approval"


def test_damage_and_moisture_tracks_have_their_own_cooldown():
    server._last_trigger.clear()

    def accepted(event_type: str) -> bool:
        event = Event(type=event_type, building_id="B01", source="api", provenance="synthetic")
        return server.trigger(event, BackgroundTasks())["accepted"]

    assert accepted("storm_started")
    assert accepted("detection_ready")  # a storm investigation must not block the photo report
    assert not accepted("detection_ready")
    assert not accepted("moisture_anomaly")
    server._last_trigger.clear()


# ---------- LLM path: real graph and tools, scripted model ----------

def _run_scripted(script: list[AIMessage], message: str):
    server._graph = build_agents(ScriptedChatModel(messages=iter(script)))
    try:
        return server.run_llm(message, "B01", session_id=new_id("test"))
    finally:
        server._graph = None


def _damage_script(ids: dict, final_text: str) -> list[AIMessage]:
    return [
        AIMessage(content="", tool_calls=[{"name": "get_building_status", "args": {"building_id": "B01"}, "id": "o1"}]),
        AIMessage(content="", tool_calls=[{"name": "ask_damage_advisor",
                                           "args": {"question": "Prepare and submit the damage report for B01."}, "id": "o2"}]),
        AIMessage(content="", tool_calls=[{"name": "get_damage_findings", "args": {"building_id": "B01"}, "id": "d1"}]),
        AIMessage(content="", tool_calls=[
            {"name": "get_treatment", "args": {"damage_type": "crack", "severity": "fine"}, "id": "d2"},
            {"name": "get_treatment", "args": {"damage_type": "plaster_loss"}, "id": "d3"},
        ]),
        AIMessage(content="", tool_calls=[{"name": "propose_damage_report", "args": {
            "building_id": "B01",
            "items": [{"detection_id": ids["crack"], "damage_type": "crack", "severity": "fine"},
                      {"detection_id": ids["plaster"], "damage_type": "plaster_loss"}],
            "summary": "Growing crack and plaster loss on the facade."}, "id": "d4"}]),
        AIMessage(content=f"Report submitted: crack at B01-W01-R2C5, 1.55 mm [{ids['crack']}]. Waiting for expert approval."),
        AIMessage(content=final_text),
    ]


def test_llm_orchestrator_delegates_to_the_damage_agent(api):
    ids = seed_wall(api, crack_width=1.55)
    final = (f"The crack at B01-W01-R2C5 is 1.55 mm wide [{ids['crack']}] and growing: monitor it, inject a fine mud grout, "
             "and have a structural engineer check it first. Waiting for expert approval.")
    response = _run_scripted(_damage_script(ids, final), "Prepare the damage report")
    names = [c["name"] for c in response.tool_calls]
    assert names == ["get_building_status", "ask_damage_advisor"]
    assert response.unverified_numbers == [] and response.needs_approval
    assert ids["crack"] in response.evidence_ids
    events = api.get("/buildings/B01/events", params={"limit": 5}).json()
    report = next(e for e in events if e["type"] == "recommendation_ready" and e["payload"].get("kind") == "damage_report")
    assert {i["detection_id"] for i in report["payload"]["report"]["items"]} == {ids["crack"], ids["plaster"]}


def test_verifier_catches_an_invented_crack_width_in_an_llm_answer(api):
    ids = seed_wall(api, crack_width=1.55)
    response = _run_scripted(_damage_script(ids, f"The crack is 7.3 mm wide [{ids['crack']}]."), "Prepare the damage report")
    assert response.unverified_numbers == ["7.3"]
    assert "Verifier" in response.answer
