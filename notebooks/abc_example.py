# Worked ABC risk example for the slide: real storm weather -> simulation -> risk score of the worst zones.
# Run from the project folder:  .venv\Scripts\python.exe notebooks\abc_example.py
from hadaq_schemas import SimulationRequest, WeatherSpec, load_registry
from hadaq_sim import WallParams, run_simulation, score_building, weather_for_spec

registry = load_registry()
lat = registry["site"]["center"]["lat"]
lon = registry["site"]["center"]["lon"]
wall = registry["walls"][0]
rain_days = registry["site"]["heavy_rain_days_per_year"]

# same steps as the API: weather -> wall simulation -> risk
spec = WeatherSpec(mode="replay", date=registry["site"]["demo_storm_date"], hours=72)
weather, source = weather_for_spec(spec, lat, lon)
params = WallParams(azimuth_deg=wall["azimuth_deg"], rows=wall["rows"], cols=wall["cols"])
request = SimulationRequest(building_id="B01", wall_id="W01", scenario="storm_replay", weather=spec)
sim = run_simulation(request, weather, params, source)

# no camera detections here, so crack growth is not part of B
scores = score_building(
    sim,
    [],
    rain_days,
    registry["zones"].get("value_weight_overrides") or {},
    registry["zones"].get("value_weight_default", 3),
)

print("weather:", source)
print("heavy rain days per year:", rain_days)
print()
for name, zone in (("worst zone", scores[0]), ("best zone", scores[-1])):
    s = sim.summary[zone.zone_id]
    print(f"{name}: {zone.zone_id}")
    print(f"  peak moisture {s.peak_moisture:.2f}, {s.hours_above_threshold} h above {sim.threshold}")
    print(f"  A {zone.A} + B {zone.B} + C {zone.C} = {zone.magnitude} -> {zone.level}")
print()
levels = {}
for z in scores:
    levels[z.level] = levels.get(z.level, 0) + 1
print("zones per level:", levels)