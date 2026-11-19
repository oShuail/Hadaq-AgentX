"""Sensor simulator (owner: Saleh). Publishes SensorReading JSON on the same MQTT topics as the real ESP32 node.

    python devices/sensor-sim/sensor_sim.py                    # simulates B01-S04..S06 (the "sim" sensors)
    python devices/sensor-sim/sensor_sim.py --include-real     # also fakes S01..S03 when the brick node is not ready

It listens to scenario events (hadaq/v1/B01/events/#):
    storm_started  moisture rises (base faster than upper wall)
    storm_ended    drying
    sensor_fault   B01-S04 sends garbage and quality "fault" for a while
    new_crack      crack gauge jumps
    demo_reset     back to the start state

The curves here are deliberately simpler than the bucket_v0 model, so sensors and simulation are not the same
equations (avoids the circularity trap described in the architecture doc). Everything is provenance "synthetic".
"""

from __future__ import annotations

import argparse
import json
import math
import random
import threading
import time
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

from hadaq_schemas import SensorReading, load_registry, topic_device_status, topic_reading

BASE_MOISTURE = 18.0  # %rel, dry season baseline
DEVICE_ID = "sim-01"


class World:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.reset()

    def reset(self) -> None:
        self.storm = False
        self.fault_until = 0.0
        self.crack_mm = 1.20
        self.moisture: dict[str, float] = {}

    def on_event(self, event_type: str) -> None:
        with self.lock:
            if event_type == "storm_started":
                self.storm = True
            elif event_type == "storm_ended":
                self.storm = False
            elif event_type == "sensor_fault":
                self.fault_until = time.time() + 45
            elif event_type == "new_crack":
                self.crack_mm += 0.35
            elif event_type == "demo_reset":
                self.reset()
        print(f"event: {event_type}")

    def step(self, sensor: dict, dt_hours: float) -> tuple[float, str]:
        """Advance one sensor by dt_hours of simulated time. Returns (value, quality)."""
        with self.lock:
            kind, zone = sensor["kind"], sensor["zone_id"]
            is_base = "-R1C" in zone
            if kind == "moisture":
                m = self.moisture.get(sensor["id"], BASE_MOISTURE)
                if self.storm:
                    rise = 14.0 if is_base else 6.0  # %/hour while it rains
                    m += rise * dt_hours * (1 - m / 100)
                else:
                    dry = 0.03 if is_base else 0.08  # base dries slower
                    m -= dry * dt_hours * (m - BASE_MOISTURE)
                self.moisture[sensor["id"]] = m
                if sensor["id"] == "B01-S04" and time.time() < self.fault_until:
                    return random.choice([0.0, 100.0, -5.0]), "fault"
                return round(min(100.0, max(0.0, m + random.gauss(0, 0.6))), 1), "ok"
            if kind == "temperature":
                hour = datetime.now().hour + datetime.now().minute / 60
                return round(22 + 7 * math.sin(2 * math.pi * (hour - 9) / 24) - (4 if self.storm else 0) + random.gauss(0, 0.2), 1), "ok"
            if kind == "humidity":
                return round((85 if self.storm else 30) + random.gauss(0, 1.5), 1), "ok"
            if kind == "crack_width":
                self.crack_mm += 0.002 * dt_hours * (3 if self.storm else 1)
                return round(self.crack_mm + random.gauss(0, 0.005), 3), "ok"
            return 0.0, "suspect"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--interval", type=float, default=2.0, help="real seconds between readings")
    parser.add_argument("--speed", type=float, default=720.0, help="simulated seconds per real second (720 = 1 day per 2 minutes)")
    parser.add_argument("--include-real", action="store_true", help="also simulate the brick node sensors S01..S03")
    args = parser.parse_args()

    registry = load_registry()
    building = registry["building"]["id"]
    sensors = [s for s in registry["sensors"] if s["source"] == "sim" or args.include_real]
    world = World()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"sensor-sim-{int(time.time())}")
    client.will_set(topic_device_status(building, DEVICE_ID), "offline", qos=1, retain=True)
    client.on_connect = lambda c, u, f, rc, p: (c.subscribe(f"hadaq/v1/{building}/events/#"),
                                               c.publish(topic_device_status(building, DEVICE_ID), "online", qos=1, retain=True))
    client.on_message = lambda c, u, msg: world.on_event(json.loads(msg.payload).get("type", ""))
    client.connect(args.host, args.port)
    client.loop_start()
    print(f"Simulating {[s['id'] for s in sensors]} -> {args.host}:{args.port}")

    dt_hours = args.interval * args.speed / 3600
    while True:
        for sensor in sensors:
            value, quality = world.step(sensor, dt_hours)
            reading = SensorReading(
                sensor_id=sensor["id"], building_id=building, zone_id=sensor["zone_id"], kind=sensor["kind"],
                value=value, unit=sensor["unit"], ts=datetime.now(timezone.utc), provenance="synthetic",
                device_id=DEVICE_ID if sensor["source"] == "sim" else sensor["device_id"], quality=quality,
            )
            client.publish(topic_reading(building, sensor["id"]), reading.model_dump_json(), qos=0)
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
