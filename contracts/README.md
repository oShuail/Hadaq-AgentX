# Contracts: the formats everyone codes against

These files are the single source of truth. If you need to change a field, a topic or an ID format, open a PR that edits this folder **and** `packages/hadaq_schemas` together, and tell the team in the group chat. Never change a contract silently.

- `building_b01.json`: the registry for the focus building (walls, zones, sensors, cameras).
- `examples/`: one valid example of every message. Tests check that they parse.
- Python models: `packages/hadaq_schemas/hadaq_schemas/models.py`
- TypeScript mirror: `apps/web/src/types/contracts.ts`

## IDs and naming

| Thing | Format | Example |
| --- | --- | --- |
| Building | `B` + 2 digits | `B01` |
| Wall | `W` + 2 digits | `W01` |
| Zone | `{building}-{wall}-R{row}C{col}` | `B01-W01-R1C3` |
| Sensor | `{building}-S` + 2 digits | `B01-S04` |
| Camera | `{building}-CAM` + 2 digits | `B01-CAM01` |
| Device (hardware or simulator) | lowercase, dash | `esp32-01`, `sim-01` |

Zones: 4 rows by 8 columns per wall. **R1 is the base row** (rows counted from the bottom). **C1 is the left column** when you stand in front of the facade.

Generated IDs use a prefix plus 12 hex characters:

| Prefix | Meaning | Example |
| --- | --- | --- |
| `evt_` | Event | `evt_3f9a1c2b7d4e` |
| `ph_` | Photo | `ph_91ab0c33de12` |
| `det_` | Detection | `det_77aa01bb2c3d` |
| `sim_` | Simulation run | `sim_c0ffee123456` |
| `risk_` | Risk score | `risk_0a1b2c3d4e5f` |
| `rd_` | Sensor reading (database row) | `rd_1532` |

Every one of these IDs is also an **evidence ID**: the agent cites them, and `GET /evidence/{id}` returns the record.

## Provenance labels

Every reading, detection, simulation and event carries one of:

| Label | Use it for |
| --- | --- |
| `measured` | A real instrument or survey (the ESP32 brick node, a real photo of a real wall) |
| `derived` | Computed from measured data (an anomaly flagged on real readings) |
| `model_output` | Simulation results, ML detections, risk scores |
| `assumed` | A literature value or a guess we have not tested |
| `synthetic` | Generated for the demo (sensor simulator, edited photos, scenario button events) |

The UI shows a "Simulated" badge for anything `synthetic`.

## MQTT topics

Broker: Mosquitto from `docker-compose.yml`, port 1883 (TCP) and 9001 (websockets). No auth in the hackathon.

| Topic | Payload | Producer | Consumers |
| --- | --- | --- | --- |
| `hadaq/v1/{building_id}/sensors/{sensor_id}` | `SensorReading` | ESP32 node, sensor simulator | API |
| `hadaq/v1/{building_id}/events/{event_type}` | `Event` | API, sensor simulator, camera simulator | API, simulators |
| `hadaq/v1/{building_id}/devices/{device_id}/status` | `online` / `offline` (retained, last will) | ESP32 node, simulators | API |

The API subscribes to `hadaq/v1/#` and is the only service that writes to the database.

## Event types

| Type | Raised by | Means |
| --- | --- | --- |
| `storm_started` | Scenario panel | Demo storm begins; simulators start raising moisture |
| `storm_ended` | Scenario panel | Storm stops; walls start drying |
| `new_crack` | Scenario panel | Camera simulator switches to the damaged photo series |
| `sensor_fault` | Scenario panel | Sensor simulator makes one sensor misbehave |
| `demo_reset` | Scenario panel | Everything back to the start state |
| `moisture_anomaly` | API (monitoring rule) | A moisture sensor rose too fast or too high |
| `new_photo` | API | A photo was uploaded |
| `detection_ready` | API | Vision posted detections for a photo |
| `simulation_done` | API | A simulation run finished |
| `recommendation_ready` | Agents | The agent proposed an action that waits for expert approval |
| `agent_note` | Agents | Summary of an automatic investigation, shown in the activity feed |

## HTTP API (Turky)

Base URL `http://localhost:8000`. Interactive docs at `/docs`.

| Method | Path | Body / query | Returns |
| --- | --- | --- | --- |
| GET | `/health` | | `{"status": "ok"}` |
| GET | `/buildings/{building_id}` | | The registry for that building |
| GET | `/buildings/{building_id}/readings/latest` | | Latest reading per sensor, with `evidence_id` |
| GET | `/buildings/{building_id}/readings` | `sensor_id`, `limit` | Reading history, newest first |
| GET | `/buildings/{building_id}/sensors` | `window` (readings, default 60) | Every registered sensor: `status`, `reasons`, `latest` (value, ts, age, quality, provenance, evidence ID) and `window` (trend, first, last, min, max, mean, change, rate per minute, evidence IDs) |
| GET | `/buildings/{building_id}/sensors/{sensor_id}` | `window`, `points` | The same for one sensor plus `history` downsampled to `points`, oldest first |
| GET | `/buildings/{building_id}/zones/{zone_id}` | `window` | One zone: `position` (base, middle, top), its sensors as above, the latest detections there, its risk score, its line in the latest simulation |
| GET | `/buildings/{building_id}/devices` | | Sensor nodes: `status` (online, offline, never_seen), sensors, last seen, MQTT status (last will) |
| GET | `/buildings/{building_id}/demo/sensor-run` | | Saleh's two-minute run: `available` (the sensor simulator `sim-01` is online, over MQTT or HTTP), `running`, `elapsed`, `duration` |
| POST | `/buildings/{building_id}/demo/sensor-run` | | Start or replay it (503 when the simulator is not running). The API sends `demo_reset` + `storm_started` (25 mm/h) at 0 s, `storm_ended` at 35 s, `sensor_fault` (S04 spike, 45 s) at 60 s, `new_crack` at 75 s; all `synthetic` |
| POST | `/buildings/{building_id}/demo/sensor-run/stop` | | Stop it and send `demo_reset` |
| POST | `/readings` | `SensorReading` | Stored reading (HTTP alternative to MQTT) |
| POST | `/events` | `Event` | Stored event; also published to MQTT |
| GET | `/buildings/{building_id}/events` | `limit` | Recent events |
| POST | `/photos` | multipart: `file`, `camera_id`, `building_id`, `captured_at`, `provenance`, `sequence`, `frame` | `Photo` |
| GET | `/buildings/{building_id}/photos` | `status=new`, `limit` | Photos: with `status`, oldest first (work queue); without, newest first |
| GET | `/photos/{photo_id}/previous-detections` | | Detections of the previous photo from the same camera |
| GET | `/photos/{photo_id}/file` | | The image |
| POST | `/detections` | `DetectionBatch` (`photo_id`, `building_id`, `camera_id`, `model`, `detections: [Detection]`) | Stored detections; marks the photo processed (send an empty list if nothing was found) |
| GET | `/buildings/{building_id}/detections/latest` | | Latest detection set |
| POST | `/simulations` | `SimulationRequest` | `{"run_id", "status": "running"}` |
| GET | `/simulations/{run_id}` | | `{"run_id", "status", "result"}` |
| GET | `/buildings/{building_id}/simulations/latest` | | The latest finished run (404 if none) |
| GET | `/buildings/{building_id}/risk` | | `[RiskScore]` per zone |
| GET | `/evidence/{evidence_id}` | | The record behind any evidence ID |
| WS | `/ws/live` | | Stream of `LiveMessage` |

`LiveMessage` = `{"channel": "reading" | "event" | "photo" | "detection" | "simulation" | "risk", "data": {...}}`. On the `reading` channel the API adds `evidence_id`, `status` and `reasons` to the `SensorReading`.

## Sensors

Units: `%rel` (wall moisture), `degC`, `%RH`, `mm` (crack width), `deg` (tilt).

Sensor status (computed by the API in `services/api/hadaq_api/sensors.py`, thresholds ASSUMED):

| Status | When |
| --- | --- |
| `ok` | Recent, plausible, not stuck |
| `suspect` | The device said `suspect`; or the value is outside the plausible range for its kind; or it is stuck: the same value 12 times in a row while another sensor of the same kind is changing |
| `fault` | The device sent `quality: "fault"` |
| `stale` | No reading for 30 s (`SENSOR_STALE_S`) |
| `no_data` | Never reported |

Only `ok` readings feed the moisture anomaly rule, the trends and the agents' evidence.

Scenario event payloads the sensor simulator reads (all optional): `storm_started` `{"peak_rain_mm_h": 12, "hours": null}`, `sensor_fault` `{"sensor_id": "B01-S04", "mode": "spike" | "stuck" | "dropout", "duration_s": 45}`, `new_crack` `{"mm": 0.35}`.

## Agents API (Omar)

Base URL `http://localhost:8100`.

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| POST | `/agents/chat` | `ChatRequest` | `ChatResponse` |
| POST | `/agents/trigger` | `Event` | `{"accepted": true}`; the agent investigates in the background |
| GET | `/health` | | `{"status": "ok", "llm": "<model or mock>"}` |
