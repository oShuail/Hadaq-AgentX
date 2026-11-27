"""Sensor health and trends (owner: Turki, with Saleh). Pure functions over stored readings, used by the API routes.

Each sensor gets a status the web app and the agents can trust:

    ok        recent, plausible, varying readings
    suspect   the device says "suspect", the value is outside the plausible range for its kind, or it is stuck: the
              same value FLATLINE_N times in a row while another sensor of the same kind on the wall is moving
              (a stuck sensor usually still says "ok"; a calm wall where everything is flat is not flagged)
    fault     the device itself reported a fault
    stale     no reading for STALE_AFTER_S seconds (sensor or device offline, battery, radio)
    no_data   never reported

The thresholds are ASSUMED for the demo cadence (one reading every 2 s); tune them for real nodes.
"""

from __future__ import annotations

import os
import statistics
from datetime import datetime, timezone

STALE_AFTER_S = float(os.getenv("SENSOR_STALE_S", "30"))
FLATLINE_N = 12
PLAUSIBLE = {
    "moisture": (0.0, 100.0),
    "temperature": (-10.0, 60.0),
    "humidity": (0.0, 100.0),
    "crack_width": (0.0, 20.0),
    "tilt": (-10.0, 10.0),
}
# Smallest change over the window that counts as a trend rather than noise.
TREND_MIN_DELTA = {"moisture": 2.0, "temperature": 1.0, "humidity": 3.0, "crack_width": 0.03, "tilt": 0.005}


def _ts(reading: dict) -> datetime:
    ts = datetime.fromisoformat(reading["ts"])
    return ts if ts.tzinfo else ts.replace(tzinfo=timezone.utc)


def plausible(kind: str, value: float) -> bool:
    lo, hi = PLAUSIBLE.get(kind, (float("-inf"), float("inf")))
    return lo <= value <= hi


def usable(reading: dict) -> bool:
    return reading.get("quality", "ok") == "ok" and plausible(reading["kind"], reading["value"])


def is_flat(history: list[dict]) -> bool:
    """The last FLATLINE_N readings (newest first) all have the same value."""
    recent = history[:FLATLINE_N]
    return len(recent) == FLATLINE_N and len({r["value"] for r in recent}) == 1


def peers_moving(kind: str, since: datetime, peers: list[list[dict]]) -> str | None:
    """sensor_id of another sensor of the same kind that changed noticeably since `since`, if any."""
    for history in peers:
        values = [r["value"] for r in history if usable(r) and _ts(r) >= since]
        if len(values) >= 2 and max(values) - min(values) >= TREND_MIN_DELTA.get(kind, 0.0):
            return history[0]["sensor_id"]
    return None


def health(spec: dict, history: list[dict], now: datetime | None = None,
           peers: list[list[dict]] | None = None) -> tuple[str, list[str]]:
    """Status and human-readable reasons. history: newest first. peers: histories of the other sensors of the same
    kind on the building (newest first), used to tell a stuck sensor from a calm wall."""
    if not history:
        return "no_data", ["no reading yet"]
    now = now or datetime.now(timezone.utc)
    latest = history[0]
    status, reasons = "ok", []
    unit = spec.get("unit", latest.get("unit", ""))
    if latest.get("quality") == "fault":
        status, reasons = "fault", ["the device reported a fault"]
    elif not plausible(latest["kind"], latest["value"]):
        lo, hi = PLAUSIBLE[latest["kind"]]
        status, reasons = "suspect", [f"value {latest['value']} {unit} is outside the plausible range {lo:g} to {hi:g}"]
    elif latest.get("quality") == "suspect":
        status, reasons = "suspect", ["the device marked the reading as suspect"]
    elif peers and is_flat(history):
        moving = peers_moving(latest["kind"], _ts(history[FLATLINE_N - 1]), peers)
        if moving:
            status, reasons = "suspect", [f"the same value for {FLATLINE_N} readings in a row while {moving} is changing (possibly stuck)"]
    age = (now - _ts(latest)).total_seconds()
    if age > STALE_AFTER_S:
        status = "stale"
        reasons = [f"no reading for {int(age)} s", *reasons]
    return status, reasons


def window_stats(spec: dict, history: list[dict]) -> dict | None:
    """Trend over the readings in `history` (newest first), using only usable readings."""
    good = [r for r in history if usable(r)]
    if not good:
        return None
    first, last = good[-1], good[0]
    values = [r["value"] for r in good]
    minutes = max((_ts(last) - _ts(first)).total_seconds() / 60, 1e-9)
    change = last["value"] - first["value"]
    min_delta = TREND_MIN_DELTA.get(spec.get("kind", last["kind"]), 0.0)
    trend = "steady"
    if len(good) >= 3 and abs(change) >= min_delta:
        trend = "rising" if change > 0 else "falling"
    digits = 3 if last["kind"] in ("crack_width", "tilt") else 1
    lowest = min(good, key=lambda r: r["value"])
    highest = max(good, key=lambda r: r["value"])
    return {
        "readings": len(good),
        "skipped": len(history) - len(good),
        "from_ts": first["ts"],
        "to_ts": last["ts"],
        "first": first["value"],
        "last": last["value"],
        "min": lowest["value"],
        "max": highest["value"],
        "mean": round(statistics.fmean(values), digits),
        "change": round(change, digits),
        "rate_per_min": round(change / minutes, digits + 1) if len(good) > 1 else 0.0,
        "trend": trend,
        "first_evidence_id": first.get("evidence_id"),
        "last_evidence_id": last.get("evidence_id"),
        "max_evidence_id": highest.get("evidence_id"),
    }


def overview(spec: dict, history: list[dict], now: datetime | None = None, peers: list[list[dict]] | None = None) -> dict:
    """Everything the UI and the agents need about one sensor. history: newest first, window already applied."""
    now = now or datetime.now(timezone.utc)
    status, reasons = health(spec, history, now, peers)
    latest = history[0] if history else None
    return {
        "sensor_id": spec["id"],
        "label": spec.get("label"),
        "kind": spec["kind"],
        "unit": spec["unit"],
        "zone_id": spec["zone_id"],
        "device_id": spec.get("device_id"),
        "status": status,
        "reasons": reasons,
        "latest": None if latest is None else {
            "value": latest["value"],
            "ts": latest["ts"],
            "age_s": round((now - _ts(latest)).total_seconds(), 1),
            "quality": latest.get("quality", "ok"),
            "provenance": latest.get("provenance"),
            "evidence_id": latest.get("evidence_id"),
        },
        "window": window_stats(spec, history),
    }


def downsample(history: list[dict], points: int) -> list[dict]:
    """Oldest first, at most `points` readings evenly spread (keeps the newest)."""
    ordered = list(reversed(history))
    if points <= 0 or len(ordered) <= points:
        picked = ordered
    else:
        step = (len(ordered) - 1) / (points - 1)
        picked = [ordered[round(i * step)] for i in range(points)]
    return [{k: r.get(k) for k in ("ts", "value", "quality", "evidence_id")} for r in picked]
