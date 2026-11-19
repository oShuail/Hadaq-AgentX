"""Command line for the simulation engine.

    python -m hadaq_sim.cli storms --start 2018-01-01 --end 2025-12-31
    python -m hadaq_sim.cli run --scenario storm_replay --synthetic
    python -m hadaq_sim.cli run --scenario storm_replay --date 2024-05-01
"""

from __future__ import annotations

import argparse
import json
from datetime import date

from hadaq_schemas import SimulationRequest, WeatherSpec, load_registry
from hadaq_sim.moisture import WallParams, run_simulation
from hadaq_sim.weather import find_wettest_days, heavy_rain_days_per_year, weather_for_spec


def main() -> None:
    parser = argparse.ArgumentParser(prog="hadaq_sim")
    sub = parser.add_subparsers(dest="command", required=True)

    storms = sub.add_parser("storms", help="list the wettest days at At-Turaif")
    storms.add_argument("--start", default="2018-01-01")
    storms.add_argument("--end", default="2025-12-31")
    storms.add_argument("--top", type=int, default=10)

    freq = sub.add_parser("rain-frequency", help="heavy-rain days per year (for the ABC 'A' score)")
    freq.add_argument("--start", default="2015-01-01")
    freq.add_argument("--end", default="2025-12-31")
    freq.add_argument("--threshold-mm", type=float, default=10.0)

    run = sub.add_parser("run", help="run one scenario and print the top zones")
    run.add_argument("--scenario", default="storm_replay", choices=["baseline", "storm_replay", "rain_x2", "drainage_fixed"])
    run.add_argument("--date", help="YYYY-MM-DD of a real storm to replay")
    run.add_argument("--synthetic", action="store_true", help="use a synthetic storm instead of real data")
    run.add_argument("--hours", type=int, default=72)
    run.add_argument("--json", action="store_true", help="print the full result as JSON")

    args = parser.parse_args()
    registry = load_registry()
    lat, lon = registry["site"]["center"]["lat"], registry["site"]["center"]["lon"]

    if args.command == "storms":
        for row in find_wettest_days(lat, lon, date.fromisoformat(args.start), date.fromisoformat(args.end), args.top):
            print(row)
        return
    if args.command == "rain-frequency":
        per_year = heavy_rain_days_per_year(lat, lon, date.fromisoformat(args.start), date.fromisoformat(args.end), args.threshold_mm)
        print(f"{per_year} days/year with >= {args.threshold_mm} mm. Put it in contracts/building_b01.json site.heavy_rain_days_per_year")
        return

    storm_date = args.date or registry["site"].get("demo_storm_date")
    mode = "synthetic_storm" if args.synthetic or not storm_date else "replay"
    spec = WeatherSpec(mode=mode, date=storm_date, hours=args.hours)
    weather, source = weather_for_spec(spec, lat, lon)
    wall = registry["walls"][0]
    params = WallParams(azimuth_deg=wall["azimuth_deg"], rows=wall["rows"], cols=wall["cols"])
    request = SimulationRequest(building_id=registry["building"]["id"], wall_id=wall["id"], scenario=args.scenario, weather=spec)
    result = run_simulation(request, weather, params, source)

    if args.json:
        print(result.model_dump_json(indent=2))
        return
    print(f"{result.run_id} | {result.engine} | {source}")
    top = sorted(result.summary.items(), key=lambda kv: (kv[1].hours_above_threshold, kv[1].peak_moisture), reverse=True)[:8]
    for zid, s in top:
        print(f"{zid}: peak {s.peak_moisture:.2f}, {s.hours_above_threshold} h above {result.threshold}, erosion {s.erosion_index}")
    print(json.dumps(result.assumptions, indent=2))


if __name__ == "__main__":
    main()
