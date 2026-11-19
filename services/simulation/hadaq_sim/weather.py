"""Weather inputs for the simulation.

- fetch_hourly(): real hourly history from the Open-Meteo archive (ERA5 reanalysis, provenance "measured")
- find_wettest_days(): pick a real past storm for the replay
- synthetic_storm(): an artificial storm for tests and offline demos (provenance "synthetic")
"""

from __future__ import annotations

import json
import math
import os
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import httpx

from hadaq_schemas import WeatherSpec

OPEN_METEO_ARCHIVE = "https://archive-api.open-meteo.com/v1/archive"
HOURLY_VARS = "temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,wind_direction_10m"
RIYADH_TZ = timezone(timedelta(hours=3))
CACHE_DIR = Path(os.getenv("HADAQ_WEATHER_CACHE", "./data/weather_cache"))


@dataclass
class WeatherHour:
    time: datetime
    rain_mm: float
    wind_ms: float
    wind_dir_deg: float  # direction the wind comes FROM, degrees from north
    temp_c: float
    rh: float  # relative humidity, %


def fetch_hourly(lat: float, lon: float, start: date, end: date, timeout: float = 30.0) -> list[WeatherHour]:
    """Hourly weather history from Open-Meteo (free, no API key). Times are Riyadh local (+03:00)."""
    params = {
        "latitude": lat,
        "longitude": lon,
        "start_date": start.isoformat(),
        "end_date": end.isoformat(),
        "hourly": HOURLY_VARS,
        "wind_speed_unit": "ms",
        "timezone": "Asia/Riyadh",
    }
    response = httpx.get(OPEN_METEO_ARCHIVE, params=params, timeout=timeout)
    response.raise_for_status()
    hourly = response.json()["hourly"]
    hours: list[WeatherHour] = []
    for i, stamp in enumerate(hourly["time"]):
        hours.append(
            WeatherHour(
                time=datetime.fromisoformat(stamp).replace(tzinfo=RIYADH_TZ),
                rain_mm=float(hourly["precipitation"][i] or 0.0),
                wind_ms=float(hourly["wind_speed_10m"][i] or 0.0),
                wind_dir_deg=float(hourly["wind_direction_10m"][i] or 0.0),
                temp_c=float(hourly["temperature_2m"][i] or 20.0),
                rh=float(hourly["relative_humidity_2m"][i] or 30.0),
            )
        )
    return hours


def fetch_hourly_cached(lat: float, lon: float, start: date, end: date) -> list[WeatherHour]:
    """Same as fetch_hourly, but keeps a JSON copy so the demo works offline after the first run."""
    path = CACHE_DIR / f"{lat:.4f}_{lon:.4f}_{start}_{end}.json"
    if path.exists():
        rows = json.loads(path.read_text(encoding="utf-8"))
        return [WeatherHour(**{**r, "time": datetime.fromisoformat(r["time"])}) for r in rows]
    hours = fetch_hourly(lat, lon, start, end)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps([{**asdict(h), "time": h.time.isoformat()} for h in hours]), encoding="utf-8")
    return hours


def heavy_rain_days_per_year(lat: float, lon: float, start: date, end: date, threshold_mm: float = 10.0) -> float:
    """Days per year with at least threshold_mm of rain (threshold ASSUMED). Feeds the ABC 'A' score."""
    totals: dict[date, float] = defaultdict(float)
    for hour in fetch_hourly(lat, lon, start, end):
        totals[hour.time.date()] += hour.rain_mm
    years = max((end - start).days / 365.25, 1.0)
    return round(sum(1 for t in totals.values() if t >= threshold_mm) / years, 2)


def find_wettest_days(lat: float, lon: float, start: date, end: date, top: int = 10) -> list[dict]:
    """Rank days by total rain. Use the result to choose the storm we replay in the demo."""
    totals: dict[date, float] = defaultdict(float)
    peaks: dict[date, float] = defaultdict(float)
    for hour in fetch_hourly(lat, lon, start, end):
        day = hour.time.date()
        totals[day] += hour.rain_mm
        peaks[day] = max(peaks[day], hour.rain_mm)
    ranked = sorted(totals.items(), key=lambda item: item[1], reverse=True)[:top]
    return [{"date": d.isoformat(), "total_mm": round(t, 1), "max_mm_h": round(peaks[d], 1)} for d, t in ranked]


def synthetic_storm(
    start: datetime | None = None,
    hours: int = 72,
    peak_mm_h: float = 12.0,
    wind_ms: float = 8.0,
    wind_dir_deg: float = 270.0,
    rain_hours: float = 10.0,
    temp_c: float = 18.0,
) -> list[WeatherHour]:
    """Bell-shaped rain burst starting at hour 6, then clear drying weather. For tests and offline demos."""
    start = start or datetime.now(RIYADH_TZ).replace(minute=0, second=0, microsecond=0)
    center = 6 + rain_hours / 2
    width = rain_hours / 4
    out: list[WeatherHour] = []
    for h in range(hours):
        rain = peak_mm_h * math.exp(-0.5 * ((h - center) / width) ** 2)
        rain = rain if rain > 0.05 else 0.0
        daily = math.sin(2 * math.pi * ((start.hour + h) % 24 - 9) / 24)
        raining = rain > 0
        out.append(
            WeatherHour(
                time=start + timedelta(hours=h),
                rain_mm=round(rain, 2),
                wind_ms=wind_ms if raining else max(2.0, wind_ms * 0.4),
                wind_dir_deg=wind_dir_deg,
                temp_c=round(temp_c + (2 if raining else 7) * daily, 1),
                rh=round(85.0 if raining else max(15.0, 40.0 - 15 * daily), 1),
            )
        )
    return out


def weather_for_spec(spec: WeatherSpec, lat: float, lon: float) -> tuple[list[WeatherHour], str]:
    """Turn a WeatherSpec into hourly weather. Returns (hours, source description)."""
    if spec.mode == "replay":
        if not spec.date:
            raise ValueError("WeatherSpec.date is required for replay")
        day = date.fromisoformat(spec.date)
        days = max(1, math.ceil(spec.hours / 24))
        hours = fetch_hourly_cached(lat, lon, day, day + timedelta(days=days - 1))[: spec.hours]
        source = f"Open-Meteo archive (ERA5), {spec.date}, {lat},{lon}"
    else:
        hours = synthetic_storm(
            hours=spec.hours, peak_mm_h=spec.peak_rain_mm_h, wind_ms=spec.wind_ms, wind_dir_deg=spec.wind_dir_deg
        )
        source = f"synthetic storm, peak {spec.peak_rain_mm_h} mm/h, wind {spec.wind_ms} m/s from {spec.wind_dir_deg} deg"
    if spec.rain_multiplier != 1.0:
        for hour in hours:
            hour.rain_mm *= spec.rain_multiplier
        source += f", rain x{spec.rain_multiplier}"
    return hours, source
