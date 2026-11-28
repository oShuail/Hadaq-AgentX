# Core API: Spring Boot (owner: Turki)

The Hadaq hub in Java 21 / Spring Boot 4.2 on MySQL/MariaDB (`hadaq_db`), on port **8080** (the core API port in
`contracts/README.md`, v2). It serves:

- **v2** (inspections, under `/api/v2`): surveys, captures from the surveyor app, findings from the AI worker, the
  conservator's review, approval, work orders and the site priority list. See "v2" below.
- **v1**, exactly like `services/api` (FastAPI): same paths, same snake_case JSON, same MQTT topics, same `/ws/live`
  websocket, so the web app, the agents, the sensor simulator, the camera simulator and the vision worker work
  against it unchanged.

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
| `AI_MODE`, `AI_URL` | `mock`, `http://localhost:8100` | v2 AI worker: `remote` calls `AI_URL/ai/process` |
| `CAPTURE_DIR` | `./data/captures` | v2 uploaded photos and rectified images |

XAMPP's server is MariaDB 10.4: keep `jdbc:mariadb://`. The MySQL driver silently drops the microseconds of every
time on MariaDB, which breaks the event order and the sensor trends.

## v2: inspections

The routes of `contracts/README.md` "v2", base `http://<laptop-ip>:8080/api/v2`. Requests carry
`X-Hadaq-User: surveyor | conservator | site_manager`; the review and the approval need `conservator` (403 otherwise).

| Route | What happens |
| --- | --- |
| `GET /registry` | The buildings and walls of `contracts/building_b01.json` |
| `GET /surveys?assignee=` | A first survey ("Qasr Salwa, winter cycle", W01, shot `full`) is made at the first start |
| `POST /captures` | multipart `file` + `meta`. **202**. The same `client_uuid` again answers the first capture with `duplicate: true` (also guarded by a unique index). Faces are hidden here when the phone did not check (`faces_hidden: null`). The capture joins the wall's open inspection (one per wall per survey), the survey task gets the shot, and the AI worker gets a `ProcessRequest` |
| `GET /captures/{id}`, `/file`, `/rectified`; `POST /captures/{id}/retry` | Retry only a `failed` capture (409 otherwise) |
| `POST /captures/{id}/results` | The AI worker's callback (multipart `results` + optional `rectified`). Replaces earlier results of that capture. 400 for a wrong capture id or a non-suspected `structural_deformation` / `incompatible_repair`; 409 when a finding or work order id belongs to another capture |
| `GET /inspections/{id}`, `GET /buildings/{id}/inspections` | Captures, findings and work orders; newest first |
| `PATCH /findings/{id}/review` | `accepted`, `corrected` (needs a corrected type or severity) or `rejected`; 409 once the inspection is approved |
| `POST /inspections/{id}/approve` | 409 while any finding is `pending`, and when already approved |
| `GET /site/priority` | One row per building, urgent first, then the longest since the last survey |
| `POST /simulations`, `GET /evidence/{id}` | Simulations as in v1; evidence for `srv_`, `cap_`, `fnd_`, `wo_`, `ins_` and every v1 id |

Live (`/ws/live`): `capture`, `finding`, `review`, `work_order`, `inspection`, with the data in `contracts.ts`.

**AI worker.** `AI_MODE=remote` sends each capture to `AI_URL` (default `http://localhost:8100`) `/ai/process` and
waits for the callback; when the worker cannot be reached the capture becomes `failed` (retry it later).
`AI_MODE=mock` (the default until the worker exists) uses a built-in stand-in that does not look at the photo: it
reports the six damages of the demo wall in wall metres, with a DGDA priority (weights ASSUMED), a lookalike question
for the salts, and one work order per finding taken from `services/agents/hadaq_agents/treatments.json`. Everything it
makes is `synthetic` and its summary says it is simulated.

Our choices where the contract leaves it open (say if the team wants otherwise):

- `previous_findings` sent to the AI worker = the non-rejected findings of the wall's last approved inspection.
- Approving an inspection approves its work orders, and rejects those whose findings were all rejected.
- Open findings in the site list = the non-rejected findings of each wall's latest inspection, by the escalation the
  priority gave (a correction does not change it).
- `faces_hidden_server` = faces hidden by the core API (when the phone did not check) plus those the AI worker reports.
- `POST /api/v2/simulations` runs the built-in port of Basma's engine until the simulation service has `/sim/run`.

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
| `Controller` | v1 routes, and v2: `CaptureController`, `InspectionController`, `SiteController` |
| `Service` | v1: readings + moisture rule, sensor health, events (stored once, live, MQTT, agents), photos + face hiding, detections, simulations, risk, evidence, devices, the two-minute sensor run, MQTT bridge, live hub. v2: `CaptureService`, `InspectionService` (review, approve), `SurveyService`, `SitePriorityService`, `V2RegistryService`, `AiWorkerClient`, `MockAiWorker`, `TreatmentLibrary` |
| `Repository`, `Entity` | Spring Data JPA. v1: `sensor_readings`, `events`, `photos`, `detections`, `simulation_runs`, `evidence`. v2: `surveys`, `inspections`, `captures`, `findings`, `work_orders` |
| `DTOIn` / `DTOOut` | Validated requests / responses in the contract shapes |
| `Enum` | Contract values (provenance, event types, kinds ...), lowercase on the wire |
| `Simulation` | Port of Basma's `hadaq_sim` (bucket_v0 moisture model, monitoring rule, ABC risk) |
| `Demo` | The built-in demo above |
| `Mapper`, `Model`, `Util`, `Events` | Mapping, registry models, IDs, times, the in-app "event stored" signal |
| `Api`, `Advice`, `Config` | `ApiException` / `ApiResponse`, `ControllerAdvice`, security (open, as the contract says), CORS, websocket, executors, `SchemaFixes` (the unique `client_uuid` index Hibernate cannot make on MariaDB 10.4) |

Errors come back as `{"message": "..."}` with 400, 403, 404, 409 or 503.

## Tests

`make core-test` (or `./mvnw test`, database `hadaq_test_db`, never `hadaq_db`). CI runs them on every push.

- `SimulationParityTest`: the Java moisture model, weather, risk and monitoring rule give the same numbers as the
  Python `hadaq_sim` (`src/test/resources/golden/sim_golden.json` was produced by running it).
- `ApiContractTest`: every v1 route, field name, status code and event.
- `V2ApiTest`: the whole v2 flow (upload, dedupe, mock analysis, review, 403 / 409 rules, approval, work orders,
  site priority, evidence), and Omar's examples in `contracts/v2/examples/` parse and validate against these DTOs.
- `V2RemoteAiTest`: `AI_MODE=remote` against a stand-in worker (the `ProcessRequest` sent, the callback, failure,
  retry, an id that belongs to another capture).
- `LiveSocketTest`, `SensorHealthServiceTest`, `DemoTest`.

The simulation, risk bands, sensor thresholds and demo values are the team's ASSUMED demo values, ported unchanged.
