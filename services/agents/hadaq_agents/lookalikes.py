"""Look-alike table: damages that look the same in a photo but need different treatments (DGDA Q&A, Q8).

Plain data and pure functions, like knowledge.py: no LLM and no network. The chain calls `lookalike_for(finding)`.
When it returns a Lookalike, the finding carries both options and the evidence the conservator needs; the agent
never picks one of the two options itself. Edit lookalikes.json to change a pair, never this file.

    python -m hadaq_agents.lookalikes      # prints the table as markdown (slide, DGDA check)
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal

from hadaq_schemas import Finding, Lookalike

from hadaq_agents.knowledge import zone_position

DATA_FILE = Path(__file__).parent / "lookalikes.json"
Lang = Literal["en", "ar"]


@lru_cache(maxsize=1)
def load() -> dict[str, Any]:
    return json.loads(DATA_FILE.read_text(encoding="utf-8"))


def pairs() -> dict[str, dict[str, Any]]:
    return {entry["id"]: entry for entry in load()["pairs"]}


def evidence() -> dict[str, dict[str, Any]]:
    return {entry["id"]: entry for entry in load()["evidence"]}


def _row_matches(rule: str, zone_id: str) -> bool:
    if rule == "any":
        return True
    position = zone_position(zone_id)
    if position is None:  # zone unknown: we cannot say it is the base, so a base-only pair does not apply
        return False
    if rule == "base":
        return position[0] == 1
    raise ValueError(f"unknown row rule {rule!r} in lookalikes.json")


def match(damage_type: str, zone_id: str) -> dict[str, Any] | None:
    """The table row for this damage type in this zone, or None. The first matching pair wins."""
    for pair in load()["pairs"]:
        if damage_type in pair["damage_types"] and _row_matches(pair["rows"], zone_id):
            return pair
    return None


def to_lookalike(pair: dict[str, Any], lang: Lang = "en") -> Lookalike:
    """A table row as the contract's Lookalike. Options and evidence in `lang`; note_ar is always Arabic."""
    catalog = evidence()
    return Lookalike(
        pair_id=pair["id"],
        options=[option[f"label_{lang}"] for option in pair["options"]],
        evidence_needed=[catalog[eid][f"label_{lang}"] for eid in pair["evidence"]],
        note_ar=pair["note_ar"],
    )


def lookalike_for(finding: Finding | Mapping[str, Any], lang: Lang = "en") -> Lookalike | None:
    """The look-alike question for one finding, or None when the photo is enough.

    Takes a contract Finding (uses the reviewed type if the conservator corrected it) or a dict with
    damage_type and zone_id.
    """
    if isinstance(finding, Finding):
        damage_type, zone_id = finding.effective_type, finding.zone_id
    else:
        damage_type, zone_id = finding["damage_type"], finding["zone_id"]
    pair = match(damage_type, zone_id)
    return to_lookalike(pair, lang) if pair else None


def markdown_table() -> str:
    """The 7 pairs as a markdown table in Arabic, for the slide and for DGDA to check."""
    catalog = evidence()
    lines = [
        "| الحالة في الصورة | الاحتمال الأول ← علاجه | الاحتمال الثاني ← علاجه | الدليل المطلوب | يطبق على |",
        "| --- | --- | --- | --- | --- |",
    ]
    for pair in load()["pairs"]:
        first, second = (f"{o['label_ar']} ← {o['treatment_ar']}" for o in pair["options"])
        needed = "، ".join(catalog[eid]["label_ar"] for eid in pair["evidence"])
        where = ", ".join(pair["damage_types"]) + (" (R1)" if pair["rows"] == "base" else "")
        lines.append(f"| {pair['case_ar']} | {first} | {second} | {needed} | {where} |")
    return "\n".join(lines)


if __name__ == "__main__":
    print(markdown_table())
