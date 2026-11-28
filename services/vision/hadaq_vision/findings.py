"""RawDetection on the orthophoto -> contract v2 Finding (wall metres, zone, severity)."""
from __future__ import annotations

from hadaq_schemas import Finding, zone_from_wall_m
from hadaq_schemas.inspection import box_center
from hadaq_vision.detect import RawDetection
from hadaq_vision.rectify import Rectified


# MockDetector still speaks the old 5 classes; the real model speaks the glossary ids.
def glossary_type(cls: str, row: int, rows: int) -> str:
    legacy = {
        "erosion": "base_erosion" if row == 1 else "rain_grooves",
        "missing_material": "voids_burrows",
        "moisture_stain": "salt_efflorescence",
    }
    return legacy.get(cls, cls)


def severity(damage_type: str, length_cm: float | None, area_m2: float, zone_area_m2: float) -> int:
    """ASSUMED thresholds until DGDA's glossary gives real ones. One place to change them."""
    if damage_type == "crack" and length_cm is not None:
        return 1 if length_cm < 30 else (2 if length_cm < 100 else 3)
    share = area_m2 / zone_area_m2 if zone_area_m2 else 0
    return 1 if share < 0.05 else (2 if share < 0.20 else 3)


def to_findings(raw: list[RawDetection], rect: Rectified, wall: dict, building_id: str, capture_id: str, model: str,
                synthetic: bool) -> list[Finding]:
    rows, cols = wall["rows"], wall["cols"]
    zone_area = (rect.width_m / cols) * (rect.height_m / rows)
    out = []
    for d in raw:
        box_m = rect.box_px_to_wall_m(d.bbox_xyxy)
        u, v = box_center(box_m)
        row, col = zone_from_wall_m(u, v, rect.width_m, rect.height_m, rows, cols)
        dtype = glossary_type(d.cls, row, rows)
        area_m2 = round((d.area_px or 0) / rect.px_per_m ** 2, 3)
        length_cm = round((d.length_px or 0) / rect.px_per_m * 100, 1) if dtype == "crack" else None
        out.append(Finding(
            capture_id=capture_id, building_id=building_id, wall_id=wall["id"],
            zone_id=f"{building_id}-{wall['id']}-R{row}C{col}",
            damage_type=dtype, severity=severity(dtype, length_cm, area_m2, zone_area),
            confidence=round(d.confidence, 3), box_px=[round(x, 1) for x in d.bbox_xyxy], box_wall_m=box_m,
            length_cm=length_cm, area_m2=area_m2, model=model,
            provenance="synthetic" if synthetic else "model_output",
            evidence_ids=[capture_id],
        ))
    return out


def label_change(findings: list[Finding], previous: list[Finding]) -> None:
    """new / grown / unchanged against the same zone and type in earlier captures of this wall."""
    before: dict[tuple[str, str], Finding] = {}
    for p in previous:
        key = (p.zone_id, p.review.corrected_type or p.damage_type)
        if p.review.status != "rejected" and key not in before:
            before[key] = p
    for f in findings:
        old = before.get((f.zone_id, f.damage_type))
        if not old:
            f.change = "new"
            continue
        f.previous_finding_id = old.finding_id
        size, old_size = (f.length_cm or f.area_m2 or 0), (old.length_cm or old.area_m2 or 0)
        f.change = "grown" if old_size and (size - old_size) / old_size >= 0.10 else "unchanged"