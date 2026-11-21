"""Sensor simulator (owner: Saleh). Publishes SensorReading JSON exactly like the real ESP32 node would, so the
API, the web app and the agents cannot tell the transport apart; only `provenance` says "synthetic".

    python devices/sensor-sim/sensor_sim.py                    # the "sim" sensors (S04 to S09)
    python devices/sensor-sim/sensor_sim.py --include-real     # also S01 to S03 while the brick node is not ready
    python devices/sensor-sim/sensor_sim.py --transport http   # no MQTT broker: POST /readings, poll /events

Transport "auto" (default) tries MQTT first and falls back to HTTP when no broker answers, so it also runs on a
laptop without Docker.

It reacts to scenario events (from the scenario panel, POST /events or MQTT hadaq/v1/{building}/events/#):
    storm_started  rain builds up; payload {"peak_rain_mm_h": 12, "hours": null} (hours: end on its own)
    storm_ended    rain stops; water ponded at the wall foot keeps the base wet for hours
    sensor_fault   payload {"sensor_id": "B01-S04", "mode": "spike" | "stuck" | "dropout", "duration_s": 45}
    new_crack      the crack gauge jumps; payload {"mm": 0.35}
    demo_reset     back to the start state

The wall itself is in wall_model.py. Everything published here is provenance "synthetic" (AGENTS.md: never label
simulated data as measured).
"""

from __future__ import annotations

import argparse
import json
import logging
import signal
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx
import paho.mqtt.client as mqtt

sys.path.insert(0, str(Path(__file__).resolve().parent))
from wall_model import World  # noqa: E402

from hadaq_schemas import SensorReading, load_registry, topic_device_status, topic_reading  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("hadaq.sensor_sim")
logging.getLogger("httpx").setLevel(logging.WARNING)
DEVICE_ID = "sim-01"


class MqttTransport:
    name = "mqtt"

    def __init__(self, host: str, port: int, building: str, device_ids: list[str], on_event) -> None:
        self.host, self.port, self.building, self.device_ids = host, port, building, device_ids
        self.on_event = on_event
        self.ready = threading.Event()
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"sensor-sim-{int(time.time())}")
        self.client.will_set(topic_device_status(building, DEVICE_ID), "offline", qos=1, retain=True)
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message

    def connect(self, timeout: float = 3.0) -> bool:
        try:
            self.client.connect(self.host, self.port, keepalive=30)
        except OSError as exc:
            log.info("No MQTT broker at %s:%s (%s)", self.host, self.port, exc)
            return False
        self.client.loop_start()
        if not self.ready.wait(timeout):
            self.client.loop_stop()
            return False
        return True

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        if reason_code.is_failure:
            return
        client.subscribe(f"hadaq/v1/{self.building}/events/#", qos=1)
        for device_id in self.device_ids:
            client.publish(topic_device_status(self.building, device_id), "online", qos=1, retain=True)
        self.ready.set()

    def _on_message(self, client, userdata, msg) -> None:
        try:
            event = json.loads(msg.payload)
        except json.JSONDecodeError:
            return
        self.on_event(event.get("type", ""), event.get("payload") or {})

    def publish(self, reading: SensorReading) -> None:
        self.client.publish(topic_reading(reading.building_id, reading.sensor_id), reading.model_dump_json(), qos=0)

    def poll(self) -> None:
        pass  # events arrive on the subscription

    def close(self) -> None:
        for device_id in self.device_ids:
            self.client.publish(topic_device_status(self.building, device_id), "offline", qos=1, retain=True)
        time.sleep(0.2)
        self.client.loop_stop()
        self.client.disconnect()


class HttpTransport:
    """No broker needed: readings go to POST /readings, scenario events are polled from GET /buildings/{b}/events."""

    name = "http"

    def __init__(self, api_url: str, building: str, on_event) -> None:
        self.building = building
        self.on_event = on_event
        self.client = httpx.Client(base_url=api_url, timeout=5)
        self.seen: set[str] = set()

    def connect(self, timeout: float = 3.0) -> bool:
        try:
            self.client.get("/health", timeout=timeout).raise_for_status()
            # Events that happened before we started are history, not instructions.
            self.seen = {e["event_id"] for e in self._events(limit=100)}
        except httpx.HTTPError as exc:
            log.info("API not reachable at %s (%s)", self.client.base_url, exc)
            return False
        return True

    def _events(self, limit: int = 20) -> list[dict]:
        response = self.client.get(f"/buildings/{self.building}/events", params={"limit": limit})
        response.raise_for_status()
        return response.json()

    def publish(self, reading: SensorReading) -> None:
        try:
            self.client.post("/readings", content=reading.model_dump_json(), headers={"content-type": "application/json"})
        except httpx.HTTPError as exc:
            log.warning("Reading %s not sent: %s", reading.sensor_id, exc)

    def poll(self) -> None:
        try:
            events = self._events()
        except httpx.HTTPError:
            return
        for event in reversed(events):  # oldest first
            if event["event_id"] not in self.seen:
                self.seen.add(event["event_id"])
                self.on_event(event.get("type", ""), event.get("payload") or {})

    def close(self) -> None:
        self.client.close()


def _stop(*_) -> None:
    raise KeyboardInterrupt


def build_transport(args, building: str, device_ids: list[str], on_event):
    order = {"auto": ["mqtt", "http"], "mqtt": ["mqtt"], "http": ["http"]}[args.transport]
    while True:
        for name in order:
            transport = (MqttTransport(args.host, args.port, building, device_ids, on_event) if name == "mqtt"
                         else HttpTransport(args.api, building, on_event))
            if transport.connect():
                return transport
        log.warning("Nothing to send to yet (tried %s). Start the broker (make up) or the API (make api); retrying in 5 s", order)
        time.sleep(5)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--transport", choices=["auto", "mqtt", "http"], default="auto")
    parser.add_argument("--host", default="localhost", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--api", default="http://localhost:8000", help="API URL for the http transport")
    parser.add_argument("--interval", type=float, default=2.0, help="real seconds between readings")
    parser.add_argument("--speed", type=float, default=720.0, help="simulated seconds per real second (720 = one day per two minutes)")
    parser.add_argument("--include-real", action="store_true", help="also simulate the brick node sensors S01 to S03")
    parser.add_argument("--seed", type=int, default=None, help="fixed random seed for repeatable runs")
    parser.add_argument("--steps", type=int, default=0, help="stop after this many rounds (0 = run until Ctrl+C)")
    args = parser.parse_args()

    registry = load_registry()
    building = registry["building"]["id"]
    all_sensors = registry["sensors"]
    sensors = [s for s in all_sensors if s["source"] == "sim" or args.include_real]
    # The model needs every sensor (the base moisture drives the tilt), we only publish the selected ones.
    world = World(all_sensors, seed=args.seed)
    lock = threading.Lock()

    def on_event(event_type: str, payload: dict) -> None:
        with lock:
            world.on_event(event_type, payload, now_real=time.monotonic())
        if event_type in ("storm_started", "storm_ended", "sensor_fault", "new_crack", "demo_reset"):
            log.info("event %s %s", event_type, json.dumps(payload) if payload else "")

    device_ids = sorted({DEVICE_ID if s["source"] == "sim" else s["device_id"] for s in sensors})
    transport = build_transport(args, building, device_ids, on_event)
    log.info("Simulating %s every %.1f s at %gx over %s", [s["id"] for s in sensors], args.interval, args.speed, transport.name)

    signal.signal(signal.SIGTERM, _stop)  # a plain kill also says "offline" before leaving
    dt_hours = args.interval * args.speed / 3600
    rounds = 0
    try:
        while not args.steps or rounds < args.steps:
            started = time.monotonic()
            transport.poll()
            with lock:
                world.advance(dt_hours)
                values = [(s, *world.read(s, now_real=started)) for s in sensors]
            now = datetime.now(timezone.utc)
            for sensor, value, quality in values:
                if value is None:
                    continue  # dropout: the sensor sends nothing
                transport.publish(SensorReading(
                    sensor_id=sensor["id"], building_id=building, zone_id=sensor["zone_id"], kind=sensor["kind"],
                    value=value, unit=sensor["unit"], ts=now, provenance="synthetic",
                    device_id=DEVICE_ID if sensor["source"] == "sim" else sensor["device_id"], quality=quality,
                ))
            rounds += 1
            time.sleep(max(0.0, args.interval - (time.monotonic() - started)))
    except KeyboardInterrupt:
        pass
    finally:
        transport.close()


if __name__ == "__main__":
    main()
