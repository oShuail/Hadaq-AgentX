"""Priority and escalation for one finding: a stand-in until Basma's B2 (hadaq_agents/priority.py) lands.

The criteria and their order are DGDA's (Q&A Q4): safety, active damage, water before the rainy season, severity and
area, value of the element, location. The four escalation levels are DGDA's proposal (Q14): urgent, high, medium, low.
Everything numeric here (how each criterion is scored 0..1, the weights, the escalation rules) is OURS and ASSUMED.

B2 replaces this module by changing one import in chain.py. It must keep this signature:

    prioritize(finding: Finding, wall: WallInfo) -> Priority
"""

from __future__ import annotations

from hadaq_schemas.inspection import (
    DAMAGE_NAMES_AR,
    SEVERITY_NAMES_AR,
    Finding,
    Priority,
    PriorityCriteria,
    WallInfo,
)

from hadaq_agents import knowledge as KB

METHOD = "DGDA criteria (Q4) and levels (Q14); scores, weights and rules ASSUMED (hadaq_agents.priority_fallback)"

# Weights in DGDA's order of criteria. ASSUMED.
WEIGHTS = {"safety": 30, "active": 20, "water_before_rain": 15, "severity_x_area": 15, "value": 10, "location": 10}

STABILITY_TYPES = {"structural_deformation", "base_erosion", "crack"}  # Q14: threat to structural stability
FALL_RISK_TYPES = {"plaster_loss", "parapet_loss", "crack", "base_erosion"}  # Q14: parts that can fall on visitors
WATER_TYPES = {"base_erosion", "rain_grooves", "salt_efflorescence", "biological_growth"}  # Q4: water-related damage
ACTIVE_BY_CHANGE = {"grown": 1.0, "new": 0.5, None: 0.5, "unchanged": 0.0, "repaired": 0.0}  # unknown counts as maybe
VALUE_BY_FABRIC = {"original": 1.0, "mixed": 0.6, "unknown": 0.5, "restored": 0.2}
SEVERITY_EN = {1: "light", 2: "moderate", 3: "severe"}


def criteria(finding: Finding, wall: WallInfo) -> PriorityCriteria:
    kind, sev = finding.effective_type, finding.effective_severity
    if kind == "structural_deformation" or (kind in STABILITY_TYPES and sev == 3):
        safety = 1.0
    elif kind in STABILITY_TYPES or (kind in FALL_RISK_TYPES and wall.visitor_route):
        safety = 0.5
    else:
        safety = 0.0
    wall_area = wall.width_m * wall.height_m
    share = min(1.0, (finding.area_m2 or 0) / wall_area * 10) if finding.area_m2 and wall_area > 0 else 0.5
    location = 1.0 if wall.visitor_route else 0.7 if wall.main_facade else 0.3
    return PriorityCriteria(
        safety=safety,
        active=ACTIVE_BY_CHANGE.get(finding.change, 0.5),
        water_before_rain=1.0 if kind in WATER_TYPES else 0.0,
        severity_x_area=round(sev / 3 * 0.7 + share * 0.3, 2),
        value=VALUE_BY_FABRIC[wall.original_fabric],
        location=location,
    )


def escalation(finding: Finding, wall: WallInfo, c: PriorityCriteria) -> str:
    """DGDA's Q14 levels. Urgent: safety or stability. High: active damage or an active water source. ASSUMED rules."""
    kind, sev = finding.effective_type, finding.effective_severity
    if c.safety >= 1.0 or (wall.visitor_route and sev == 3 and kind in FALL_RISK_TYPES):
        return "urgent"
    if finding.change == "grown" or (kind in WATER_TYPES and sev >= 2):
        return "high"
    return "medium" if sev >= 2 else "low"


def prioritize(finding: Finding, wall: WallInfo) -> Priority:
    c = criteria(finding, wall)
    score = sum(WEIGHTS[k] * getattr(c, k) for k in WEIGHTS) / sum(WEIGHTS.values()) * 100
    level = escalation(finding, wall, c)
    kind, sev = finding.effective_type, finding.effective_severity
    name_en = KB.types().get(kind, {}).get("name_en", kind.replace("_", " "))
    ar = [f"{DAMAGE_NAMES_AR.get(kind, kind)} (الشدة: {SEVERITY_NAMES_AR[sev]})"]
    en = [f"{SEVERITY_EN[sev].capitalize()} {name_en.lower()}"]
    if c.safety >= 1.0:
        ar.append("يهدد ثبات الجدار")
        en.append("threatens the wall's stability")
    if finding.change == "grown":
        ar.append("نشط ويتسع")
        en.append("active and growing")
    if c.water_before_rain:
        ar.append("مرتبط بالماء، يعالج قبل موسم الأمطار")
        en.append("water-related, treat before the rainy season")
    if wall.visitor_route:
        ar.append("على مسار الزوار")
        en.append("on a visitor route")
    return Priority(
        score=round(score, 1),
        criteria=c,
        escalation=level,
        reason_ar="، ".join(ar),
        reason_en=", ".join(en),
        method=METHOD,
    )
