import json
from pathlib import Path

import pytest

from hadaq_schemas import (
    Detection,
    Event,
    SensorReading,
    SimulationRequest,
    all_zone_ids,
    load_registry,
    parse_zone_id,
    topic_reading,
    zone_id,
)

EXAMPLES = Path(__file__).resolve().parents[3] / "contracts" / "examples"


def _load(name: str) -> dict:
    return json.loads((EXAMPLES / name).read_text(encoding="utf-8"))


def test_examples_parse():
    SensorReading.model_validate(_load("sensor_reading.json"))
    Event.model_validate(_load("event.json"))
    Detection.model_validate(_load("detection.json"))
    SimulationRequest.model_validate(_load("simulation_request.json"))


def test_zone_ids_roundtrip():
    zid = zone_id("B01", "W01", 1, 3)
    assert zid == "B01-W01-R1C3"
    assert parse_zone_id(zid) == ("B01", "W01", 1, 3)
    assert len(all_zone_ids()) == 32
    with pytest.raises(ValueError):
        parse_zone_id("Z-1-3")


def test_registry_sensors_use_valid_zones():
    registry = load_registry()
    zones = set(all_zone_ids())
    for sensor in registry["sensors"]:
        assert sensor["zone_id"] in zones, sensor
        assert sensor["id"].startswith("B01-S")


def test_topics():
    assert topic_reading("B01", "B01-S01") == "hadaq/v1/B01/sensors/B01-S01"
