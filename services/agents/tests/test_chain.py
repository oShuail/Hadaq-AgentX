"""v2 agent chain (O2): look-alikes, priority fallback, cited work order drafts, verifier, summary, the chat tool.

No network anywhere: the mock path is checked with sockets blocked, the LLM rewording with scripted chat models, and the
core API with an httpx MockTransport.
"""

import copy
import json
import socket
from pathlib import Path

import httpx
import pytest
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage
from pydantic import Field

from hadaq_agents import chain
from hadaq_agents import knowledge as KB
from hadaq_agents import server
from hadaq_agents import tools as T
from hadaq_agents.priority_fallback import prioritize
from hadaq_agents.verifier import check_work_order
from hadaq_schemas import CaptureResults, Finding, ProcessRequest, WallInfo

EXAMPLES = Path(__file__).resolve().parents[3] / "contracts" / "v2" / "examples"


def example(name: str) -> dict:
    return json.loads((EXAMPLES / name).read_text(encoding="utf-8"))


@pytest.fixture()
def wall() -> WallInfo:
    return WallInfo.model_validate(example("registry.json")["buildings"][0]["walls"][0])  # B01-W01, on a visitor route


def raw_finding(**changes) -> Finding:
    """The contract example's finding as the detector would send it: no lookalike, priority or change yet."""
    data = example("capture_results.json")["findings"][0]
    data.update(lookalike=None, priority=None, change=None)
    data.update(changes)
    return Finding.model_validate(data)


def run(findings, wall, **kwargs) -> CaptureResults:
    return chain.run_chain("cap_9e8d7c6b5a41", findings, wall, detector="rfdetr-seg-small@turaif_v1", llm="mock", **kwargs)


# ---------- the contract example ----------

def test_contract_example_gets_dark_stain_urgent_cited_order_and_closing(wall):
    results = run([raw_finding()], wall)
    assert CaptureResults.model_validate(json.loads(results.model_dump_json())) == results  # the contract shape
    f = results.findings[0]
    assert f.lookalike.pair_id == "dark_stain_base"
    assert f.priority.escalation == "urgent" and "ASSUMED" in f.priority.method
    assert [o.finding_ids for o in results.work_orders] == [[f.finding_id]]
    order = results.work_orders[0]
    assert order.citation.source and order.citation.section is None
    assert order.status == "draft_needs_field_check" and order.escalation == "urgent" and order.fix_water_source_first
    assert order.steps_ar[0].startswith("اجمع الأدلة قبل اعتماد المعالجة") and "قراءات الرطوبة" in order.steps_ar[0]
    assert order.materials_ar == KB.types()["base_erosion"]["materials_ar"]  # only what the library says
    assert results.summary_ar.endswith(chain.CLOSING_AR)
    assert results.summary_en.endswith(chain.CLOSING_EN)
    assert "R1C4" in results.summary_ar and results.chain == "hadaq_agents.chain@v1; summary: template"
    assert "\u2014" not in results.summary_ar + results.summary_en + "".join(order.steps_ar + order.steps_en)


def test_process_takes_the_wall_and_previous_findings_from_the_core_request():
    request = ProcessRequest.model_validate(example("process_request.json"))
    results = chain.process(request, [raw_finding()], detector="rfdetr-seg-small@turaif_v1", scale="absolute", px_per_m=500.0,
                            llm="mock")
    assert results.capture_id == request.capture_id and results.scale == "absolute" and results.findings[0].change == "new"


def test_findings_from_another_capture_or_wall_are_refused(wall):
    with pytest.raises(ValueError):
        run([raw_finding(capture_id="cap_000000000000")], wall)
    with pytest.raises(ValueError):
        run([raw_finding(wall_id="W02")], wall)


# ---------- suspected types ----------

def test_suspected_structural_deformation_is_referred_never_treated(wall):
    suspect = raw_finding(finding_id="fnd_00000000000a", zone_id="B01-W01-R3C2", damage_type="structural_deformation",
                          suspected=True, severity=2)
    results = run([raw_finding(), suspect], wall)
    f = next(x for x in results.findings if x.finding_id == suspect.finding_id)
    assert f.suspected and f.priority.escalation == "urgent"  # Q14: leaning or bulging is escalated, then referred
    assert all(suspect.finding_id not in o.finding_ids for o in results.work_orders)
    assert len(results.work_orders) == 1
    assert "اشتباه يحال للمختص بدون معالجة" in results.summary_ar and "R3C2" in results.summary_ar


def test_suspected_incompatible_repair_asks_for_a_tap_test_and_gets_no_treatment(wall):
    suspect = raw_finding(zone_id="B01-W01-R2C2", damage_type="incompatible_repair", suspected=True, severity=1)
    results = run([suspect], wall)
    assert results.findings[0].lookalike.pair_id == "smooth_surface"
    assert results.work_orders == []


# ---------- verifier ----------

def test_verifier_rejects_a_work_order_without_a_citation(wall):
    order = run([raw_finding()], wall).work_orders[0].model_dump(mode="json")
    assert check_work_order(order) == []
    no_citation = {k: v for k, v in order.items() if k != "citation"}
    assert "no citation" in check_work_order(no_citation)
    assert "no citation" in check_work_order({**order, "citation": {"source": " ", "section": None, "status": "team_proposal"}})
    approved = {**order, "citation": {**order["citation"], "status": "approved_library"}}
    assert check_work_order(approved, library_source="team_proposal") == ["a team proposal is cited as an approved library row"]
    assert check_work_order({**order, "status": "approved"})


def test_citation_is_approved_library_only_when_the_authority_document_is_named(monkeypatch):
    assert chain.citation_for("base_erosion").status == "team_proposal"  # "الهيئة" row, document not named yet
    assert chain.citation_for("timber_decay").status == "team_proposal"
    table = copy.deepcopy(KB.load())
    table["sources"]["heritage_authority"]["document"] = "Crosby, Architectural Conservation (nomination dossier 2009)"
    monkeypatch.setattr(KB, "load", lambda: table)
    cited = chain.citation_for("base_erosion")
    assert cited.status == "approved_library" and cited.source.startswith("Crosby") and cited.section is None
    assert chain.citation_for("timber_decay").status == "team_proposal"  # a team proposal never becomes approved


# ---------- priority and change ----------

def test_change_against_the_previous_inspection(wall):
    before = raw_finding(finding_id="fnd_0000000000b1", severity=2)
    grown = run([raw_finding(severity=3)], wall, previous_findings=[before]).findings[0]
    assert grown.change == "grown" and grown.previous_finding_id == before.finding_id and grown.priority.criteria.active == 1.0
    same = run([raw_finding(severity=2)], wall, previous_findings=[before]).findings[0]
    assert same.change == "unchanged" and same.priority.criteria.active == 0.0
    assert run([raw_finding(change="repaired")], wall).findings[0].change == "repaired"  # the worker's value is kept


def test_escalation_levels_follow_dgda_q14(wall):
    quiet = wall.model_copy(update={"visitor_route": False, "main_facade": False})
    hole = raw_finding(damage_type="voids_burrows", zone_id="B01-W01-R3C6", severity=1, change="unchanged")
    assert prioritize(hole, quiet).escalation == "low"
    assert prioritize(hole.model_copy(update={"severity": 2}), quiet).escalation == "medium"
    salts = raw_finding(damage_type="salt_efflorescence", zone_id="B01-W01-R2C3", severity=2)
    assert prioritize(salts, quiet).escalation == "high"  # active water source
    crack = raw_finding(damage_type="crack", zone_id="B01-W01-R2C5", severity=1, change="grown")
    assert prioritize(crack, quiet).escalation == "high"  # active damage
    assert prioritize(raw_finding(severity=3), quiet).escalation == "urgent"  # severe base erosion: stability
    p = prioritize(raw_finding(), wall)
    assert 0 <= p.score <= 100 and p.reason_ar and p.reason_en and "على مسار الزوار" in p.reason_ar


def test_crack_with_a_lookalike_and_a_width_gets_the_width_step(wall):
    crack = raw_finding(damage_type="crack", zone_id="B01-W01-R2C5", severity=2, width_mm=3.0, area_m2=None)
    order = run([crack], wall).work_orders[0]
    assert order.steps_en[0].startswith("Collect the evidence") and "Crack monitoring" in order.steps_en[0]
    assert KB.treatment("crack", "wide")["step_for_severity_en"] in order.steps_en


def test_no_findings_still_ends_with_the_closing(wall):
    results = run([], wall)
    assert results.findings == [] and results.work_orders == [] and results.summary_ar.endswith(chain.CLOSING_AR)


# ---------- the summary and the LLM ----------

def test_mock_path_never_touches_the_network(wall, monkeypatch):
    def blocked(*args, **kwargs):
        raise AssertionError("network used on the mock path")

    monkeypatch.setattr(socket.socket, "connect", blocked)
    monkeypatch.setattr(socket, "create_connection", blocked)
    monkeypatch.setenv("HADAQ_LLM", "mock")
    results = chain.run_chain("cap_9e8d7c6b5a41", [raw_finding()], wall, detector="test@0")  # llm from the environment
    assert results.chain.endswith("summary: template")


class Recording(GenericFakeChatModel):
    seen: list = Field(default_factory=list)

    def invoke(self, input, *args, **kwargs):  # noqa: A002 - langchain's name
        self.seen.append(input)
        return super().invoke(input, *args, **kwargs)


def test_llm_rewording_gets_text_only_and_is_kept_when_it_passes(wall):
    ar, en = chain.template_summary([], [], [], wall)
    good = {"summary_ar": f"لا يوجد تلف ظاهر في صورة الجدار W01 (B01). {chain.CLOSING_AR}",
            "summary_en": f"The photo of wall W01 (B01) shows no damage. {chain.CLOSING_EN}"}
    model = Recording(messages=iter([AIMessage(json.dumps(good, ensure_ascii=False))]))
    new_ar, new_en, mode = chain.reword(ar, en, model)
    assert (new_ar, new_en) == (good["summary_ar"], good["summary_en"]) and mode.startswith("llm")
    sent = model.seen[0]
    assert all(isinstance(m.content, str) for m in sent)  # text only: no image blocks
    assert json.loads(sent[-1].content) == {"summary_ar": ar, "summary_en": en}


def test_llm_rewording_that_changes_a_number_or_drops_the_closing_is_rejected(wall):
    results = run([raw_finding()], wall)
    ar, en = results.summary_ar, results.summary_en
    for bad in ({"summary_ar": ar.replace(chain.CLOSING_AR, ""), "summary_en": en},
                {"summary_ar": ar, "summary_en": en.replace("R1C4", "R1C5")},
                {"summary_ar": ar + " 4711", "summary_en": en}):
        model = GenericFakeChatModel(messages=iter([AIMessage(json.dumps(bad, ensure_ascii=False))]))
        assert chain.reword(ar, en, model) == (ar, en, "template (LLM rewording rejected by the verifier)")


def test_a_failing_llm_keeps_the_template(wall):
    ar, en = chain.template_summary([], [], [], wall)
    model = GenericFakeChatModel(messages=iter([AIMessage("not json at all")]))
    assert chain.reword(ar, en, model)[2].startswith("template (LLM failed")


# ---------- the chat tool ----------

@pytest.fixture()
def core(monkeypatch):
    """A stand-in core API that serves the contract's example inspection."""
    inspections = [example("inspection.json")]

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v2/buildings/B01/inspections":
            return httpx.Response(200, json=inspections)
        return httpx.Response(200, json=[])

    T.set_core_client(httpx.Client(base_url="http://core.test", transport=httpx.MockTransport(handler)))
    yield inspections
    T.set_core_client(httpx.Client(base_url=T.CORE_URL, timeout=30))


def test_get_latest_inspection_tool(core):
    out = json.loads(T.get_latest_inspection.invoke({"building_id": "B01"}))
    assert out["inspection_id"] == "ins_0c1d2e3f4a5b" and out["findings"][0]["zone"] == "R1C4"
    assert out["findings"][0]["lookalike"]["pair_id"] == "dark_stain_base" and out["findings"][0]["escalation"] == "urgent"
    assert out["note"].endswith(chain.CLOSING_EN)
    assert "note" in json.loads(T.get_latest_inspection.invoke({"building_id": "B02"}))  # no inspection yet
    assert T.get_latest_inspection in T.ORCHESTRATOR_TOOLS


def test_get_latest_inspection_reports_an_unreachable_core():
    def down(request):
        raise httpx.ConnectError("refused")

    T.set_core_client(httpx.Client(base_url="http://core.test", transport=httpx.MockTransport(down)))
    try:
        assert "core API not reachable" in json.loads(T.get_latest_inspection.invoke({"building_id": "B01"}))["error"]
    finally:
        T.set_core_client(httpx.Client(base_url=T.CORE_URL, timeout=30))


@pytest.mark.parametrize(("message", "closing"), [("What did the last survey find?", "The final decision is the conservator's."),
                                                  ("وش لقى آخر مسح؟", "القرار النهائي للمختص.")])
def test_mock_chat_answers_the_last_survey_question(core, message, closing):
    response = server.run_mock(message, "B01")
    assert [c["name"] for c in response.tool_calls] == ["get_latest_inspection"]
    assert "R1C4" in response.answer and response.answer.endswith(closing)
    assert "ins_0c1d2e3f4a5b" in response.evidence_ids and response.unverified_numbers == []
