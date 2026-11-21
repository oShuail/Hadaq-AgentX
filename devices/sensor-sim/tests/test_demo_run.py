"""The two-minute B01 sensor run (Saleh): timing checks at the simulator defaults (2 s interval, 720x speed).

The API's SensorRun sends demo_reset + storm_started at 0 s, storm_ended at 35 s, sensor_fault at 60 s and new_crack
at 75 s (services/api/hadaq_api/sensor_run.py). These checks keep the wall model readable inside that window.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from wall_model import CRACK_MAX, World  # noqa: E402

from hadaq_schemas import load_registry  # noqa: E402

INTERVAL_S = 2
DT_HOURS = INTERVAL_S * 720 / 3600
DEMO_STORM = {"peak_rain_mm_h": 25, "wind_ms": 8, "wind_dir_deg": 270}  # the payload SensorRun sends


def sensor(world: World, sensor_id: str) -> dict:
    return next(s for s in world.sensors if s["id"] == sensor_id)


def play(world: World, seconds: int) -> None:
    for _ in range(seconds // INTERVAL_S):
        world.advance(DT_HOURS)


def test_two_minute_run():
    for seed in range(5):
        world = World(load_registry()["sensors"], seed=seed)
        s01, s04, s05 = (sensor(world, i) for i in ("B01-S01", "B01-S04", "B01-S05"))
        air, temp, gauge = (sensor(world, i) for i in ("B01-S03", "B01-S02", "B01-S06"))
        dry_temp = world.read(temp)[0]

        world.on_event("demo_reset")
        world.on_event("storm_started", DEMO_STORM)
        play(world, 34)
        base, base_s01, upper = world.read(s04)[0], world.read(s01)[0], world.read(s05)[0]
        assert 55 <= base <= 80 and 55 <= base_s01 <= 80, (seed, base, base_s01)  # crosses the 60 %rel line around 30 s
        assert 20 < upper < base
        assert world.read(air)[0] > 70
        assert world.read(temp)[0] < dry_temp - 3

        world.on_event("storm_ended")  # 35 s
        play(world, 24)
        after_storm = world.read(s04)[0]
        assert after_storm >= 60, (seed, after_storm)  # ponded water keeps the base wet: the agent sees it
        assert world.read(s05)[0] < upper + 3  # the upper wall stops wetting once the rain eases off

        world.on_event("sensor_fault", {}, now_real=60)  # default: S04, spike, 45 s
        for t in range(60, 104, INTERVAL_S):
            value, quality = world.read(s04, now_real=t)
            assert quality == "fault" and 0 <= value <= 100
        assert world.read(s04, now_real=106)[1] == "ok"  # recovered after 45 s

        old_crack = world.read(gauge)[0]
        world.on_event("new_crack", {})  # 75 s
        new_crack = world.read(gauge)[0]
        assert 0.3 < new_crack - old_crack < 0.4
        assert 1 <= new_crack <= CRACK_MAX

        play(world, 45)
        assert world.read(s04, now_real=120)[0] > 50  # base still wet at the end of the run

        world.on_event("demo_reset")
        assert world.read(s04)[0] < 25
        assert world.read(gauge)[0] < old_crack + 0.05
