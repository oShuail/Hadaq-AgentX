"""Change detection v0: compare this photo's detections with the previous photo's, per zone and class.

Works because the camera is fixed. Round 2: register photos first (LightGlue/LoFTR) and compare on the mesh.
"""

from __future__ import annotations

GROWTH_THRESHOLD_PCT = 10.0


def _size(d: dict) -> float:
    return float(d.get("length_px") or d.get("area_px") or 0.0)


def label_changes(current: list[dict], previous: list[dict]) -> list[dict]:
    """Adds 'change' and 'growth_pct' to each current detection (dicts with zone_id, cls, length_px/area_px)."""
    before = {}
    for d in previous:
        key = (d["zone_id"], d["cls"])
        before[key] = max(before.get(key, 0.0), _size(d))
    for d in current:
        old = before.get((d["zone_id"], d["cls"]))
        if not old:
            d["change"], d["growth_pct"] = "new", None
            continue
        growth = (_size(d) - old) / old * 100.0
        d["growth_pct"] = round(growth, 1)
        d["change"] = "grown" if growth >= GROWTH_THRESHOLD_PCT else "unchanged"
    return current
