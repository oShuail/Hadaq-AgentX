"""Monitoring rules on live sensor readings (owner: Basma, called by the API on every reading).

Rule v0 for moisture (%rel, 0..100):
- fires if the value is above ABS_THRESHOLD, or
- fires if it rose faster than RISE_PCT_PER_H compared with the reading about an hour earlier.
Both thresholds are ASSUMED. Tune them on the sensor simulator output so the demo storm triggers once, not constantly.
"""

from __future__ import annotations

from datetime import datetime, timedelta

ABS_THRESHOLD = 60.0
RISE_PCT_PER_H = 8.0


def detect_moisture_anomaly(
    history: list[tuple[datetime, float]],
    abs_threshold: float = ABS_THRESHOLD,
    rise_pct_per_h: float = RISE_PCT_PER_H,
) -> dict | None:
    """history: (timestamp, value) pairs, any order. Returns details if anomalous, else None."""
    if not history:
        return None
    points = sorted(history, key=lambda p: p[0])
    latest_ts, latest = points[-1]
    if latest >= abs_threshold:
        return {"rule": "absolute", "value": latest, "threshold": abs_threshold}
    window_start = latest_ts - timedelta(hours=1)
    earlier = [p for p in points if p[0] <= window_start] or points[:1]
    ref_ts, ref_value = earlier[-1]
    hours = max((latest_ts - ref_ts).total_seconds() / 3600.0, 1 / 60)
    rate = (latest - ref_value) / hours
    if rate >= rise_pct_per_h and hours >= 0.25:
        return {"rule": "rate", "value": latest, "rate_pct_per_h": round(rate, 2), "threshold": rise_pct_per_h}
    return None
