"""MQTT bridge: subscribes to hadaq/v1/#, stores everything, pushes it live, runs the monitoring rule."""

from __future__ import annotations

import json
import logging
import time

import paho.mqtt.client as mqtt
from pydantic import ValidationError

from hadaq_api.settings import settings
from hadaq_schemas import TOPIC_ALL, Event, SensorReading, topic_event

log = logging.getLogger("hadaq.mqtt")


class MqttBridge:
    def __init__(self, on_reading, on_event, on_device=None) -> None:
        self.on_reading = on_reading  # callable(SensorReading)
        self.on_event = on_event  # callable(Event)
        self.on_device = on_device  # callable(device_id, "online" | "offline")
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"hadaq-api-{int(time.time())}")
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.connected = False

    def start(self) -> None:
        try:
            self.client.connect_async(settings.mqtt_host, settings.mqtt_port, keepalive=30)
            self.client.loop_start()
        except Exception as exc:  # noqa: BLE001
            log.warning("MQTT not started (%s). The API still works over HTTP.", exc)

    def stop(self) -> None:
        self.client.loop_stop()
        self.client.disconnect()

    def publish_event(self, event: Event) -> None:
        if self.connected:
            self.client.publish(topic_event(event.building_id, event.type), event.model_dump_json(), qos=1)

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        self.connected = not reason_code.is_failure
        if self.connected:
            client.subscribe(TOPIC_ALL, qos=1)
            log.info("MQTT connected to %s:%s, subscribed to %s", settings.mqtt_host, settings.mqtt_port, TOPIC_ALL)

    def _on_message(self, client, userdata, msg) -> None:
        parts = msg.topic.split("/")  # hadaq / v1 / B01 / sensors|events|devices / ...
        if len(parts) < 5:
            return
        kind = parts[3]
        try:
            if kind == "sensors":
                self.on_reading(SensorReading.model_validate_json(msg.payload))
            elif kind == "events":
                self.on_event(Event.model_validate_json(msg.payload))
            elif kind == "devices":
                status = msg.payload.decode(errors="ignore").strip()
                log.info("device %s is %s", parts[4], status)
                if self.on_device:
                    self.on_device(parts[4], status)
        except (ValidationError, json.JSONDecodeError) as exc:
            log.warning("Bad payload on %s: %s", msg.topic, exc)
