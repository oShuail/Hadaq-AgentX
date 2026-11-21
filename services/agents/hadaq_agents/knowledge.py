"""Treatment knowledge base: damage type -> treatment -> materials -> tools (the team's treatment table).

Plain data and pure functions: no LLM and no network. The mock agent, the LLM tools and the tests all use the same
rules, so every recommendation can be traced back to one row of treatments.json. Edit that file to change what
the agent recommends; the agent itself never adds a treatment, material or tool.
"""

from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path
from typing import Any

DATA_FILE = Path(__file__).parent / "treatments.json"
ZONE_RE = re.compile(r"-R(?P<row>\d+)C(?P<col>\d+)$")
PRIORITY_ORDER = {"low": 0, "medium": 1, "high": 2}
SEVERITY_AR = {"fine": "دقيق", "wide": "عريض", "structural": "إنشائي", "unmeasured": "غير مقاس"}
PRIORITY_AR = {"low": "منخفضة", "medium": "متوسطة", "high": "عالية"}
UNMEASURED_STEP = {
    "en": "Width not measured yet: measure the crack on site, then pick the step for its width",
    "ar": "العرض ما انقاس بعد: يُقاس الشق في الموقع ثم تُختار الخطوة حسب عرضه",
}


@lru_cache(maxsize=1)
def load() -> dict[str, Any]:
    return json.loads(DATA_FILE.read_text(encoding="utf-8"))


def types() -> dict[str, dict[str, Any]]:
    return {entry["id"]: entry for entry in load()["damage_types"]}


def type_ids() -> list[str]:
    return [entry["id"] for entry in load()["damage_types"]]


def zone_position(zone_id: str) -> tuple[int, int] | None:
    """(row, col) from a zone ID such as B01-W01-R2C5. Row 1 is the base, column 1 the left end of the facade."""
    match = ZONE_RE.search(zone_id or "")
    return (int(match["row"]), int(match["col"])) if match else None


def _position_matches(position: str, row: int, rows: int) -> bool:
    checks = {
        "any": True,
        "base": row == 1,
        "above_base": row > 1,
        "top": row == rows,
        "below_top": row < rows,
        "lower_half": row <= rows / 2,
        "upper_half": row > rows / 2,
    }
    return checks[position]


def classify(detector_class: str, zone_id: str, rows: int = 4) -> dict[str, Any]:
    """Map a detector class and the zone's row onto a damage type of the table. The first matching rule wins."""
    position = zone_position(zone_id)
    row = position[0] if position else None
    for rule in load()["detector_rules"]:
        if rule["detector_class"] != detector_class:
            continue
        if rule["position"] != "any" and (row is None or not _position_matches(rule["position"], row, rows)):
            continue
        where = "any row" if rule["position"] == "any" else f"row {row}, {rule['position'].replace('_', ' ')}"
        return {
            "damage_type": rule["damage_type"],
            "needs_review": bool(rule.get("needs_review", False)),
            "alternatives": list(rule.get("alternatives", [])),
            "rule": f"detector class '{detector_class}' ({where}) -> {rule['damage_type']}",
        }
    return {
        "damage_type": None,
        "needs_review": True,
        "alternatives": [],
        "rule": f"no table rule for detector class '{detector_class}': the expert classifies it",
    }


def crack_width_rule() -> dict[str, Any]:
    thresholds = load()["crack_width_mm"]
    return {
        "fine_below_mm": thresholds["fine_below"],
        "structural_from_mm": thresholds["structural_from"],
        "provenance": thresholds["provenance"],
        "note": thresholds["note_en"],
    }


def crack_severity(width_mm: float | None) -> str:
    """fine | wide | structural from the crack width; unmeasured when there is no width."""
    if width_mm is None:
        return "unmeasured"
    thresholds = load()["crack_width_mm"]
    if width_mm < thresholds["fine_below"]:
        return "fine"
    if width_mm < thresholds["structural_from"]:
        return "wide"
    return "structural"


def priority(damage_type: str | None, severity: str | None = None, active: bool = False) -> str:
    """Our (assumed) rule: structural cases and growing cracks first, fine cracks and small holes last."""
    entry = types().get(damage_type or "")
    if entry is None:
        return "medium"  # not classified yet: the expert decides
    if active or severity == "structural" or entry["refer_to_engineer"]:
        return "high"
    if (damage_type == "crack" and severity == "fine") or damage_type == "holes_burrows":
        return "low"
    return "medium"


def treatment(damage_type: str, severity: str | None = None) -> dict[str, Any]:
    """Everything the table says for one damage type, in Arabic and English. Raises KeyError for an unknown type."""
    entry = types()[damage_type]
    source = load()["sources"][entry["source"]]
    out: dict[str, Any] = {
        "damage_type": damage_type,
        "name_en": entry["name_en"],
        "name_ar": entry["name_ar"],
        "source": entry["source"],
        "source_label_en": source["label_en"],
        "source_label_ar": source["label_ar"],
        "source_status_en": source["status_en"],
        "source_status_ar": source["status_ar"],
        "where_en": entry["where_en"],
        "where_ar": entry["where_ar"],
        "treatment_en": entry["treatment_en"],
        "treatment_ar": entry["treatment_ar"],
        "materials_en": entry["materials_en"],
        "materials_ar": entry["materials_ar"],
        "tools_en": entry["tools_en"],
        "tools_ar": entry["tools_ar"],
        "fix_moisture_source_first": entry["fix_moisture_source_first"],
        "test_on_small_area_first": entry["test_on_small_area_first"],
        "refer_to_engineer": entry["refer_to_engineer"],
        "detected_by_camera": entry["detected_by_camera"],
        "provenance": load()["provenance"],
    }
    for key in ("first_step_en", "first_step_ar", "avoid_en", "avoid_ar"):
        if entry.get(key):
            out[key] = entry[key]
    steps = entry.get("steps_by_severity")
    if steps:
        chosen = severity if severity in steps else "unmeasured"
        step = steps.get(chosen, UNMEASURED_STEP)
        out["severity"] = chosen
        out["step_for_severity_en"] = step["en"]
        out["step_for_severity_ar"] = step["ar"]
        out["crack_width_rule"] = crack_width_rule()
    return out


def step(treatment_out: dict[str, Any], lang: str) -> str:
    """The one action to do: the step for this crack's severity, or the whole treatment of the table row."""
    return treatment_out.get(f"step_for_severity_{lang}") or treatment_out[f"treatment_{lang}"]


def report_item(finding: dict[str, Any], damage_type: str, severity: str | None) -> dict[str, Any]:
    """One line of the damage report: the finding plus the table row it maps to."""
    t = treatment(damage_type, severity if damage_type == "crack" else None)
    active = bool(finding.get("active")) and damage_type == "crack"
    item_severity = t.get("severity")
    item = {
        "detection_id": finding["detection_id"],
        "zone_id": finding["zone_id"],
        "position_en": finding.get("position_en"),
        "position_ar": finding.get("position_ar"),
        "detector_class": finding.get("detector_class"),
        "bbox_xyxy": finding.get("bbox_xyxy"),
        "confidence": finding.get("confidence"),
        "damage_type": damage_type,
        "name_en": t["name_en"],
        "name_ar": t["name_ar"],
        "classified_by": "rule" if damage_type == finding.get("damage_type") else "agent",
        "needs_review": bool(finding.get("needs_review")) or t["source"] == "team_proposal",
        "severity": item_severity,
        "active": active,
        "crack_width_mm": finding.get("crack_width_mm") if damage_type == "crack" else None,
        "priority": priority(damage_type, item_severity, active),
        "step_en": step(t, "en"),
        "step_ar": step(t, "ar"),
        "treatment_en": t["treatment_en"],
        "treatment_ar": t["treatment_ar"],
        "materials_en": t["materials_en"],
        "materials_ar": t["materials_ar"],
        "tools_en": t["tools_en"],
        "tools_ar": t["tools_ar"],
        "avoid_en": t.get("avoid_en", []),
        "avoid_ar": t.get("avoid_ar", []),
        "source": t["source"],
        "source_label_en": t["source_label_en"],
        "source_label_ar": t["source_label_ar"],
        "fix_moisture_source_first": t["fix_moisture_source_first"],
        "test_on_small_area_first": t["test_on_small_area_first"],
        "refer_to_engineer": t["refer_to_engineer"] or item_severity == "structural" or active,
        "evidence_ids": [e for e in (finding["detection_id"], finding.get("crack_width_evidence_id")) if e],
    }
    return item


def merged(items: list[dict[str, Any]], key: str) -> list[str]:
    """Union of a list field over report items, in first-seen order."""
    return list(dict.fromkeys(value for item in items for value in item.get(key) or []))


def first_steps(items: list[dict[str, Any]]) -> dict[str, list[str]]:
    """What has to happen before the repairs, from the table flags, with the zones it applies to."""
    def zones(flag: str, sep: str = ", ") -> str:
        return sep.join(dict.fromkeys(i["zone_id"].split("-")[-1] for i in items if i.get(flag)))

    en: list[str] = []
    ar: list[str] = []
    if zones("refer_to_engineer"):
        en.append(f"A structural engineer checks {zones('refer_to_engineer')} before any filling or rebuilding")
        ar.append(f"مهندس إنشائي يفحص {zones('refer_to_engineer', '، ')} قبل أي تعبئة أو إعادة بناء")
    if zones("fix_moisture_source_first"):
        en.append(f"Treat the moisture source at {zones('fix_moisture_source_first')} before repairing")
        ar.append(f"معالجة مصدر الرطوبة في {zones('fix_moisture_source_first', '، ')} قبل الترميم")
    if zones("test_on_small_area_first"):
        en.append(f"Patch-test any poultice or chemical on a small area at {zones('test_on_small_area_first')}")
        ar.append(f"تجربة أي كمادات أو مواد كيميائية على منطقة صغيرة في {zones('test_on_small_area_first', '، ')}")
    return {"en": en, "ar": ar}


PROVENANCE_AR = {"measured": "مقاس", "derived": "مشتق من قياس", "model_output": "مخرجات نموذج", "assumed": "مفترض",
                 "synthetic": "مصطنع للديمو"}


def count_ar(n: int) -> str:
    """Arabic count of findings with the right noun form."""
    if n == 1:
        return "نتيجة واحدة"
    if n == 2:
        return "نتيجتان"
    return f"{n} نتائج" if 3 <= n <= 10 else f"{n} نتيجة"
