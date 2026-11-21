"""Demo timing checks for the B01 simulator at its default two-second interval."""

import importlib.util
from pathlib import Path


spec = importlib.util.spec_from_file_location("sensor_sim", Path(__file__).with_name("sensor_sim.py"))
sensor_sim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sensor_sim)


def sensor(sensor_id, kind, row):
    return {"id": sensor_id, "kind": kind, "zone_id": f"B01-W01-R{row}C3"}


def test_two_minute_scenarios(monkeypatch):
    monkeypatch.setattr(sensor_sim.random, "gauss", lambda mean, stddev: mean)
    world = sensor_sim.World()
    base = sensor("B01-S01", "moisture", 1)
    fault_base = sensor("B01-S04", "moisture", 1)
    upper = sensor("B01-S05", "moisture", 3)
    air = sensor("B01-S03", "humidity", 1)
    gauge = sensor("B01-S06", "crack_width", 2)
    dt_hours = 2 * 720 / 3600

    dry_temp = world.step(sensor("B01-S02", "temperature", 1), dt_hours)[0]
    world.on_event("storm_started")
    for _ in range(15):  # 30 real seconds
        base_value, _ = world.step(base, dt_hours)
        fault_value, _ = world.step(fault_base, dt_hours)
        upper_value, _ = world.step(upper, dt_hours)
        humidity, _ = world.step(air, dt_hours)
        wet_temp, _ = world.step(sensor("B01-S02", "temperature", 1), dt_hours)
    assert 60 <= base_value <= 80
    assert 60 <= fault_value <= 80
    assert 20 < upper_value < base_value
    assert humidity > 70
    assert wet_temp < dry_temp - 3

    world.on_event("storm_ended")
    before = world.step(base, dt_hours)[0]
    for _ in range(15):
        after, _ = world.step(base, dt_hours)
    assert 18 < after < before  # slow drying, not an instant reset

    world.on_event("sensor_fault")
    for _ in range(10):
        value, quality = world.step(fault_base, dt_hours)
        assert quality == "fault" and 0 <= value <= 100

    old_crack = world.step(gauge, dt_hours)[0]
    world.on_event("new_crack")
    new_crack = world.step(gauge, dt_hours)[0]
    assert 0.3 < new_crack - old_crack < 0.4
    assert 1 <= new_crack <= 3

    world.on_event("demo_reset")
    assert world.step(base, dt_hours)[0] == sensor_sim.BASE_MOISTURE
    assert world.step(gauge, dt_hours)[0] < old_crack + 0.01
