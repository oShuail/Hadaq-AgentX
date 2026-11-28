import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from hadaq_schemas import (
    CORE_DAMAGE_TYPES,
    Capture,
    CaptureAccepted,
    CaptureMeta,
    CaptureResults,
    Finding,
    Inspection,
    Registry,
    ReviewRequest,
    SitePriorityRow,
    Survey,
    SurveyRequest,
    WorkOrderDraft,
    load_registry,
    zone_from_wall_m,
)
from hadaq_schemas.inspection import LiveMessageV2, ProcessRequest

ROOT = Path(__file__).resolve().parents[3]
EX = ROOT / "contracts" / "v2" / "examples"


def _load(name: str):
    return json.loads((EX / name).read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    "model,name",
    [
        (Registry, "registry.json"),
        (Survey, "survey.json"),
        (SurveyRequest, "survey_request.json"),
        (CaptureMeta, "capture_meta.json"),
        (CaptureAccepted, "capture_accepted.json"),
        (Finding, "finding.json"),
        (WorkOrderDraft, "work_order.json"),
        (ProcessRequest, "process_request.json"),
        (CaptureResults, "capture_results.json"),
        (Inspection, "inspection.json"),
        (ReviewRequest, "review_request.json"),
        (LiveMessageV2, "live_finding.json"),
    ],
)
def test_v2_examples_parse(model, name):
    model.model_validate(_load(name))


def test_site_priority_example():
    for row in _load("site_priority.json"):
        SitePriorityRow.model_validate(row)


def test_example_finding_zone_matches_its_box():
    finding = Finding.model_validate(_load("finding.json"))
    wall = Registry.model_validate(_load("registry.json")).buildings[0].walls[0]
    u = (finding.box_wall_m[0] + finding.box_wall_m[2]) / 2
    v = (finding.box_wall_m[1] + finding.box_wall_m[3]) / 2
    row, col = zone_from_wall_m(u, v, wall.width_m, wall.height_m, wall.rows, wall.cols)
    assert finding.zone_id == f"B01-W01-R{row}C{col}"


def test_zone_from_wall_m_edges():
    assert zone_from_wall_m(0, 0, 32.8, 7.2) == (1, 1)
    assert zone_from_wall_m(32.8, 7.2, 32.8, 7.2) == (4, 8)  # far corner stays inside the grid
    assert zone_from_wall_m(16.5, 3.7, 32.8, 7.2) == (3, 5)


def test_corners_must_be_four_points():
    data = _load("capture_meta.json")
    data["corners_px"] = data["corners_px"][:3]
    with pytest.raises(ValidationError):
        CaptureMeta.model_validate(data)


def test_out_of_scope_types_are_suspected_only():
    data = _load("finding.json")
    data["damage_type"] = "structural_deformation"
    data["suspected"] = False
    with pytest.raises(ValidationError):
        Finding.model_validate(data)
    data["suspected"] = True
    Finding.model_validate(data)


def test_correction_needs_a_value():
    with pytest.raises(ValidationError):
        ReviewRequest.model_validate({"status": "corrected"})


def test_inspection_can_approve_only_when_nothing_pending():
    ins = Inspection.model_validate(_load("inspection.json"))
    assert not ins.can_approve
    ins.findings[0].review.status = "accepted"
    assert ins.can_approve


def test_capture_extends_meta():
    cap = Capture.model_validate(_load("inspection.json")["captures"][0])
    assert cap.capture_id.startswith("cap_") and cap.status == "analyzed"


def test_registry_wall_has_v2_fields():
    wall = load_registry()["walls"][0]
    for key in ("width_m", "height_m", "visitor_route", "main_facade"):
        assert key in wall


def test_treatment_table_covers_core_types():
    table = json.loads((ROOT / "services/agents/hadaq_agents/treatments.json").read_text(encoding="utf-8"))
    ids = {t["id"] for t in table["damage_types"]}
    assert set(CORE_DAMAGE_TYPES) <= ids
