# Draws wall moisture during the demo storm: base zone vs top zone, with and without drainage.
# Run from the project folder:  .venv\Scripts\python.exe notebooks\base_vs_top.py
import matplotlib.pyplot as plt

from hadaq_schemas import SimulationRequest, WeatherSpec, load_registry
from hadaq_sim import WallParams, run_simulation, weather_for_spec

registry = load_registry()
lat = registry["site"]["center"]["lat"]
lon = registry["site"]["center"]["lon"]
storm_date = registry["site"]["demo_storm_date"]
wall = registry["walls"][0]

# 1) the real storm's weather (read from the cache if you already ran it once)
spec = WeatherSpec(mode="replay", date=storm_date, hours=72)
weather, source = weather_for_spec(spec, lat, lon)

# 2) run the wall simulation twice: as it is, and with the drainage fixed
params = WallParams(azimuth_deg=wall["azimuth_deg"], rows=wall["rows"], cols=wall["cols"])
runs = {}
for scenario in ("storm_replay", "drainage_fixed"):
    request = SimulationRequest(building_id="B01", wall_id="W01", scenario=scenario, weather=spec)
    runs[scenario] = run_simulation(request, weather, params, source)

# 3) draw
base = "B01-W01-R1C3"  # base row, column 3
top = "B01-W01-R4C3"   # top row, same column
plt.figure(figsize=(9, 5))
plt.plot(runs["storm_replay"].zones[base], label="base R1C3, as is")
plt.plot(runs["drainage_fixed"].zones[base], label="base R1C3, drainage fixed")
plt.plot(runs["storm_replay"].zones[top], label="top R4C3, as is", linestyle="--")
plt.axhline(runs["storm_replay"].threshold, color="gray", linewidth=0.8, label="critical level")
plt.title(f"Wall moisture, storm {storm_date} (ERA5 weather, uncalibrated model)")
plt.xlabel("hour")
plt.ylabel("moisture fraction (uncalibrated)")
plt.legend()
plt.tight_layout()
plt.savefig("data/base_vs_top.png", dpi=200)
print("saved data/base_vs_top.png")
for scenario, run in runs.items():
    for zone in (base, top):
        print(scenario, zone, "hours above critical:", run.summary[zone].hours_above_threshold, "peak:", round(run.summary[zone].peak_moisture, 2))