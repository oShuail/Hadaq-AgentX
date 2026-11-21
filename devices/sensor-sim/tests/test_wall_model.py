import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from wall_model import World  # noqa: E402

from hadaq_schemas import load_registry  # noqa: E402

START = datetime(2026, 11, 21, 13, 0, tzinfo=timezone.utc)  # 16:00 in Riyadh


def make_world() -> World:
    return World(load_registry()["sensors"], start=START, seed=7)


def run(world: World, hours: float, dt: float = 0.4) -> None:
    for _ in range(round(hours / dt)):
        world.advance(dt)


def value(world: World, sensor_id: str) -> float:
    sensor = next(s for s in world.sensors if s["id"] == sensor_id)
    return world.read(sensor)[0]


def test_dry_wall_stays_calm():
    world = make_world()
    run(world, 24)
    assert value(world, "B01-S04") < 25
    assert value(world, "B01-S05") < 25


def test_storm_wets_the_base_first_and_it_dries_slowly():
    world = make_world()
    world.on_event("storm_started", {"peak_rain_mm_h": 12})
    run(world, 12)
    base, mid, upper = value(world, "B01-S04"), value(world, "B01-S08"), value(world, "B01-S05")
    assert base > 55, base  # the demo needs the base to cross the 60 %rel anomaly line soon after
    assert base > mid > upper - 2  # base wettest, upper wall least (allow noise)
    world.on_event("storm_ended")
    run(world, 6)
    assert value(world, "B01-S04") > base - 5  # ponded water keeps the base wet after the rain stops
    run(world, 48)
    assert value(world, "B01-S05") < upper  # the upper wall dries faster than the base


def test_storm_with_hours_ends_on_its_own():
    world = make_world()
    world.on_event("storm_started", {"hours": 3})
    run(world, 4)
    assert not world.storm


def test_faults_spike_stuck_dropout_and_recover():
    world = make_world()
    s04 = next(s for s in world.sensors if s["id"] == "B01-S04")
    world.on_event("sensor_fault", {"sensor_id": "B01-S04", "mode": "spike", "duration_s": 10}, now_real=100)
    assert world.read(s04, now_real=101)[1] == "fault"
    assert world.read(s04, now_real=111)[1] == "ok"  # cleared after duration_s

    world.on_event("sensor_fault", {"sensor_id": "B01-S04", "mode": "stuck", "duration_s": 60}, now_real=200)
    first = world.read(s04, now_real=201)
    world.on_event("storm_started")
    run(world, 6)
    assert world.read(s04, now_real=230) == first  # stuck: same value, and it still claims "ok"

    world.on_event("sensor_fault", {"sensor_id": "B01-S04", "mode": "dropout"}, now_real=300)
    assert world.read(s04, now_real=301)[0] is None


def test_new_crack_and_reset():
    world = make_world()
    before = value(world, "B01-S06")
    world.on_event("new_crack", {"mm": 0.35})
    assert value(world, "B01-S06") > before + 0.3
    world.on_event("storm_started")
    run(world, 10)
    world.on_event("demo_reset")
    assert not world.storm and world.crack_mm == 1.20 and value(world, "B01-S04") < 25


def test_every_registry_sensor_has_a_plausible_value():
    world = make_world()
    ranges = {"moisture": (0, 100), "temperature": (-5, 55), "humidity": (0, 100), "crack_width": (0, 10), "tilt": (-5, 5)}
    for sensor in world.sensors:
        v, quality = world.read(sensor)
        lo, hi = ranges[sensor["kind"]]
        assert lo <= v <= hi and quality == "ok", (sensor["id"], v)
