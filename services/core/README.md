# Core API: Spring Boot (owner: Turki)

The Hadaq hub in Java 21 / Spring Boot 4.2 on MySQL/MariaDB (`hadaq_db`), on port **8080** (the core API port in
`contracts/README.md`, v2). Today it serves the whole **v1** contract, exactly like `services/api` (FastAPI): same
paths, same snake_case JSON, same MQTT topics, same `/ws/live` websocket, so the web app, the agents, the sensor
simulator, the camera simulator and the vision worker work against it unchanged. **v2** (inspections: surveys,
captures, findings, reviews, work orders under `/api/v2`) is the next step.

`services/api` stays until the hosted demo (Render) runs this service instead.

## Run

1. Java 21, and MySQL/MariaDB running (XAMPP is fine). `hadaq_db` and its tables are created on first start.
2. `make core`, or run `HadaqApplication` in IntelliJ (open `services/core`). API on http://localhost:8080.
3. Web app: put `VITE_API_URL=http://localhost:8080` in `apps/web/.env.local`, then `make web`.
4. Optional: `make agents` (`HADAQ_LLM=mock` works without a key) for the chat and the automatic damage reports,
   and `make up` for the MQTT broker (without it set `MQTT_ENABLED=false`; the API works over HTTP).

The registry (`contracts/building_b01.json`) and the YuNet face model (`services/api/hadaq_api/models/`) are packed
into the jar at build time from where the team keeps them: there is one copy of each in the repo.

| Setting (env var) | Default | What |
| --- | --- | --- |
| `PORT` | `8080` | |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:mariadb://localhost:3306/hadaq_db` / `root` / empty | On a real MySQL server use `jdbc:mysql://...` |
| `MQTT_ENABLED`, `MQTT_HOST`, `MQTT_PORT` | `true`, `localhost`, `1883` | |
| `AGENTS_URL`, `AGENTS_ENABLED` | `http://localhost:8100`, `true` | Where storm, anomaly and detection events are forwarded |
| `API_PUBLIC_URL` | `http://localhost:8080` | Base of `file_url` in photos |
| `PRIVACY_BLUR` | `true` | Hide faces before a photo is stored (YuNet, OpenCV) |
| `HADAQ_REGISTRY` | the packed copy | Path to a registry file to read instead |

XAMPP's server is MariaDB 10.4: keep `jdbc:mariadb://`. The MySQL driver silently drops the microseconds of every
time on MariaDB, which breaks the event order and the sensor trends.

## Built-in demo (like https://hadaq.vercel.app)

The hosted demo runs the sensor simulator, camera simulator and mock vision worker next to the API. This service has
the same built in (package `Demo`: Java ports of Saleh's `wall_model.py` / `sensor_sim.py` and Abdurhmn's
`demo_frames.py` / `camera_sim.py` / mock detector / worker), so `make core` alone shows live sensors, camera photos,
detections, and a filled risk grid (a first storm simulation at startup). Activity's two-minute sensor run works on it.
Everything it makes is `synthetic`. Switch a part off when the real one runs:

| Env var | Turn off when |
| --- | --- |
| `DEMO_ENABLED=false` | all of it |
| `DEMO_SENSORS=false` | `make sensors` runs (else readings come twice) |
| `DEMO_INCLUDE_REAL=false` | the real ESP32 brick node sends S01 to S03 |
| `DEMO_CAMERA=false` | `make camera` or real photos are uploaded |
| `DEMO_VISION=false` | the real RF-DETR worker (`make vision`) runs |

## Layers

| Package | What |
| --- | --- |
| `Controller` | The v1 routes in `contracts/README.md` |
| `Service` | Readings + moisture rule, sensor health, events (stored once, live, MQTT, agents), photos + face hiding, detections, simulations, risk, evidence, devices, the two-minute sensor run, MQTT bridge, live hub |
| `Repository`, `Entity` | Spring Data JPA: `sensor_readings`, `events`, `photos`, `detections`, `simulation_runs`, `evidence` |
| `DTOIn` / `DTOOut` | Validated requests / responses in the contract shapes |
| `Enum` | Contract values (provenance, event types, kinds ...), lowercase on the wire |
| `Simulation` | Port of Basma's `hadaq_sim` (bucket_v0 moisture model, monitoring rule, ABC risk) |
| `Demo` | The built-in demo above |
| `Mapper`, `Model`, `Util`, `Events` | Mapping, registry models, IDs, times, the in-app "event stored" signal |
| `Api`, `Advice`, `Config` | `ApiException` / `ApiResponse`, `ControllerAdvice`, security (open, as the contract says), CORS, websocket, executors |

Errors come back as `{"message": "..."}` with 400, 404 or 503.

## Tests

`make core-test` (or `./mvnw test`, database `hadaq_test_db`, never `hadaq_db`). CI runs them on every push.

- `SimulationParityTest`: the Java moisture model, weather, risk and monitoring rule give the same numbers as the
  Python `hadaq_sim` (`src/test/resources/golden/sim_golden.json` was produced by running it).
- `ApiContractTest`: every v1 route, field name, status code and event.
- `LiveSocketTest`, `SensorHealthServiceTest`, `DemoTest`.

The simulation, risk bands, sensor thresholds and demo values are the team's ASSUMED demo values, ported unchanged.
