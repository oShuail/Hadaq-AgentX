"""The v2 agent chain: detector findings for one capture -> priority, look-alike questions, cited work order drafts, summary.

The AI worker (A3) calls `process(request, findings, detector=...)` and posts the CaptureResults it returns to the core
API. Steps, in order:

1. Change against the wall's previous findings (only where the worker left `change` empty).
2. Suspected-only types (and anything else marked suspected) are referred to the specialist: no treatment for them.
3. Look-alike check (lookalikes.json, DGDA Q8). The chain never picks one of the two options.
4. Priority and escalation (DGDA Q4, Q14) from `prioritize`: B2 when it lands, our ASSUMED fallback until then.
5. A work order draft per confirmed finding from the treatment library (treatments.json until B1 lands). A finding with
   a look-alike gets "collect the evidence" as its first step. Every draft needs a field check.
6. Verifier: a draft without a citation, or a team proposal cited as approved, is withheld.
7. Arabic and English summary from a template, ending with "القرار النهائي للمختص". An LLM may reword it (text only,
   never photos); with HADAQ_LLM=mock, or if the LLM fails or changes a number, the template is used and `chain` says so.
"""

from __future__ import annotations

import json
import logging
import os
import re
from collections import Counter
from typing import Any

from hadaq_schemas.inspection import (
    DAMAGE_NAMES_AR,
    ESCALATION_NAMES_AR,
    ESCALATION_ORDER,
    SEVERITY_NAMES_AR,
    CaptureResults,
    Citation,
    Finding,
    Inspection,
    ProcessRequest,
    WallInfo,
    WorkOrderDraft,
    WorkOrderLocation,
)

from hadaq_agents import knowledge as KB
from hadaq_agents import lookalikes as LA
from hadaq_agents.priority_fallback import prioritize  # B2: change to `from hadaq_agents.priority import prioritize`
from hadaq_agents.verifier import check_work_order, verify

log = logging.getLogger("hadaq.chain")

CHAIN_ID = "hadaq_agents.chain@v1"
CLOSING_AR = "القرار النهائي للمختص"
CLOSING_EN = "The final decision is the conservator's."
ZONE_RE = re.compile(r"R\d+C\d+")
REWORD_PROMPT = """You reword an inspection summary for a heritage conservator. You get JSON with summary_ar and summary_en.
Make each one read naturally (Arabic: clear Modern Standard Arabic). Rules: keep every number, zone ID (like R1C4) and damage
name exactly; add no facts, treatments, causes or advice; keep the last sentence exactly as it is; no em dashes.
Answer with JSON only: {"summary_ar": "...", "summary_en": "..."}"""


def _short(zone_id: str) -> str:
    return zone_id.split("-")[-1]


def _name_en(kind: str) -> str:
    return KB.types().get(kind, {}).get("name_en", kind.replace("_", " "))


# ---------------------------------------------------------------- steps 1 to 4: findings


def mark_change(finding: Finding, previous: list[Finding]) -> None:
    """Same zone and type in the previous approved inspection: unchanged, or grown if the severity went up. ASSUMED rule."""
    if finding.change is not None:
        return
    same = [p for p in previous if p.zone_id == finding.zone_id and p.effective_type == finding.effective_type]
    if not same:
        finding.change = "new"
        return
    before = max(same, key=lambda p: p.effective_severity)
    finding.previous_finding_id = before.finding_id
    finding.change = "grown" if finding.effective_severity > before.effective_severity else "unchanged"


# ---------------------------------------------------------------- step 5 and 6: work orders


def citation_for(damage_type: str) -> Citation:
    """The library row's source. approved_library only when the row is from the authority AND its document is named;
    the section is never invented."""
    entry = KB.types()[damage_type]
    source = KB.load()["sources"][entry["source"]]
    document = source.get("document")
    approved = entry["source"] != "team_proposal" and bool(document)
    text = f"{document or source['label_en']}: treatment table row '{damage_type}'"
    if not document:
        text += " (treatments.json; source document not named yet)"
    return Citation(source=text, section=None, status="approved_library" if approved else "team_proposal")


def _evidence_steps(pair: dict[str, Any]) -> tuple[list[str], list[str]]:
    catalog = LA.evidence()
    needed_ar = "، ".join(catalog[e]["label_ar"] for e in pair["evidence"])
    needed_en = "; ".join(catalog[e]["label_en"] for e in pair["evidence"])
    a, b = pair["options"]
    ar = [f"اجمع الأدلة قبل اعتماد المعالجة: {needed_ar}",
          f"حسب الدليل (جدول الهيئة للحالات المتشابهة): 1) {a['label_ar']}: {a['treatment_ar']}. 2) {b['label_ar']}: {b['treatment_ar']}."]
    en = [f"Collect the evidence before the treatment is confirmed: {needed_en}",
          f"Depending on the evidence (DGDA look-alike table): 1) {a['label_en']}: {a['treatment_en']}. 2) {b['label_en']}: {b['treatment_en']}."]
    return ar, en


def work_order_for(finding: Finding) -> WorkOrderDraft | None:
    """A draft from the treatment library for one confirmed finding, or None when the library has no row for it."""
    kind = finding.effective_type
    entry = KB.types().get(kind)
    if entry is None or finding.priority is None:
        return None
    severity = KB.crack_severity(finding.width_mm) if kind == "crack" else None
    t = KB.treatment(kind, severity)
    flags = {
        "zone_id": finding.zone_id,
        "refer_to_engineer": t["refer_to_engineer"] or severity == "structural" or (kind == "crack" and finding.change == "grown"),
        "fix_moisture_source_first": t["fix_moisture_source_first"],
        "test_on_small_area_first": t["test_on_small_area_first"],
    }
    steps_ar: list[str] = []
    steps_en: list[str] = []
    pair = LA.match(kind, finding.zone_id)
    if pair:
        ar, en = _evidence_steps(pair)
        steps_ar += ar
        steps_en += en
    first = KB.first_steps([flags])
    steps_ar += first["ar"]
    steps_en += first["en"]
    if t.get("step_for_severity_ar") and t.get("first_step_ar"):
        steps_ar.append(t["first_step_ar"])
        steps_en.append(t["first_step_en"])
    steps_ar.append(KB.step(t, "ar"))
    steps_en.append(KB.step(t, "en"))
    return WorkOrderDraft(
        inspection_id=finding.inspection_id,
        finding_ids=[finding.finding_id],
        location=WorkOrderLocation(building_id=finding.building_id, wall_id=finding.wall_id, zone_ids=[finding.zone_id],
                                   box_wall_m=finding.box_wall_m),
        treatment_id=kind,
        steps_ar=list(dict.fromkeys(steps_ar)),
        steps_en=list(dict.fromkeys(steps_en)),
        materials_ar=t["materials_ar"],
        materials_en=t["materials_en"],
        tools_ar=t["tools_ar"],
        tools_en=t["tools_en"],
        fix_water_source_first=t["fix_moisture_source_first"],
        citation=citation_for(kind),
        escalation=finding.priority.escalation,
    )


# ---------------------------------------------------------------- step 7: summary


def _levels(findings: list[Finding], ar: bool) -> str:
    counts = Counter(f.priority.escalation for f in findings if f.priority)
    order = sorted(counts, key=ESCALATION_ORDER.__getitem__)
    if ar:
        return "، ".join(f"{ESCALATION_NAMES_AR[k]} {counts[k]}" for k in order)
    return ", ".join(f"{counts[k]} {k}" for k in order)


def template_summary(findings: list[Finding], orders: list[WorkOrderDraft], withheld: list[str], wall: WallInfo) -> tuple[str, str]:
    where_ar = f"الجدار {wall.id} ({wall.building_id})"
    where_en = f"wall {wall.id} ({wall.building_id})"
    if not findings:
        return (f"لم يرصد تلف في صورة {where_ar}. {CLOSING_AR}", f"No damage found in the photo of {where_en}. {CLOSING_EN}")
    ranked = sorted(findings, key=lambda f: (ESCALATION_ORDER[f.priority.escalation], -f.priority.score))
    ar = [f"{KB.count_ar(len(findings))} في {where_ar} ({_levels(findings, True)})."]
    en = [f"{len(findings)} finding{'' if len(findings) == 1 else 's'} on {where_en} ({_levels(findings, False)})."]
    for f in ranked:
        if f.priority.escalation == "urgent":
            ar.append(f"عاجل في {_short(f.zone_id)}: {f.priority.reason_ar}.")
            en.append(f"Urgent at {_short(f.zone_id)}: {f.priority.reason_en}.")
    asks = [f for f in ranked if f.lookalike]
    if asks:
        ar.append("قبل اعتماد المعالجة يلزم: " + "؛ ".join(
            f"{_short(f.zone_id)}: " + "، ".join(LA.evidence()[e]["label_ar"] for e in LA.pairs()[f.lookalike.pair_id]["evidence"])
            for f in asks) + ".")
        en.append("Needed before the treatment is confirmed: " + "; ".join(
            f"{_short(f.zone_id)}: " + ", ".join(f.lookalike.evidence_needed) for f in asks) + ".")
    referred = [f for f in ranked if f.suspected]
    if referred:
        ar.append("اشتباه يحال للمختص بدون معالجة: " + "، ".join(
            f"{DAMAGE_NAMES_AR.get(f.effective_type, f.effective_type)} في {_short(f.zone_id)}" for f in referred) + ".")
        en.append("Suspected, referred to the specialist with no treatment: " + ", ".join(
            f"{_name_en(f.effective_type).lower()} at {_short(f.zone_id)}" for f in referred) + ".")
    if orders:
        ar.append(f"مسودات أوامر العمل: {len(orders)}، كلها تحتاج فحصاً ميدانياً.")
        en.append(f"Work order drafts: {len(orders)}, all need a field check.")
    if withheld:
        ar.append(f"أوامر عمل محجوبة لعدم اكتمال التوثيق: {len(withheld)}.")
        en.append(f"Work orders withheld for a missing or wrong citation: {len(withheld)}.")
    ar.append(CLOSING_AR)
    en.append(CLOSING_EN)
    return " ".join(ar), " ".join(en)


def reword(summary_ar: str, summary_en: str, model: Any = None) -> tuple[str, str, str]:
    """(summary_ar, summary_en, mode). Only the two template strings are sent; the template wins on any doubt."""
    model = model if model is not None else os.getenv("HADAQ_LLM", "mock")
    if model == "mock":
        return summary_ar, summary_en, "template"
    try:
        from langchain_core.messages import HumanMessage, SystemMessage

        from hadaq_agents.graph import _chat_model, text_of

        reply = _chat_model(model).invoke([SystemMessage(REWORD_PROMPT), HumanMessage(json.dumps(
            {"summary_ar": summary_ar, "summary_en": summary_en}, ensure_ascii=False))])
        text = text_of(reply).strip()
        out = json.loads(text[text.find("{"): text.rfind("}") + 1])
        new_ar, new_en = out["summary_ar"].strip(), out["summary_en"].strip()
    except Exception as exc:  # noqa: BLE001 - any LLM failure keeps the template
        log.warning("Summary rewording failed (%s); keeping the template", type(exc).__name__)
        return summary_ar, summary_en, f"template (LLM failed: {type(exc).__name__})"
    for old, new, closing in ((summary_ar, new_ar, CLOSING_AR), (summary_en, new_en, CLOSING_EN)):
        unverified, _ = verify(new, [old])
        if unverified or not new.endswith(closing) or set(ZONE_RE.findall(old)) - set(ZONE_RE.findall(new)) or "\u2014" in new:
            return summary_ar, summary_en, "template (LLM rewording rejected by the verifier)"
    return new_ar, new_en, f"llm ({model if isinstance(model, str) else type(model).__name__})"


# ---------------------------------------------------------------- the chain


def run_chain(
    capture_id: str,
    findings: list[Finding],
    wall: WallInfo,
    previous_findings: list[Finding] | None = None,
    *,
    detector: str,
    scale: str = "relative",
    px_per_m: float | None = None,
    faces_hidden_server: int = 0,
    llm: Any = None,
) -> CaptureResults:
    previous = list(previous_findings or [])
    out: list[Finding] = []
    for raw in findings:
        if raw.capture_id != capture_id or raw.wall_id != wall.id or raw.building_id != wall.building_id:
            raise ValueError(f"finding {raw.finding_id} is not from capture {capture_id} on {wall.building_id}-{wall.id}")
        f = raw.model_copy(deep=True)
        mark_change(f, previous)
        f.lookalike = LA.lookalike_for(f)
        f.priority = prioritize(f, wall)
        out.append(f)

    orders: list[WorkOrderDraft] = []
    withheld: list[str] = []
    for f in out:
        if f.suspected:  # referred to the specialist: no treatment
            continue
        order = work_order_for(f)
        if order is None:
            withheld.append(f"{f.finding_id}: no library row for {f.effective_type}")
            continue
        problems = check_work_order(order, KB.types()[order.treatment_id]["source"])
        if problems:
            withheld.append(f"{f.finding_id}: " + ", ".join(problems))
            continue
        orders.append(order)
    orders.sort(key=lambda o: ESCALATION_ORDER[o.escalation])
    for line in withheld:
        log.warning("Work order withheld: %s", line)

    summary_ar, summary_en = template_summary(out, orders, withheld, wall)
    summary_ar, summary_en, mode = reword(summary_ar, summary_en, llm)
    return CaptureResults(
        capture_id=capture_id,
        scale=scale,
        px_per_m=px_per_m,
        faces_hidden_server=faces_hidden_server,
        findings=out,
        work_orders=orders,
        summary_ar=summary_ar,
        summary_en=summary_en,
        detector=detector,
        chain=f"{CHAIN_ID}; summary: {mode}",
    )


def process(request: ProcessRequest, findings: list[Finding], *, detector: str, scale: str = "relative",
            px_per_m: float | None = None, faces_hidden_server: int = 0, llm: Any = None) -> CaptureResults:
    """What the AI worker calls after detection: wall and previous findings come from the core's ProcessRequest."""
    return run_chain(request.capture_id, findings, request.wall, request.previous_findings, detector=detector, scale=scale,
                     px_per_m=px_per_m, faces_hidden_server=faces_hidden_server, llm=llm)


# ---------------------------------------------------------------- for the chat tool


def brief(inspection: Inspection) -> dict[str, Any]:
    """A compact view of one inspection for the chat (get_latest_inspection): every number it may quote, with IDs."""
    findings = sorted(inspection.findings, key=lambda f: (ESCALATION_ORDER[f.priority.escalation] if f.priority else 9,
                                                         -(f.priority.score if f.priority else 0)))
    return {
        "inspection_id": inspection.inspection_id,
        "survey_id": inspection.survey_id,
        "building_id": inspection.building_id,
        "wall_id": inspection.wall_id,
        "started_at": inspection.started_at.isoformat(),
        "status": inspection.status,
        "approved_by": inspection.approved_by,
        "pending_review": sum(f.review.status == "pending" for f in inspection.findings),
        "findings": [{
            "finding_id": f.finding_id,
            "zone": _short(f.zone_id),
            "damage_type": f.effective_type,
            "name_ar": DAMAGE_NAMES_AR.get(f.effective_type, f.effective_type),
            "name_en": _name_en(f.effective_type),
            "severity": f.effective_severity,
            "severity_ar": SEVERITY_NAMES_AR[f.effective_severity],
            "suspected": f.suspected,
            "change": f.change,
            "escalation": f.priority.escalation if f.priority else None,
            "score": f.priority.score if f.priority else None,
            "reason_ar": f.priority.reason_ar if f.priority else None,
            "reason_en": f.priority.reason_en if f.priority else None,
            "lookalike": f.lookalike.model_dump() if f.lookalike else None,
            "review": f.review.status,
            "provenance": f.provenance.value,
        } for f in findings],
        "work_orders": [{
            "work_order_id": o.work_order_id,
            "treatment_id": o.treatment_id,
            "zones": [_short(z) for z in o.location.zone_ids],
            "escalation": o.escalation,
            "status": o.status,
            "citation_status": o.citation.status,
        } for o in inspection.work_orders],
        "note": "Model output and ASSUMED priority rules; work orders are drafts that need a field check. " + CLOSING_EN,
    }
