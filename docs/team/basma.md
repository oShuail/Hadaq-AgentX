# Basma: rain simulation, weather data, risk scoring, final PDF

**Mission:** you own the physics and the numbers. When the agent says "zone R1C3 stays wet for 54 hours", that number comes from your model; when it says "risk is high", that comes from your ABC scoring. You also assemble and submit the Round 1 PDF.

## What you own

| Path | What |
| --- | --- |
| `services/simulation/hadaq_sim/weather.py` | Open-Meteo history, wettest days, heavy-rain frequency, synthetic storms, offline cache |
| `services/simulation/hadaq_sim/moisture.py` | `bucket_v0` wall moisture model |
| `services/simulation/hadaq_sim/risk.py` | ABC risk per zone |
| `services/simulation/hadaq_sim/monitoring.py` | Anomaly rule on live moisture readings |
| `services/simulation/hadaq_sim/cli.py` | Command line |
| `contracts/building_b01.json` (`site.*`) | `heavy_rain_days_per_year`, `demo_storm_date` |
| The final PDF | Assembly and submission |

## Setup

```bash
make setup
.venv/bin/pytest -q services/simulation/tests
.venv/bin/python -m hadaq_sim.cli run --scenario storm_replay --synthetic
```

## How the model works (explain this in the deck)

Each wall zone is a bucket of water. Every simulated hour:

1. Wind-driven rain hits the facade with the ASHRAE 160 form: `r_bv = F_E x F_D x F_L x U x cos(theta) x r_h`. `theta` is the angle between where the wind comes from and where the facade faces.
2. Each zone absorbs what it can (less when it is already wet); the rest runs down to the zone below.
3. Water reaching the ground pools at the foot and feeds the base row by capillary rise after the rain stops. This is why mud walls fail at the base.
4. Zones dry with air dryness (vapour pressure deficit) and wind; the base dries at half speed.
5. An erosion index adds up water hitting the surface x wind x wetness.

Every parameter is in `WallParams` and marked ASSUMED. With the synthetic storm base zone R1C3 stays above the critical level about 54 hours versus about 6 for top zone R4C3, and `drainage_fixed` cuts R1C3 to about 29 hours. That before-and-after is the demo's intervention story.

## Tasks for Round 1, in order

### 1. Pick the real storm we replay

```bash
.venv/bin/python -m hadaq_sim.cli storms --start 2015-01-01 --end 2025-12-31 --top 10
```

Pick a day with a high total and a clear peak. Put it in `contracts/building_b01.json` as `site.demo_storm_date`. From then on, any simulation with `mode: "replay"` and no date uses it, and the result is cached in `data/weather_cache/` so the demo works offline. Run it once on the demo laptop to fill the cache:

```bash
.venv/bin/python -m hadaq_sim.cli run --scenario storm_replay   # uses demo_storm_date
```

Tell Omar the date, so the agent's `run_rain_simulation` default switches from `synthetic_storm` to `replay`.

### 2. Compute how often heavy rain happens (the ABC "A" score)

```bash
.venv/bin/python -m hadaq_sim.cli rain-frequency --start 2015-01-01 --end 2025-12-31 --threshold-mm 10
```

Put the result in `site.heavy_rain_days_per_year`. The 10 mm threshold is ASSUMED; write that on the slide.

### 3. Sanity-check the model with a chart for the deck

```python
# notebooks/base_vs_top.py  (run with .venv/bin/python)
import matplotlib.pyplot as plt
from hadaq_schemas import SimulationRequest
from hadaq_sim import WallParams, run_simulation, synthetic_storm

weather = synthetic_storm(hours=72)
runs = {s: run_simulation(SimulationRequest(building_id="B01", scenario=s), weather, WallParams(), "synthetic")
        for s in ("storm_replay", "drainage_fixed")}
for scenario, result in runs.items():
    plt.plot(result.zones["B01-W01-R1C3"], label=f"base R1C3, {scenario}")
plt.plot(runs["storm_replay"].zones["B01-W01-R4C3"], label="top R4C3, storm_replay", linestyle="--")
plt.axhline(runs["storm_replay"].threshold, color="gray", linewidth=0.8)
plt.xlabel("hour"); plt.ylabel("moisture fraction (uncalibrated)"); plt.legend(); plt.tight_layout()
plt.savefig("base_vs_top.png", dpi=200)
```

(`pip install matplotlib` in the venv first.) If the curves look wrong physically, tune `WallParams`, re-run `pytest`, and note every change in the docstring.

### 4. Tune the monitoring rule

`monitoring.py` fires a `moisture_anomaly` when moisture is above 60 %rel or rises faster than 8 %rel per hour. Run `make sensors`, press **Start storm**, and check that exactly one anomaly per sensor appears per storm (the API also has a 10-minute cooldown). Adjust `ABS_THRESHOLD` and `RISE_PCT_PER_H` if it fires too early or never.

### 5. Review the ABC risk mapping

Read `risk.py`. A = heavy-rain frequency on the log scale (5 = about once a year); B = expected loss per event from peak moisture, hours above threshold and crack growth; C = the zone's share of the wall's value. Magnitude = A + B + C; the level bands (low < 10, medium < 11, high < 12, very high from 12) are ours. Prepare one worked example on a slide:

```text
Zone B01-W01-R1C1: A 5.0 + B 3.0 + C 3.49 = 11.49 -> high
```

Take the real numbers from `GET /buildings/B01/risk` after a simulation, not from this example.

### 6. Assemble and submit the Round 1 PDF (21 Nov, before 11:59 PM)

- Template structure, in order: title, problem, proposed solution, AI concept, architecture, user journey (2 to 3 slides), current status with the demo video link, next steps, team.
- Footer and logos from the template on every slide.
- Export PDF under 50 MB, one file, named in the template's format: team name, then "Solution Deck".
- Submit by 9 PM, not 11:58 PM.

## Naming rules

- Scenarios: `baseline`, `storm_replay`, `rain_x2`, `drainage_fixed`. Add new ones in `SimulationRequest.scenario` (contracts PR) and in `apply_scenario`.
- Engine names carry a version: `bucket_v0`, next `hygro1d_v1`.
- Every parameter has a comment with its unit and ASSUMED or a source.

## Contracts

- Produces: `SimulationResult`, `RiskScore`, anomaly details (through the API).
- Consumes: `SimulationRequest`, `WeatherSpec`, `Detection` (crack growth feeds B).

## Definition of done (21 Nov)

- [ ] `demo_storm_date` and `heavy_rain_days_per_year` set from real data, cache filled on the demo laptop
- [ ] Chart of base vs top, with and without drainage, in the deck
- [ ] Anomaly fires once per storm with the simulator
- [ ] Worked ABC example from real API output in the deck
- [ ] PDF submitted

## Round 2

Replace `bucket_v0` with a 1D liquid and vapour transport solver per zone (finite volume, SciPy `solve_ivp`), check it on the EN 15026 benchmark, add Monte Carlo uncertainty bands (P10, P50, P90), calibrate on real moisture readings, then flood and seismic engines.
