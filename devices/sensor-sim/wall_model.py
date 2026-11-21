"""The wall the sensor simulator measures (owner: Saleh).

A small, deliberately simple model of one mud-brick facade so the simulated sensors behave like a wall would:

- Rain wets the wall. The base row (R1) gets the most water (splash and runoff), the middle rows the least, and the
  parapet row (R4) a little more again. Water that ponds at the wall foot keeps feeding the base by capillary rise
  for hours after the rain stops, so the base peaks late and dries slowly.
- Drying depends on temperature and humidity; the base dries about three times slower than the upper wall.
- Temperature and humidity follow a Riyadh November day (local time UTC+3) and drop / rise during rain.
- The crack gauge widens slowly while the wall is wet and breathes a little with the daily temperature cycle.
- The inclinometer creeps very slowly once the base is soaked (a wet base carries load worse).

The equations are intentionally NOT the bucket_v0 simulation model (services/simulation), so the "measured" curves
and the simulation are not the same maths (avoids the circularity trap in the architecture doc).

Everything here runs on a simulated clock: `advance(dt_hours)` moves the world forward. The CLI maps real seconds
to simulated hours (default 720x: one day every two minutes) so a storm plays out during a short demo.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

RIYADH = timezone(timedelta(hours=3))

# Share of driving rain each row receives, base row first. Tuned by eye, ASSUMED.
ROW_WETTING = {1: 1.0, 2: 0.55, 3: 0.4, 4: 0.5}
K_RAIN = 0.38  # %rel per hour per mm/h of rain, at a dry wall
K_POND = 0.20  # %rel per hour per mm of ponded water (base row only)
K_DRY = {1: 0.025, 2: 0.06, 3: 0.07, 4: 0.08}  # share of the excess moisture lost per hour at evaporation factor 1
M_SAT = 95.0  # moisture where the wall cannot take more water
POND_MAX = 15.0  # mm of water that can stand at the wall foot
POND_DRAIN = 1.0  # mm per hour soaking away when it is not raining

FAULT_MODES = ("spike", "stuck", "dropout")


@dataclass
class Fault:
    mode: str  # spike: garbage values with quality "fault"; stuck: repeats one value; dropout: sends nothing
    until: float  # real time (time.monotonic() clock of the caller) when the fault clears
    stuck_value: float | None = None


@dataclass
class World:
    sensors: list[dict]
    start: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    seed: int | None = None

    def __post_init__(self) -> None:
        self.rng = random.Random(self.seed)
        # Each sensor sits in a slightly different spot: a fixed +-15% exposure factor per sensor.
        self.exposure = {s["id"]: random.Random(f"{self.seed}-{s['id']}").uniform(0.85, 1.15) for s in self.sensors}
        self.reset()

    # ---------- state ----------

    def reset(self) -> None:
        self.sim_time = self.start
        self.storm = False
        self.storm_left_h: float | None = None
        self.peak_rain = 12.0
        self.rain = 0.0
        self.pond = 0.0
        self.crack_mm = 1.20
        self.tilt_deg = 0.12
        self.faults: dict[str, Fault] = {}
        temp, rh = self.ambient()
        self.moisture = {s["id"]: self._equilibrium(rh) * self.exposure[s["id"]] for s in self.sensors if s["kind"] == "moisture"}

    def on_event(self, event_type: str, payload: dict | None = None, now_real: float = 0.0) -> None:
        """React to a scenario event (the same events the scenario panel sends)."""
        payload = payload or {}
        if event_type == "storm_started":
            self.storm = True
            self.peak_rain = float(min(max(payload.get("peak_rain_mm_h", 12.0), 2.0), 60.0))
            hours = payload.get("hours")
            self.storm_left_h = float(hours) if hours else None
        elif event_type == "storm_ended":
            self.storm = False
            self.storm_left_h = None
        elif event_type == "sensor_fault":
            sensor_id = payload.get("sensor_id", "B01-S04")
            mode = payload.get("mode", "spike")
            if mode not in FAULT_MODES:
                mode = "spike"
            self.faults[sensor_id] = Fault(mode=mode, until=now_real + float(payload.get("duration_s", 45)))
        elif event_type == "new_crack":
            self.crack_mm += float(payload.get("mm", 0.35))
        elif event_type == "demo_reset":
            self.reset()

    # ---------- physics ----------

    def local_hour(self) -> float:
        t = self.sim_time.astimezone(RIYADH)
        return t.hour + t.minute / 60

    def ambient(self) -> tuple[float, float]:
        """Air temperature (degC) and relative humidity (%RH) next to the wall."""
        day = math.sin(2 * math.pi * (self.local_hour() - 9) / 24)  # peaks mid-afternoon
        temp = 22.0 + 7.0 * day
        rh = 32.0 - 14.0 * day
        if self.rain > 0.2:
            wet = min(self.rain / max(self.peak_rain, 1.0), 1.0)
            temp -= 5.0 * wet
            rh += (92.0 - rh) * wet
        return temp, min(max(rh, 5.0), 100.0)

    @staticmethod
    def _equilibrium(rh: float) -> float:
        """Moisture a dry-season wall settles at for a given air humidity."""
        return 10.0 + 0.08 * rh

    def advance(self, dt_hours: float) -> None:
        self.sim_time += timedelta(hours=dt_hours)
        if self.storm and self.storm_left_h is not None:
            self.storm_left_h -= dt_hours
            if self.storm_left_h <= 0:
                self.storm, self.storm_left_h = False, None
        target = self.peak_rain if self.storm else 0.0
        self.rain += (target - self.rain) * min(1.0, dt_hours / 1.5)  # rain builds up and eases off over about 1.5 h
        if self.rain < 0.05:
            self.rain = 0.0
        self.pond = min(POND_MAX, max(0.0, self.pond + (0.6 * self.rain - (0.0 if self.rain > 0.5 else POND_DRAIN)) * dt_hours))

        temp, rh = self.ambient()
        evaporation = min(max((temp - 5.0) / 25.0, 0.2), 1.5) * (1.0 - rh / 110.0)
        equilibrium = self._equilibrium(rh)
        for sensor in self.sensors:
            if sensor["kind"] != "moisture":
                continue
            row = _row(sensor["zone_id"])
            m = self.moisture[sensor["id"]]
            room = max(0.0, 1.0 - m / M_SAT)
            wetting = K_RAIN * self.rain * ROW_WETTING.get(row, 0.5) * self.exposure[sensor["id"]] * room
            if row == 1:
                wetting += K_POND * self.pond * room
            drying = K_DRY.get(row, 0.07) * evaporation * max(0.0, m - equilibrium)
            self.moisture[sensor["id"]] = min(M_SAT, max(0.0, m + (wetting - drying) * dt_hours))

        wet_mid = [self.moisture[s["id"]] for s in self.sensors if s["kind"] == "moisture" and _row(s["zone_id"]) == 2]
        self.crack_mm += 0.0015 * dt_hours * (1.0 if wet_mid and max(wet_mid) > 25 else 0.15)
        base = [self.moisture[s["id"]] for s in self.sensors if s["kind"] == "moisture" and _row(s["zone_id"]) == 1]
        if base and sum(base) / len(base) > 50:
            self.tilt_deg += 0.0008 * dt_hours

    # ---------- what each sensor reports ----------

    def read(self, sensor: dict, now_real: float = 0.0) -> tuple[float | None, str]:
        """(value, quality) for one sensor at the current simulated time. None means the sensor sends nothing."""
        sid = sensor["id"]
        fault = self.faults.get(sid)
        if fault and now_real >= fault.until:
            del self.faults[sid]
            fault = None
        if fault and fault.mode == "dropout":
            return None, "fault"
        if fault and fault.mode == "spike":
            return self.rng.choice([0.0, 100.0, -5.0, 250.0]), "fault"

        value = self._true_value(sensor)
        if fault and fault.mode == "stuck":  # a stuck sensor does not know it is stuck: it reports "ok"
            if fault.stuck_value is None:
                fault.stuck_value = value
            return fault.stuck_value, "ok"
        return value, "ok"

    def _true_value(self, sensor: dict) -> float:
        kind = sensor["kind"]
        temp, rh = self.ambient()
        if kind == "moisture":
            return round(min(100.0, max(0.0, self.moisture[sensor["id"]] + self.rng.gauss(0, 0.4))), 1)
        if kind == "temperature":
            return round(temp + self.rng.gauss(0, 0.15), 1)
        if kind == "humidity":
            return round(min(100.0, rh + self.rng.gauss(0, 1.0)), 1)
        if kind == "crack_width":
            breathing = -0.02 * math.sin(2 * math.pi * (self.local_hour() - 9) / 24)  # closes a little in the heat
            return round(self.crack_mm + breathing + self.rng.gauss(0, 0.004), 3)
        if kind == "tilt":
            return round(self.tilt_deg + 0.003 * math.sin(2 * math.pi * (self.local_hour() - 9) / 24) + self.rng.gauss(0, 0.001), 3)
        raise ValueError(f"Unknown sensor kind {kind!r}")


def _row(zone_id: str) -> int:
    """B01-W01-R1C3 -> 1"""
    return int(zone_id.rsplit("-R", 1)[1].split("C", 1)[0])
