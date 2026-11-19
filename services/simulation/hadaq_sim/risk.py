"""Risk per zone with an adapted ABC method (ICCROM / CCI).

ABC splits a risk into three log-scale scores (each step = x10):
- A: how often the damaging event happens.      A = 5 means about once a year.
- B: share of value lost in each affected part.  B = 5 means total loss, 4 = 10%, 3 = 1%, 2 = 0.1%.
- C: share of the asset's value affected.        C = 5 means the whole asset.
Magnitude = A + B + C (max 15).

Our adaptation (ASSUMED, to review with a conservator):
- A from heavy-rain days per year at the site (registry: site.heavy_rain_days_per_year).
- B from the simulation (peak moisture, hours above threshold) and vision (crack growth).
- C from each zone's value weight versus the whole wall.
- Level bands below are ours, not from the ABC manual.
"""

from __future__ import annotations

import math

from hadaq_schemas import Detection, RiskScore, SimulationResult


def _log_score(fraction: float) -> float:
    """Map a fraction (or rate per year) to the ABC log scale and clamp to [0.5, 5]."""
    if fraction <= 0:
        return 0.5
    return round(min(5.0, max(0.5, 5.0 + math.log10(fraction))), 2)


def score_A(heavy_rain_days_per_year: float) -> float:
    return _log_score(heavy_rain_days_per_year)


def loss_fraction(peak: float, hours_above: int, threshold: float, crack_growth_pct: float | None) -> float:
    """ASSUMED mapping from model outputs to expected loss of value per event."""
    fraction = 0.0001
    if peak >= threshold:
        fraction = 0.003
    if hours_above >= 12:
        fraction = 0.01
    if hours_above >= 36:
        fraction = 0.03
    if crack_growth_pct and crack_growth_pct > 10:
        fraction *= 3
    return min(fraction, 1.0)


def level_for(magnitude: float) -> str:
    if magnitude >= 12:
        return "very_high"
    if magnitude >= 11:
        return "high"
    if magnitude >= 10:
        return "medium"
    return "low"


def score_building(
    sim: SimulationResult,
    detections: list[Detection],
    heavy_rain_days_per_year: float,
    zone_weights: dict[str, float] | None = None,
    default_weight: float = 3.0,
) -> list[RiskScore]:
    weights = {z: (zone_weights or {}).get(z, default_weight) for z in sim.summary}
    total_weight = sum(weights.values()) or 1.0
    growth_by_zone: dict[str, float] = {}
    det_ids_by_zone: dict[str, list[str]] = {}
    for det in detections:
        det_ids_by_zone.setdefault(det.zone_id, []).append(det.detection_id)
        if det.growth_pct is not None:
            growth_by_zone[det.zone_id] = max(growth_by_zone.get(det.zone_id, 0.0), det.growth_pct)

    a = score_A(heavy_rain_days_per_year)
    scores: list[RiskScore] = []
    for zid, summary in sim.summary.items():
        b = _log_score(loss_fraction(summary.peak_moisture, summary.hours_above_threshold, sim.threshold, growth_by_zone.get(zid)))
        c = _log_score(weights[zid] / total_weight)
        magnitude = round(a + b + c, 2)
        scores.append(
            RiskScore(
                building_id=sim.building_id,
                zone_id=zid,
                A=a,
                B=b,
                C=c,
                magnitude=magnitude,
                level=level_for(magnitude),
                evidence_ids=[sim.run_id, *det_ids_by_zone.get(zid, [])],
            )
        )
    scores.sort(key=lambda s: s.magnitude, reverse=True)
    return scores
