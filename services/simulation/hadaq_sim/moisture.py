"""bucket_v0: a simple, transparent wall moisture model.

Each wall zone (4 rows x 8 columns) is a "bucket" of water storage. Every hour:

1. Wind-driven rain hits the facade (ASHRAE 160 form):  r_bv = F_E * F_D * F_L * U * cos(theta) * r_h
   theta = angle between where the wind comes from and the direction the facade faces.
2. Each zone absorbs what it can (less when already wet). The rest runs DOWN to the zone below.
3. The base row (R1) also gets ground splash, and water that reaches the ground pools at the wall foot.
   The pool drains slowly and keeps feeding the base by capillary rise after the rain stops.
   This is why earthen walls typically fail at the base, and why better drainage helps.
4. Zones dry depending on air dryness (vapour pressure deficit) and wind; the base dries slower.
5. An erosion index accumulates: water hitting the surface x wind x wetness.

Every parameter below is ASSUMED (literature-scale guesses, not tested on At-Turaif adobe).
This is a demo model, not a validated hygrothermal simulation. Round 2 replaces it with a
1D liquid + vapour transport solver (see the architecture doc, section 5).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from hadaq_schemas import SimulationRequest, SimulationResult, ZoneSummary, parse_zone_id, zone_id
from hadaq_sim.weather import WeatherHour

ENGINE = "bucket_v0"
F_L = 0.2  # ASHRAE 160 empirical constant, kg.s/(m3.mm)


@dataclass
class WallParams:
    azimuth_deg: float = 270.0  # direction the facade faces (outward), degrees from north
    rows: int = 4
    cols: int = 8
    exposure_fe: float = 1.0  # ASSUMED: open, low-rise site
    row_deposition_fd: tuple[float, ...] = (0.35, 0.45, 0.6, 1.0)  # ASSUMED: R1 (base) .. R4 (top)
    edge_col_factor: float = 1.2  # ASSUMED: corners catch more rain
    absorb_capacity_mm_h: float = 2.0  # ASSUMED: water a dry surface takes in per hour
    storage_mm: float = 25.0  # ASSUMED: water a zone holds at full saturation (m = 1)
    splash_factor: float = 0.15  # ASSUMED: share of horizontal rain splashing onto the base row
    catchment_factor: float = 1.0  # ASSUMED: mm of ground water at the foot per mm of rain (paving slope, roof spouts)
    pond_drain_mm_h: float = 0.6  # ASSUMED: how fast the pool at the foot drains away
    pond_max_mm: float = 40.0  # ASSUMED: deepest pool before it overflows elsewhere
    capillary_mm_h: float = 0.8  # ASSUMED: max uptake from the pool into the base row
    base_drying_factor: float = 0.5  # ASSUMED: base dries at half speed (ground contact, shade)
    drying_rate: float = 0.02  # ASSUMED: fraction lost per hour at 1 kPa VPD, calm air
    initial_moisture: float = 0.15  # ASSUMED: dry-season starting state
    threshold: float = 0.6  # ASSUMED: moisture fraction we treat as critical
    assumptions: list[str] = field(default_factory=list)

    def describe(self) -> list[str]:
        return [
            f"Facade faces {self.azimuth_deg:.0f} deg; zones {self.rows}x{self.cols} (R1 = base).",
            f"Exposure F_E={self.exposure_fe}, deposition by row {self.row_deposition_fd}, corners x{self.edge_col_factor}.",
            f"Absorption {self.absorb_capacity_mm_h} mm/h, storage {self.storage_mm} mm, splash {self.splash_factor}.",
            f"Pool at the foot: catchment x{self.catchment_factor}, drains {self.pond_drain_mm_h} mm/h, capillary rise up to {self.capillary_mm_h} mm/h.",
            f"Drying {self.drying_rate}/h per kPa VPD; start moisture {self.initial_moisture}; critical {self.threshold}.",
            "All parameters ASSUMED; not calibrated on At-Turaif adobe.",
            *self.assumptions,
        ]


def _vpd_kpa(temp_c: float, rh: float) -> float:
    saturation = 0.6108 * math.exp(17.27 * temp_c / (temp_c + 237.3))
    return max(0.0, saturation * (1 - rh / 100.0))


def _facing_factor(wind_from_deg: float, facade_azimuth_deg: float) -> float:
    """cos(theta) when the wind blows onto the facade, else 0."""
    diff = math.radians(wind_from_deg - facade_azimuth_deg)
    return max(0.0, math.cos(diff))


def apply_scenario(params: WallParams, scenario: str) -> WallParams:
    """Interventions and what-ifs are boundary changes, not new physics."""
    if scenario == "drainage_fixed":
        params.splash_factor *= 0.5
        params.catchment_factor *= 0.3
        params.pond_drain_mm_h *= 5
        params.assumptions.append(
            "Scenario drainage_fixed: graded ground and a drain at the foot; splash halved, pooling cut by 70%, drains 5x faster (ASSUMED effect)."
        )
    return params


def run_simulation(
    request: SimulationRequest,
    weather: list[WeatherHour],
    params: WallParams | None = None,
    weather_source: str = "unknown",
) -> SimulationResult:
    params = apply_scenario(params or WallParams(), request.scenario)
    rain_scale = 2.0 if request.scenario == "rain_x2" else 1.0
    if rain_scale != 1.0:
        params.assumptions.append("Scenario rain_x2: every hour of rain doubled.")

    rows, cols = params.rows, params.cols
    moisture = [[params.initial_moisture] * cols for _ in range(rows)]  # moisture[row_index][col_index], row 0 = R1
    erosion = [[0.0] * cols for _ in range(rows)]
    pond = [0.0] * cols  # water pooled at the foot of each column, mm
    series: dict[str, list[float]] = {zone_id(request.building_id, request.wall_id, r + 1, c + 1): [] for r in range(rows) for c in range(cols)}

    for hour in weather:
        r_h = hour.rain_mm * rain_scale
        drive = params.exposure_fe * F_L * hour.wind_ms * _facing_factor(hour.wind_dir_deg, params.azimuth_deg) * r_h
        vpd = _vpd_kpa(hour.temp_c, hour.rh)
        for c in range(cols):
            col_factor = params.edge_col_factor if c in (0, cols - 1) else 1.0
            runoff = 0.0
            for r in range(rows - 1, -1, -1):  # top row first, water runs down
                incoming = drive * params.row_deposition_fd[r] * col_factor + runoff
                if r == 0:
                    incoming += params.splash_factor * r_h
                room = params.absorb_capacity_mm_h * (1.0 - moisture[r][c])
                absorbed = min(incoming, room)
                runoff = incoming - absorbed
                moisture[r][c] += absorbed / params.storage_mm
                erosion[r][c] += incoming * math.sqrt(max(hour.wind_ms, 0.1)) * moisture[r][c]
            # Water reaching the ground pools at the foot, then feeds the base row by capillary rise.
            pond[c] = min(params.pond_max_mm, pond[c] + params.catchment_factor * r_h + runoff)
            uptake = min(pond[c], params.capillary_mm_h * (1.0 - moisture[0][c]))
            moisture[0][c] += uptake / params.storage_mm
            pond[c] = max(0.0, pond[c] - uptake - params.pond_drain_mm_h - 0.1 * vpd)
            for r in range(rows):
                dry = params.drying_rate * vpd * (1 + 0.1 * hour.wind_ms) * moisture[r][c]
                moisture[r][c] -= dry * (params.base_drying_factor if r == 0 else 1.0)
                moisture[r][c] = min(1.0, max(0.0, moisture[r][c]))
        for r in range(rows):
            for c in range(cols):
                series[zone_id(request.building_id, request.wall_id, r + 1, c + 1)].append(round(moisture[r][c], 4))

    summary = {}
    for zid, values in series.items():
        _, _, row, col = parse_zone_id(zid)
        summary[zid] = ZoneSummary(
            peak_moisture=round(max(values) if values else params.initial_moisture, 4),
            hours_above_threshold=sum(1 for v in values if v >= params.threshold),
            erosion_index=round(erosion[row - 1][col - 1], 3),
        )

    return SimulationResult(
        building_id=request.building_id,
        wall_id=request.wall_id,
        scenario=request.scenario,
        engine=ENGINE,
        times=[hour.time for hour in weather],
        zones=series,
        summary=summary,
        threshold=params.threshold,
        assumptions=params.describe(),
        weather_source=weather_source,
    )
