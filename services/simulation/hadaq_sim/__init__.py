"""Hadaq simulation engine (owner: Basma).

Modules:
- weather:    Open-Meteo history, wettest-day finder, synthetic storms
- moisture:   bucket_v0 wall moisture model (wind-driven rain, runoff, drying)
- risk:       ABC risk scoring per zone
- monitoring: anomaly rules on live sensor readings
- cli:        command line entry point
"""

from hadaq_sim.moisture import WallParams, run_simulation
from hadaq_sim.monitoring import detect_moisture_anomaly
from hadaq_sim.risk import score_building
from hadaq_sim.weather import WeatherHour, synthetic_storm, weather_for_spec

__all__ = [
    "WallParams",
    "WeatherHour",
    "detect_moisture_anomaly",
    "run_simulation",
    "score_building",
    "synthetic_storm",
    "weather_for_spec",
]
