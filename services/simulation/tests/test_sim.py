from datetime import datetime, timedelta, timezone

from hadaq_schemas import Detection, SimulationRequest, WeatherSpec
from hadaq_sim.monitoring import detect_moisture_anomaly
from hadaq_sim.moisture import WallParams, run_simulation
from hadaq_sim.risk import score_building
from hadaq_sim.weather import synthetic_storm


def _run(scenario: str, wind_dir: float = 270.0):
    weather = synthetic_storm(hours=48, peak_mm_h=12, wind_ms=8, wind_dir_deg=wind_dir)
    request = SimulationRequest(building_id="B01", scenario=scenario, weather=WeatherSpec())
    return run_simulation(request, weather, WallParams(azimuth_deg=270), "test storm")


def test_storm_wets_the_wall():
    result = _run("storm_replay")
    assert len(result.zones) == 32
    assert max(s.peak_moisture for s in result.summary.values()) > 0.15


def test_rain_x2_is_wetter_and_drainage_helps_the_base():
    base = _run("storm_replay")
    double = _run("rain_x2")
    drained = _run("drainage_fixed")
    zone = "B01-W01-R1C3"
    assert double.summary[zone].peak_moisture >= base.summary[zone].peak_moisture
    assert drained.summary[zone].peak_moisture <= base.summary[zone].peak_moisture


def test_wind_from_behind_keeps_facade_drier():
    facing = _run("storm_replay", wind_dir=270)
    behind = _run("storm_replay", wind_dir=90)
    zone = "B01-W01-R4C4"
    assert facing.summary[zone].peak_moisture > behind.summary[zone].peak_moisture


def test_risk_scores_cover_every_zone():
    sim = _run("storm_replay")
    det = Detection(photo_id="ph_x", camera_id="B01-CAM01", building_id="B01", zone_id="B01-W01-R1C3",
                    cls="crack", confidence=0.8, bbox_xyxy=[0, 0, 10, 10], model="mock", growth_pct=20)
    scores = score_building(sim, [det], heavy_rain_days_per_year=2)
    assert len(scores) == 32
    assert scores[0].magnitude >= scores[-1].magnitude
    assert all(0.5 <= s.A <= 5 and 0.5 <= s.B <= 5 and 0.5 <= s.C <= 5 for s in scores)


def test_moisture_anomaly_rules():
    now = datetime.now(timezone.utc)
    assert detect_moisture_anomaly([(now, 70.0)])["rule"] == "absolute"
    rising = [(now - timedelta(hours=1), 20.0), (now, 35.0)]
    assert detect_moisture_anomaly(rising)["rule"] == "rate"
    flat = [(now - timedelta(hours=1), 20.0), (now, 21.0)]
    assert detect_moisture_anomaly(flat) is None
