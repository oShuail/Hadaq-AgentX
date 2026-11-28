# Hadaq handover

Read this first when you start a session (human or AI). Update it before you end one.

**Challenge requirements, gaps and plan:** [docs/challenge-plan.md](docs/challenge-plan.md) (Arabic). **Agents reference:** [services/agents/README.md](services/agents/README.md).

**How to update:** rewrite "Current state", "Known issues" and "Next steps" so they stay true (do not just append), then add one entry at the top of "Session log". Keep it short: what changed, why, what is left.

---

## Current state (2026-11-21, after the integration of all branches)

| Area | State | Owner |
| --- | --- | --- |
| Web app (`apps/web`) | Working. Budots-style 3D twin of Diriyah in three.js with the **Courtyard HUD** (design 3): the map is full screen and four corner towers sit over it (brand and tabs top left, status and the Layers menu top right, views and About bottom left, the panel bottom right), a sun-arc clock bottom centre, and sensor readings pinned to the B01 facade. Clicking a twin building opens its own page at `/building/<id>` (not a popup); Qasr Salwa takes two clicks, the first shows its reading cards on the map. Activity's two-minute B01 run (Saleh) controls the sensor simulator through the API; the map, twin panel and building page show the live WebSocket readings | Omar (twin), Turki (shell), Saleh (sensor run) |
| Building pages (`src/building/`, list in `src/twins.ts`) | **B01 Qasr Salwa (قصر سلوى)**: our model with the wall zone grid, wall condition, sensors, camera, activity, agent. **B02 AL Turaif House**: opened from "palace of Nasr" on the map, shows the Sketchfab scan with placeholders (no registry, sensors or camera yet). New on B01: the **Damage map** section (`DamageMap.tsx`: the agent's latest report drawn on its photo, numbered marks, colour key by damage family, treatment card, zone grid toggle) and a **Damage** layer next to Moisture and Risk that colours the grid and the 3D wall the same way (also in the layer pill above the sun clock on the map, where Damage is the default). The camera shows how many faces were hidden. The Activity feed shows damage reports as cards; the chat suggestions cover damage (one in Arabic) | Omar |
| 3D map data (`apps/web/public/site/`) | Baked and committed (about 9 MB): OSM + Microsoft footprints, Esri imagery, AWS terrain, 76k palms. No keys needed | Omar |
| Focus building model | B01 is named Qasr Salwa (قصر سلوى). Model `apps/web/public/models/b01_v3.glb` (decimated Meshy citadel from `assets/turaif/`), placed by `contracts/building_b01.json` `base_line` and `azimuth_deg` | Omar |
| Real scan | AL Turaif House (Sketchfab embed, by ATS) is the 3D view of the B02 page. Our own photogrammetry scan is not done | Omar |
| Agents (`services/agents/`) | Three LangGraph agents: Orchestrator, **Damage and Treatment** (new) and Simulation and Advisory. The damage agent maps the latest photo's detections onto the team's treatment table (`hadaq_agents/treatments.json`: 11 damage types in Arabic and English) and, on its own when a photo with new or grown damage is analysed, sends a damage report (type, zone, severity, treatment, materials, tools, source) for expert review. Mock mode answers in Arabic and English. LLM settings fixed for the current models (no `temperature`, which they reject; `max_tokens` 16000; effort `medium`; server-side fallback; cached system prompt; 90 s timeout) and a labelled fixed-plan answer if the LLM fails. The real LLM path is checked by a contract test against a stand-in Messages API, not yet with a real key. See `services/agents/README.md` | agents task owner |
| Photo privacy (`services/api/hadaq_api/privacy.py`) | Faces are hidden (pixelated and blurred) before an uploaded photo is stored; YuNet face detector from the OpenCV Zoo (`models/`, MIT licence alongside). The count goes into the `new_photo` event payload (`privacy`). `PRIVACY_BLUR=false` turns it off. Needs `opencv-python-headless` (run `make setup` again) | agents task owner (review: Turki) |
| Challenge fit (`docs/challenge-plan.md`, Arabic) | Organisers' requirements and scoring, what we have, gaps, prioritised plan. Built: damage types, treatments, materials and tools, damage map with a colour key, face hiding. Not built: real photos, expert review screen, accuracy check against an expert, a run with the real LLM key | abooda7m |
| Simulation, vision, devices | Scaffolded and tested (see each team guide in `docs/team/`). Saleh: the camera simulator generates a fixed synthetic wall series locally when no photos are present; API and vision recognise the growing R2C5 crack with synthetic provenance | Basma, Abdurhmn, Saleh |
| Sensors (B01) | **Working end to end, together with Saleh's two-minute run.** 9 sensors (S01 to S09: base, mid and upper moisture, temperature, humidity, crack gauge, tilt) from `devices/sensor-sim/` over MQTT, or HTTP when there is no broker. The API gives each sensor a health status (ok, suspect, fault, stale, no_data) and a trend (`GET /buildings/B01/sensors`, `/sensors/{id}`, `/zones/{zone_id}`, `/devices`); the agents read them through `get_sensor_overview`, `get_sensor_history`, `get_zone_detail`; the web shows a trend line and the status per sensor. The brick node (S01 to S03) is still simulated with `--include-real` | Saleh, Turki |
| Core API (`services/core`, Spring Boot, :8080) | **Branch `Turki`.** The whole v1 contract in Java 21 / Spring Boot on MySQL/MariaDB (`hadaq_db`): same paths, JSON, MQTT topics and `/ws/live` as `services/api`, checked against every team client (sensor simulator, camera, vision worker, agents in mock mode, web app). Built-in demo like the hosted site (live sensors, camera, mock detections, a startup storm simulation; `DEMO_*=false` turns parts off). **v2 (inspections) built**: every route of `contracts/README.md` "v2" under `/api/v2` (registry, surveys with a seeded one, captures with dedupe and face hiding, AI worker dispatch and callback, review, approve, site priority, evidence, the five new live channels). Until the AI worker has `/ai/process`, `AI_MODE=mock` (default) analyses captures with a built-in synthetic stand-in; `AI_MODE=remote` calls `AI_URL`. 30 tests, including the v2 examples in `contracts/v2/examples/`. See `services/core/README.md` | Turki |
| Surveyor app (`apps/ios`, S1 + S2) | **On `main` (also branch `saleh/ios-capture`). Builds in CI (Xcode 16.4, simulator) and its 20 core tests pass; not yet run on an iPhone.** SwiftUI, iOS 17: task list from `GET /api/v2/surveys` and `/registry` (cached on disk, bundled contract examples before the first sync), guided camera (level within 5°, steady, light, scale toggle; warnings never block), faces pixelated on the phone before anything is saved, four corner handles (`corners_px`), Import from Photos with the EXIF date, SwiftData offline queue, upload to `POST /api/v2/captures` with the same `client_uuid` on every retry, polling `GET /captures/{id}` for status, findings count and urgent, Reset in Settings. Core logic has `swift test` unit tests against `contracts/v2/`; `.github/workflows/ios.yml` builds the app on macOS. See `apps/ios/README.md` | Saleh (written by Omar) |
| Simulation site data (`contracts/building_b01.json` `site.*`) | Set from Open-Meteo ERA5 by Basma (PR #3): `heavy_rain_days_per_year` 1.91 (2015 to 2025, ASSUMED 10 mm/day threshold), `demo_storm_date` 2023-01-05. Only used by `mode: "replay"` runs, which nothing sends yet (see Known issues) | Basma |

### Run it

```bash
make up && make api      # API on :8000
make agents              # agents on :8100 (HADAQ_LLM=mock works without a key)
make web                 # http://localhost:5173
make sensors             # sensor simulator (MQTT, or HTTP by itself when there is no broker)
make camera && make vision   # camera frames and the mock detector (damage reports)
```

On Windows without Docker or make, one PowerShell terminal per part, from the repo root (start the API first; `make setup` once, or `py -3 -m venv .venv` and the `pip install -e ...` line from the Makefile):

```powershell
$env:MQTT_ENABLED="false"; .venv\Scripts\python.exe -m uvicorn hadaq_api.main:app --port 8000
.venv\Scripts\python.exe -m uvicorn hadaq_agents.server:app --port 8100
.venv\Scripts\python.exe devices\sensor-sim\sensor_sim.py --include-real --transport http
.venv\Scripts\python.exe -m hadaq_vision.camera_sim --folder assets/camera/B01-CAM01 --interval 10
.venv\Scripts\python.exe -m hadaq_vision.worker --backend mock
cd apps\web; npm run dev
```

After pulling the 3D map change, run `cd apps/web && npm install` once (Cesium was replaced by three.js).

### Where things are in the web app

| Path | What |
| --- | --- |
| `apps/web/src/twin3d/Twin.ts` | The scene: loading, views, sun and lighting, layers, hover and click |
| `apps/web/src/twin3d/buildings.ts` | Extruded buildings (Najdi parapets and crenellations, night floodlights) |
| `apps/web/src/twin3d/infill.ts` | Illustrative courtyard ruins inside At-Turaif |
| `apps/web/src/twin3d/focus.ts` | B01 model placement, wall zone grid, attention boxes |
| `apps/web/src/twin3d/{terrain,palms,infra,lights,labels,walkers,sun,siteData}.ts` | Terrain, palms, roads and water, glow lights, place names, visitors, sun position, data loading |
| `apps/web/src/bake/bake.ts` + `apps/web/bake.html` | Re-bakes the site data (dev server only), see `tools/site_data/README.md` |
| `apps/web/src/App.tsx` | Map screen layout and state (tabs, selection, simulation hour) |
| `apps/web/src/hud/` | The HUD towers: `BrandTower` (brand + tabs), `LayersMenu` (status + Layers button and menu), `ViewsTower` (views, About and sources, attribution), `callouts.ts` (which sensor cards get pinned to which zone) |
| `apps/web/src/TimeBar.tsx` | Sun-arc clock (drag the sun, play, date, speed, Now) and the Damage / Moisture / Risk pill above it |

## Known issues and honesty notes

- The B01-CAM01 photo folders are empty in git by design. Without an actual wall photo, `make camera` generates illustrative synthetic artwork at 1600 x 1200 with wall bounds `[80, 80, 1520, 1120]`. Replace both folders and remeasure the bounds when the real fixed-camera series is available; no photos have been shared to Drive.
- Activity's sensor run needs the API and `make sensors` (MQTT or HTTP; the API checks that `sim-01` is online in `GET /buildings/B01/devices`). It is unavailable on a static web preview. The API times the scenario events, the simulator sends the nine readings, and the site renders the persisted WebSocket feed.
- In the two-minute run the agent investigates once at the start (`storm_started`, when the wall is still dry); the `moisture_anomaly` about 30 s later falls inside the moisture track's cooldown, so it does not start a second investigation. Ask the agent in the chat after the anomaly for a wet-wall answer. Camera frames and crack detection additionally require `make camera` and `make vision`.
- This Windows/WSL host stops its Docker MQTT broker when WSL becomes idle. Keeping WSL alive enabled two fresh end-to-end sensor cycles and a complete timed sensor scenario including fault recovery. Camera upload, worker comparison and the synthetic provenance event passed twice through the API. The web shows sensor values, the photo detection boxes and the R2C5 attention box on the 3D wall; two mock agent drainage answers returned. This is an illustrative demo, not real photos or a calibrated model.
- Map layers switched off for now (built but hidden, kept in code): car parks and cars, lanterns, street lights, visitors, helipads. Turn one back on by removing it from `DISABLED_LAYERS` in `apps/web/src/twin3d/Twin.ts`.
- B01 is called Qasr Salwa but stands at a placeholder spot west of the real Salwa Palace; the real palace is not a separate outline in our OSM data. Say "placeholder location" if asked, or move it with `base_line`.
- B02 ("AL Turaif House", the OSM "palace of Nasr") has no registry in `contracts/`, so its page has placeholders and the agent does not answer for it.
- Building heights are estimates unless OSM has them. The ruins filling At-Turaif are illustrative, not surveyed. Visitors and cars are simulated. Say this on the slide.
- B01 is an AI-generated model at a placeholder spot, not a survey of a real building.
- Esri imagery is fine for a non-commercial demo with the attribution kept (Explore, About).
- Chrome slows the 3D view to about 1 fps when its window is hidden or covered. That is the browser, not the app. Measured on Omar's laptop (AMD integrated GPU): about 27 ms per frame, roughly 35 fps.
- The real storm (`demo_storm_date`) is not used in the demo yet: the web client (`apps/web/src/api/client.ts` `runSimulation`) and the agent tools (`run_rain_simulation`, `compare_scenarios`) all send `mode: "synthetic_storm"`. The API only fills `demo_storm_date` for `mode: "replay"` with no date.
- The mock agent's answers no longer start with a "[mock agent ...]" label (Omar's decision: say it in the presentation). `GET /agents/health` still reports `"llm": "mock"`; when a real LLM call fails, the fallback answer still says so.
- A replay run needs the internet or a filled weather cache (`data/weather_cache/`). Offline with no cache, the simulation job fails; there is no fallback to the synthetic storm yet.
- Sensor readings carry real timestamps, but the simulator compresses a day into two minutes (`--speed 720`). Rates "per minute" therefore look fast, and the anomaly rate rule (it needs 15 real minutes of history) never fires in a short demo; the absolute 60 %rel rule does.
- A stuck sensor is only caught while another sensor of the same kind is moving, so press **Stuck sensor** during a storm. A wall that is calm everywhere is never flagged (on purpose: the real brick node can sit at one value for minutes).
- `contracts/building_b01.json` gained S07 (base moisture R1C1), S08 (mid-wall moisture R2C3) and S09 (tilt R4C5, unit `deg`). The building page and the facade cards show them; when the panel is open only the zones that need attention get a card.
- `tools/turaif_model/` (the old Cesium district builder) is superseded and kept only for reference.
- Clean-up suggestions (Turki, 2026-11-28; nothing removed, owners decide): `assets/turaif/` is 38 MB of the 62 MB repo (the source Meshy model and textures `b01_v3.glb` was made from; only `tools/turaif_model/` uses it), so it could move to Drive; `apps/web/src/panel/ScanViewer.tsx` is imported nowhere; 12 old merged branches could be deleted. Removing files does not shrink a clone (they stay in git history).
- Two APIs exist for now: `services/api` (Python, :8000) and `services/core` (Spring, :8080, same v1 contract). The hosted demo (Render) still runs the Python one. Retire `services/api` once Render runs `services/core`; until then, run one of them, not both, against the same simulators.
- `apps/web/.env.local` files from the Cesium version still have `VITE_CESIUM_ION_TOKEN`, `VITE_DISTRICT_URL`, `VITE_GOOGLE_3D`. They are unused and can be deleted.
- The demo story (`docs/demo-script.md`) still centres on the storm. The challenge makes sensors optional and scores damage types, treatments, materials and tools from real photos, a clear damage map, expert correction and face blurring. Proposed new story: `docs/challenge-plan.md` section 9.
- Damage agent: the crack-width thresholds (2 mm, 10 mm) and the priority rule are ours (`assumed`). The treatment table was transcribed from a screenshot and its source document ("الهيئة") is not named yet. Incompatible repairs, timber decay and structural deformation cannot be found by the current detector classes.
- Start new branches from `main` (`omar/turaif-district` is merged into it).
- Without Docker (no MQTT) the camera simulator now reads the scenario events from the API before each frame, so **New crack** still switches it to the damage series (up to one camera interval, 10 s, later than over MQTT).
- After pulling: run `make setup` (or `pip install -e services/api`) once. The API now needs OpenCV for face hiding; without it the API still starts but stores photos unchecked and logs an error.
- After pulling the demo-variety change: delete `assets/camera/B01-CAM01/baseline` and `damage` once if they were generated before 21 Nov (no `.demo_frames_version` file), then restart the camera and the vision worker.
- The zone value weights (`value_weight_overrides`) are our assumption for the demo, not a heritage assessment. Say so if the risk grid comes up.
- Face hiding is checked in tests with a fixed face box and on wall texture (no false faces), but not yet on real photos with visitors. An upload that is not a decodable image is stored as is with `privacy.checked = false`.
- Damage map colours are per damage family (4), not per type (11): only three hues stay distinguishable for every pair on a map, colour-blind readers included (checked with a palette validator). The type name and number are always next to the mark.
- If two vision batches are posted for the same photo (worker restart), `/detections/latest` returns both; the agent keeps one finding per zone and class.

- The iOS app (`apps/ios`) builds in CI (`.github/workflows/ios.yml`: core tests + simulator build) but has not run on a real iPhone yet: the camera, level, face hiding and the airplane-mode sync can only be checked there. `Hadaq.xcodeproj` was written by hand in the Xcode 16 format; if it does not open, `xcodegen` in `apps/ios` rebuilds it from `project.yml`.
- The phone sends `survey_id` only after it has fetched the surveys from Spring once; photos taken before that go up without a survey (Spring files them under the wall's open inspection with no survey).

## Next steps

- **Everyone:** read `docs/challenge-plan.md` and answer its open questions (section 12). Pull `main` once the integration PR (`omar/integration`) is merged, then `make setup` and `npm install` in `apps/web` once. Your old branches are all inside it; start new work from `main`.
- **Omar:** decide the new demo story and the contract changes for expert review and face blurring (`docs/challenge-plan.md` sections 9 and 10); fill the building pages (wall condition details, camera per wall, per-building agent context); register B02 in `contracts/` when it gets sensors; check the wall zones line up with the B01 facade in the Focus building view; full demo run twice.
- **Agents task owner:** put the LLM key in `.env` (`HADAQ_LLM=anthropic:claude-sonnet-5-5`) and run the chat suggestions in Arabic and English with the real model (no verifier warnings, damage report on a new photo); then build the approval step (approve, then a work order) with the expert review screen once Omar approves the contract changes.
- **Turki:** T3, T4 and the core side of T5 are on `main` (PR #13); the site manager's survey screen is on branch `Turki`. Left for T5: the live run with the real AI worker (`AI_MODE=remote`) once `/ai/process` exists, and with Basma's service (`SIM_URL=http://localhost:8200`) once `/sim/run` exists. Optional: a Render setup for `services/core`.
- **Omar (O4):** `apps/web/src/api/core.ts` now exists with `CORE_URL` and `core.sitePriority()` (the plan said to add it if T3 came first). Extend it for the inspection page; `config.ts` has `CORE_URL` and `CORE_WS`, `.env.example` has `VITE_CORE_URL`.
- **AI worker (Abdurhmn, Omar):** the core API sends `ProcessRequest` to `AI_URL/ai/process` and expects 202, then the multipart callback on `callback_url`. Finding and work order ids must be new (the core refuses an id that belongs to another capture with 409). `previous_findings` = the non-rejected findings of the wall's last approved inspection.
- **Basma:** the core forwards simulations to `SIM_URL/sim/run` when it is set. The body it sends is in `services/core/README.md` ("Our choices": `run_id`, `building_id`, `wall` as WallInfo, `scenario`, `weather` as the v1 WeatherSpec, `approved_findings` as v2 Findings, `requested_by`); answer with a `SimulationResult` (extras such as the first critical hour per zone are kept). Say if you want another body. Until it is up, or if it fails, the core runs its Java port and says so.
- **Omar (contract):** the core made a few choices the v2 spec leaves open (listed in `services/core/README.md`, "Our choices"): what approval does to work orders, what counts as an open finding in `/site/priority`, `faces_hidden_server`. Say if any should change.
- **Saleh (iOS, S1 and S2):** the app is on `main` (`apps/ios/README.md`); work on `saleh/ios-capture`. On a Mac: open `apps/ios/Hadaq.xcodeproj`, set your signing team, run it on the iPhone, and go through the README's "Test on the phone" list (the plan's Done-when lines). Fix whatever the first Xcode build reports, calibrate the blur score on the demo phone, then E1 with Omar at Sun 12:00.
- **Saleh:** your old `World` class in `sensor_sim.py` was replaced by `wall_model.py` during the integration; your two-minute timing is kept as `devices/sensor-sim/tests/test_demo_run.py`, your fault values (3, 96, 8, 91) and the 3 mm crack cap are in the model. With `make api` and `make sensors` running, use Activity → Play sensor run (Replay restarts, Reset demo restores the baseline). Left: build the brick node and run the simulator without `--include-real` once it streams; film the water-pour shot; tune `K_RAIN` if the run looks too fast or slow on the demo laptop (keep `test_demo_run.py` passing).
- **Whoever does the agents:** the sensor tools are in `tools.py` and both agents have them; with a real LLM, check it skips sensors that are not `ok` and cites the `rd_` evidence IDs from the trends.
- **Basma:** share the `hadaq_sim.cli storms` row for 2023-01-05 (total mm and peak mm/h) for the slide; run a replay once on the demo laptop to fill `data/weather_cache/`. The simulation player is hidden from the map for phase 1; the simulation still runs in the background (risk scores, the agent's drainage question) and the Moisture layer shows each zone's peak in the latest run.
- **Omar or Turki:** switch the web client and the agent tools to `mode: "replay"` so the demo uses the real storm, and make the API fall back to the synthetic storm if the weather fetch fails offline.
- **Abdurhmn:** try face hiding on real At-Turaif photos with visitors (far and side views) and report any missed face. Demo video shots changed again (HUD layout, views bottom left, readings pinned on the facade in the Qasr Salwa view), see `docs/demo-script.md`.
- **Saleh and Abdurhmn:** rehearse the complete two-minute shot sequence on the demo laptop with all services running, and share the photo series to the team Drive if needed. Replace the illustrative series with a real straight-on source photo and remeasure `wall_bbox_px` when available.

---

## Hosted demo (Vercel + Render)

- Web app: Vercel project `hadaq` (https://hadaq.vercel.app), built from `apps/web`. It reads `VITE_API_URL` and `VITE_AGENTS_URL` (Vercel project settings, Environment Variables); without them it looks for `localhost` and shows Offline.
- Backend: Render web service `hadaq-api` from `render.yaml` (Docker, `infra/render/`): the API with the agents' routes on one port, plus the sensor simulator, camera simulator and mock vision worker in one helper process, all over HTTP (no MQTT). About 380 MB, under the free plan's 512 MB.
- Free plan: the service sleeps after about 15 minutes without visitors and takes about a minute to wake; its disk is wiped on every restart (fresh database, new photos). Open the site a minute before showing it.
- Real LLM answers: add `HADAQ_LLM` and `ANTHROPIC_API_KEY` in the Render dashboard (Environment). Without them the agent runs the mock plan.

## Session log

### 2026-11-29 (Turki): the site manager plans surveys

- A **المسوحات · Surveys** section in the Priority tab (`hud/SurveyPlanner.tsx`): every survey with each wall's status (لم يبدأ / جزئي / مكتمل), done and missing shots and the due date, reloaded when a capture arrives or after a demo reset; and a **مسح جديد** form (title, surveyor, due date, walls from `/api/v2/registry`, each whole or in 2 or 4 parts) that calls `POST /api/v2/surveys` as `site_manager` and shows the API's own error text. `api/core.ts` gained `registry()`, `surveys()`, `createSurvey()`.
- Checked in Chrome on core alone: an empty title is refused in Arabic; a survey for W01 in two parts is created (C1-C4, C5-C8) and appears at once, also in `GET /api/v2/surveys?assignee=surveyor` for the app; after a capture with `segment: C1-C4` it turned «جزئي» 1/2, missing C5-C8, without a reload; no console errors. `npm run build` passes.

### 2026-11-28, late night (Turki): T5, the core side

- Neither `/ai/process` (A3) nor `/sim/run` (B3) exists on any branch yet, so T5 is the core side plus a rehearsal.
- Simulations: with `SIM_URL` set, `POST /simulations` (v1 and v2) goes to `SIM_URL/sim/run` and keeps the answer as it is (extras included); on any failure the Java model runs and says so in `assumptions`; `simulation_done` has `engine_source`. Body documented in `services/core/README.md`. Tested against a stand-in service (good answer, 500, broken answer).
- AI worker rehearsal: core with the real demo settings (`AI_MODE=remote DEMO_CAMERA=false DEMO_VISION=false`) against a stand-in worker in Python that uses our `hadaq_schemas` models like the plan's worker. Two captures: the `ProcessRequest` validates in Pydantic (also with an approved previous finding), the photo downloads, the multipart callback is accepted, `change: unchanged` points at the approved finding, the rectified image is served.
- Found and fixed on the way: Spring sent the JSON bodies chunked (no `Content-Length`), which a plain Python server refused; the core now sends the length. The Java test stand-ins answer 411 to a chunked body so it cannot come back. 35 Java tests.

### 2026-11-28, night (Turki): T4 demo reset and T3 site manager ranking

- T4: `tools/demo_reset.sql` + `tools/demo_reset.sh` (`make demo-reset`, API stopped; finds XAMPP's mysql client on Windows and macOS) and `POST /api/v2/demo/reset` (API running, only with `DEMO_ENABLED=true`). Checked as the plan asks on the real `hadaq_db`: stop core, run the script twice, start core: no open findings, survey back with no shots.
- T3: a **Priority · الأولويات** tab in `hud/BrandTower.tsx` with `hud/PriorityPanel.tsx` (header counts, ranked rows with the Arabic escalation badge, reason, days since the last survey; a click flies to the building) and a ground tint under each twin building by its worst escalation (`Twin.setEscalations`, the plan's colours). Data from `state/useSitePriority.ts` (core API, reloaded on the v2 live channels). New `api/core.ts`, `CORE_URL` / `CORE_WS` in `config.ts`, `VITE_CORE_URL`.
- The tint is a patch on the ground, not the plan's emissive change: the focus model's emissive is already the night glow (`setTime`), so changing it would break the night look. Other twins are flown to by their footprint (`flyTo("twin:<id>")`); Qasr Salwa keeps its own view.
- Checked in Chrome with the web app on core alone (:8080): clean state "0 مبنى تم مسحه · 0 عاجل"; one upload, and without a reload Qasr Salwa became first with «عاجل» and the R1C4 reason; the click flew to it with the rose tint; a demo reset cleared the tint and the list live; no console errors. 33 Java tests, `npm run build`.

### 2026-11-28, evening (Turki): create surveys (contract change, please review)

- v2 had no way to create a survey (only `GET /surveys`). Added `POST /api/v2/surveys` for the site manager (`X-Hadaq-User: site_manager`): body `SurveyRequest` (title, assignee, due, tasks with `required_shots`), answer 201 `Survey` with empty `done_shots` and status `not_started`.
- Changed together, as "Changing v2" asks: `contracts/README.md` (one row), `inspection.py` (`SurveyRequest`, `SurveyTaskRequest`, exported), `contracts.ts` (`TaskStatus`, `SurveyTask`, `Survey`, `SurveyRequest`; `Survey` was missing there), a new example `contracts/v2/examples/survey_request.json`, the regenerated schemas (only `SurveyRequest.json` is new; the other 13 came out identical), and the example in `test_inspection.py`. Nothing existing changed shape.
- `services/core`: the route, with checks (wall exists, column range inside the wall, no wall twice). The seeded survey stays for the demo.
- Checked: 26 Python schema tests, 31 Java tests (the new example validates against the Java DTO; create, list, a partial shot, 403 and 400 cases), web `npm run build`.

### 2026-11-28, surveyor app (Omar, with an AI assistant): S1 and S2

- New `apps/ios/` on branch `saleh/ios-capture`: the capture app (S1) and the offline queue and sync (S2) from the Phase 2 plan, checked against `services/core` (multipart `file` + `meta`, dedupe on `client_uuid`, `findings_count` and `urgent` on `GET /captures/{id}`, 20 MB limit, `{"message"}` errors).
- Changes from the plan's sample code: photos are drawn upright before face detection (the sample pixelated the wrong place on portrait photos), times are decoded with microseconds (Spring writes them; Swift's `.iso8601` rejects them), landscape shots keep their orientation, a capture Spring marked `failed` is retried with `POST /captures/{id}/retry` instead of re-uploading forever, 4xx answers stop the retries, `NSLocalNetworkUsageDescription` added, `survey_id` only for surveys that came from Spring.
- Pushed to `main`. CI on macOS: Xcode 16.4 build succeeded, 20 core tests pass. Not run on an iPhone yet, and Spring was not run against it (Maven Central blocked here). Left: the phone checks in `apps/ios/README.md`, then E1.

### 2026-11-28, review (Omar, with an AI assistant): PR #12 merged

- Reviewed and merged Turki's PR #12 (`bf8dbbe`): `services/core` (Spring Boot 4.2, MariaDB) with v1 and v2. CI green (core job: 30 tests on MariaDB 10.11).
- Open issues: `AI_URL` and `AGENTS_URL` both default to :8100, so the AI worker (A3) must add `/ai/process` to the existing agents app instead of starting a second server on 8100. Run only one backend (`services/core` on :8080, not `services/api` on :8000). For the real demo turn off `DEMO_CAMERA`, `DEMO_VISION` and set `AI_MODE=remote`.
- Task plan doc updated: T1, T2 and the T3 backend marked done; left for Turki: T3 web (priority tab, building tint), T4 demo reset (MariaDB SQL), T5 wiring the real AI worker and forwarding `/api/v2/simulations` to the sim service on :8200.
- Not checked locally: Maven Central is blocked from the review machine, so the build was verified through the GitHub Actions run only.

### 2026-11-28, later (Turki): v2 core API

- Built contracts v2 in `services/core` under `/api/v2`: registry (from `contracts/building_b01.json`), surveys (one seeded: "Qasr Salwa, winter cycle"), captures (202, `client_uuid` dedupe with a unique index, faces hidden when the phone did not check, one open inspection per wall per survey, survey shot marked done), dispatch to the AI worker and the results callback (findings + work orders, replaces a re-analysis), retry, conservator review and approval (`X-Hadaq-User`, 403 / 409 rules), site priority, v2 evidence and live channels.
- No AI worker yet: `AI_MODE=mock` (default) analyses with a built-in stand-in labelled synthetic (the demo wall's six damages, DGDA priority with ASSUMED weights, work orders from `treatments.json`). `AI_MODE=remote` + `AI_URL` for the real one.
- Found: Jackson's snake_case writes `severity_xarea`, not the contract's `severity_x_area` (fixed with an explicit name); Hibernate cannot make unique constraints on MariaDB 10.4 (`SchemaFixes` makes the index with plain SQL).
- Checked: 30 Java tests pass, including Omar's v2 examples validated against the Java DTOs and the remote worker path against a stand-in server; a live upload against `hadaq_db` (202, analysed in under 3 s, site priority urgent with the Arabic reason). Not checked: the real AI worker and surveyor app (neither exists yet), the web review screen (not built).

### 2026-11-28 (Turki)

- Added `services/core`: the core API in Spring Boot (port 8080, as in contracts v2) serving the v1 contract like `services/api`, with the MQTT bridge, `/ws/live`, face hiding (YuNet via OpenCV), sensor health, the moisture rule, Basma's simulation and ABC risk ported to Java, and a built-in demo (ports of the sensor simulator, camera simulator and mock vision worker). It reads `contracts/building_b01.json` and the YuNet model from where they are, no copies.
- `make core`, `make core-test`; a `core` job in CI (MariaDB service). Web app against it: `VITE_API_URL=http://localhost:8080`.
- Checked: 23 Java tests pass (parity with `hadaq_sim` on golden outputs, every v1 route, websocket, sensor health, demo). Earlier on the same code: the sensor simulator over MQTT, camera, mock vision, agents in mock mode (English and Arabic) and the web app all worked against it. Not checked: v2 (not built), a real LLM key, face hiding on real photos of people.
- Found: XAMPP is MariaDB 10.4, where the MySQL JDBC driver drops fractions of seconds; `services/core` uses the MariaDB driver.
- Deleted the merged branch `turki/fix-name`; my work goes to the branch `Turki`.

### 2026-11-28 (Omar, Round 2 day 1)

- Froze contracts v2 for field inspections (surveys, captures, findings, reviews, work orders): `packages/hadaq_schemas/hadaq_schemas/inspection.py`, TypeScript mirror in `apps/web/src/types/contracts.ts`, spec in `contracts/README.md` section "v2", examples in `contracts/v2/examples/`, JSON Schemas for Java and Swift in `contracts/v2/schemas/`.
- The core API (Spring Boot, `services/core`, :8080) is the front door for the surveyor app and the web; Python services post results back to it.
- Damage type ids now follow the DGDA glossary: `rain_gullies` is `rain_grooves`, `holes_burrows` is `voids_burrows` (treatments, agents, web); Arabic names of plaster loss and salts match the glossary.
- `contracts/building_b01.json` W01 gained `width_m` (32.8, derived from base_line), `main_facade`, `visitor_route`, `original_fabric`.
- Checked: 85 Python tests pass, `npm run build` passes.

### 2026-11-27 (Turki)

- Fixed the spelling of my name: Turky is now Turki everywhere (docs, code comments, Makefile, `docs/team/turky.md` renamed to `docs/team/turki.md`). No code or contract change.

### 2026-11-22 (Omar, with an AI assistant)

- The Vercel site showed Offline and "no data": it had no API to talk to. Added a Render backend (`render.yaml`, `infra/render/`), see "Hosted demo". `sensor_sim`, `camera_sim` and the vision worker now take `main(argv)` so the hosted helper can run them in threads.
- Fixed the two-minute run test to a fixed start time (it failed at night in CI).

### 2026-11-21, later (Omar, with an AI assistant)

- Removed the Windows start and stop scripts (`tools/windows/`): the team runs each part in its own terminal. The PowerShell commands are under "Run it".
- Phase 1 focus on damage: hid the simulation player (play, scenario, hour slider) from the map; the Damage / Moisture / Risk buttons stay in a small pill with Damage as the default; the sun clock no longer jumps to the simulated hour; Moisture shows each zone's peak in the latest run. Simulation backend and the agent's drainage question untouched.
- Removed the "[mock agent ...]" label from the start of mock answers.
- Demo variety: the generated camera wall now shows older damage in every frame (holes R3C2 low, rain gullies R3C6, salt R1C7, broken parapet R4C7 and eroded base R1C4, all medium) besides the fine crack R2C5 (low, high once it grows), and the mock detector reports it. The risk grid now mixes low, medium, high and very high: `contracts/building_b01.json` `zones.value_weight_*` (ABC C, the heritage value of each zone) is set, ASSUMED for the demo and labelled so in the file. The mock agent lists damage most urgent first with a priority line, says when every sensor is healthy, and the chat suggestions cover each capability (damage by priority, Arabic treatment, one damage type, the expert report, sensor trust, drainage what-if).
- Agent answers are easier to read: the mock plan writes a bold title per finding or section and "- **Label:** value" bullets (zones as R2C5, sensors with their names, the drainage comparison with a one-line takeaway, moisture as % full), and the web renders them (`chat/AnswerText.tsx`: bold, bullets, evidence IDs as small chips that open the record). The LLM prompt asks for the same format.
- Map: the wall zone grid, the risk labels ("R1C1 · risk high") and the reading cards on the Qasr Salwa facade show only once the building (or one of its wall zones) is clicked; clicking elsewhere hides them again. The building page still shows everything. The first click on Qasr Salwa selects it and shows the cards; a second click opens its page. Other twin buildings still open their page on the first click. The Qasr Salwa view button only flies there; it no longer selects the building or turns the grid on.

### 2026-11-21, integration (Omar, with an AI assistant)

- Put all open work together on `omar/integration`: `abooda7m/agents` (damage and treatment agent, face hiding, damage map), `saleh/data-feeds` (two-minute sensor run, synthetic camera frames, synthetic provenance for mock detections) and `omar/sensors` (sensor health, wall model, sensor tools). Basma's and the older `omar/*` branches were already in `main`.
- Decisions while merging: Saleh's simulator tuning moved into `wall_model.py` (fault values, crack cap) and his timing test became `test_demo_run.py`; `K_RAIN` 0.38 to 0.5 and the run's storm 12 to 25 mm/h so the base passes 60 %rel at about 30 s as in his run; the run works over HTTP too (available when `sim-01` is online by MQTT status or fresh readings); the damage agent uses the crack gauge only when its status is `ok`; the mock agent's sensor lines exist in Arabic too; the run's drying label now says the base stays wet (it does: ponding); the camera simulator follows scenario events over HTTP when there is no broker.
- Checked: 63 Python tests pass (`make test` paths), `npm run build` passes; live runs in the container: MQTT run (storm, anomalies on S07 and S04 at 27 s and 35 s, S04 fault 60 to 105 s and back to `ok`, crack 1.20 to 1.55 mm), HTTP run with `--include-real` (nine sensors, devices online), the mock chat in English (fastest rising sensor, faulty S04 left out) and Arabic, and camera + vision + damage report citing the crack gauge reading. Not checked: the real LLM, the web app in a browser on the merged branch (checked on Omar's laptop after the pull).

### 2026-11-21 (Omar, with an AI assistant)

- Built the sensor part end to end so the agents can read it. New `devices/sensor-sim/wall_model.py`: rain wets the base most, water ponds at the wall foot and keeps the base wet after the rain, base dries slower, daily temperature and humidity, crack and tilt; fault modes spike, stuck and dropout. `sensor_sim.py` now talks MQTT or falls back to HTTP (no Docker needed).
- Added S07, S08, S09 to the registry (base north corner, mid-wall, tilt).
- API: `services/api/hadaq_api/sensors.py` (health and trend per sensor), new routes `/buildings/{b}/sensors`, `/sensors/{id}`, `/zones/{zone_id}`, `/devices`; device online/offline from the MQTT last will; the live `reading` message now carries `status` and `reasons`; faulty, implausible or stuck sensors no longer raise moisture anomalies.
- Agents: `get_sensor_overview`, `get_sensor_history`, `get_zone_detail`; `get_building_status` includes each sensor's status; the mock plan reports the fastest rising sensor and lists the sensors it did not use. One prompt line added.
- Web: trend line and status (fault, check, stale) per sensor in the Building twin panel and the building page (`panel/SensorList.tsx`); facade cards update in place instead of being rebuilt on every reading (they used to flicker and could miss clicks); a **Stuck sensor** button in the demo controls.
- Checked: all tests pass (28, with new ones for the wall model, sensor health and the sensor tools); `npm run build` passes; ran Mosquitto + API + agents (mock) + simulator together: storm, anomaly, automatic investigation citing the sensor trend, spike, stuck, dropout, reset, and the HTTP fallback with no broker.

### 2026-11-21, site run uses live feed (Saleh data feeds)

- Replaced the browser-generated sensor overlay with API-timed scenario events. Play resets and starts the storm, stops it at 35 seconds, faults S04 at 60 seconds and steps S06 at 75 seconds. The device simulator publishes readings over MQTT, the API persists and broadcasts them, and the site plots those actual readings. Replay and reset control the same simulator. Added broker/device availability checks and a scheduler test; web build and 20 Python tests passed.

### 2026-11-21, site sensor playback (Saleh data feeds)

- Added a browser-only two-minute B01 playback in Activity with storm, slow wall drying, S04 fault and recovery, and an S06 crack step. Its synthetic readings update the map callouts, Building twin panel and B01 building page; the latter now uses the registry sensor list when the API is offline. Existing live controls remain available when connected and playback is cleared. Web build and 19 Python tests passed.

### 2026-11-21, full B01 sensor run (Saleh data feeds)

- Ran `demo_reset` → `storm_started` (30 s) → `storm_ended` (24 s) → `sensor_fault` (through recovery) → `new_crack` → `demo_reset` against the live MQTT broker and API. All six sensor timestamps advanced. S01/S04 reached 64.3 %rel; S05 reached 29.8 %rel; humidity rose 28.3 to 77.7 %RH and temperature fell 28.7 to 25.3 degC. S04 reported `fault` at 8.0 %rel, recovered to `ok` after 45 s, S06 rose to 1.602 mm, and reset returned S04 to 18.2 %rel and S06 to 1.196 mm. No code changes.

### 2026-11-21 (Saleh data feeds)

- Tuned the sensor simulator's storm, drying, humidity, temperature, fault and crack curves for the two-minute demo without changing IDs, topics or provenance; added a 30-second scenario timing test to `make test`.
- With no wall photo available, added deterministic locally generated baseline/damage frames as a camera fallback and aligned the mock detector to the measured generated wall bounds. The API now marks mock `detection_ready` events synthetic.
- Python suite: 19 passed with pytest plugin autoload disabled because this host's application control blocks the `xxhash` DLL. Web build passed. Two fresh MQTT/API sensor cycles reached 62–63 %rel at the base within 27 seconds, showed fault quality and a stepped crack. Two camera/API/vision cycles labeled the R2C5 crack grown. The web displayed the photo, live readings and 3D attention box; made narrow crack outlines easier to see. Two mock agent drainage queries succeeded; Drive sharing and an on-laptop rehearsal remain pending.

### 2026-11-21 (abooda7m)

- Read the challenge page (requirements and scoring) and wrote `docs/challenge-plan.md` (Arabic): the idea, what exists, the gaps, the team's treatment table, a prioritised plan, a proposed demo story and open questions.
- Built the Damage and Treatment agent in `services/agents/`: `hadaq_agents/treatments.json` (the team's table: 11 damage types in Arabic and English, detector rules, crack-width thresholds), `knowledge.py` (the rules), four tools (`get_damage_findings`, `get_treatment`, `list_damage_types`, `propose_damage_report`), and `ask_damage_advisor` for the Orchestrator. A photo with new or grown damage now produces a damage report on its own. Trigger cooldowns are per track (damage, moisture). Mock mode answers damage questions in Arabic and English. Reference: `services/agents/README.md`.
- Web: damage reports show as cards in the Activity feed, "Vision finished" lines with no change are hidden, chat suggestions cover damage (one in Arabic), chat text and suggestions use `dir="auto"`.
- Later the same day: the **Damage map** on the building page and the **Damage** layer (grid and 3D); **face hiding** before photos are stored (`privacy.py`, YuNet model with its MIT licence in `services/api/hadaq_api/models/`, `opencv-python-headless` added to the API); LLM settings fixed for the current models (the old `temperature=0` is rejected by them with a 400), fallback to the labelled fixed plan when the LLM fails, `llm_ready` in `/health`; one finding per zone and class when a photo was analysed twice.
- No change to `contracts/` (new details only inside event payloads).
- Checked: `make test` 47 passed (30 new, including a contract test of the real LLM client against a stand-in Messages API); `npm run build` passes; live runs of the API, agents (mock), vision worker and web app: a damage report about half a second after a photo, a second high-priority report when the crack grew, a storm investigation on its own track; Chrome screenshots of the damage map, the Damage layer on the grid and the 3D wall, the privacy badge and an Arabic chat answer. Not checked: the real LLM (no key on this machine), face hiding on real photos of people.
- Merged the latest `main` (PR #4 and #6) into `abooda7m/agents`.

### 2026-11-20, evening (Omar, with an AI assistant)

- Reviewed and merged Basma's PR #3 (`basma/storm-data`): `contracts/building_b01.json` now has `heavy_rain_days_per_year` 1.91 and `demo_storm_date` 2023-01-05 from Open-Meteo ERA5. JSON valid, merged clean; the change in the A score is small.
- Found that nothing runs a replay yet, so the real storm is not used in the demo; added it to Known issues and Next steps. The numbers were not re-checked independently (Open-Meteo was not reachable from the review session).

### 2026-11-20, later (Omar, with an AI assistant)

- Picked UI direction 3 (Courtyard HUD) from five mockups, with the Layers menu from direction 2. Brief and mockups: the "Hadaq UI redesign brief" doc and the "Hadaq UI: five Diriyah directions" canvas on claude.ai.
- Built it: top tabs and the floating panel are gone. Explore = map only; Building twin, Agent and Activity open the bottom-right panel (click the tab again or X to close). Layers is a button top right that opens a menu (click outside or Escape closes it). Views moved bottom left with "About and sources" (model facts, honesty notes, sources). The clock is a sun arc you can drag.
- Sensor readings are pinned to their wall zones with leader lines when the camera is close to Qasr Salwa and the wall zones are on (`setCallouts` in `twin3d/Twin.ts`, built in `twin3d/focus.ts`). Click a card to open that zone. With the panel open they all move to the left so the panel does not hide them.
- The Qasr Salwa view also selects B01, so the Building twin tab shows it straight away. Offline, the sensor list comes from the registry file ("no data").
- Removed `panel/ExplorePanel.tsx` (its parts moved to `hud/`).
- Checked: `npm run build` passes; screenshots at 1440 x 900, 1366 x 768 and 390 x 844.
- `omar/turaif-district` was force-pushed once to fix the committer name on the last two commits (no code change). If you pulled it before, run `git pull --rebase` once.
- Handed out two tasks: the agents (real LLM, autonomy, more tools) and the sensor and camera data feeds for B01 (sensor simulator tuning, camera photo series, vision worker). See `docs/team/omar.md`, `saleh.md` and `abdurhmn.md`.

### 2026-11-20 (Omar, with an AI assistant)

- Switched off five map layers (car parks and cars, lanterns, street lights, visitors, helipads): hidden from the Explore panel and the scene, code kept (`DISABLED_LAYERS` in `twin3d/Twin.ts`).
- Renamed B01 to Qasr Salwa (قصر سلوى) on the map, the panels and `contracts/building_b01.json` (`building.name` only; id stays B01).
- Added full building pages at `/building/<id>` (`src/building/BuildingPage.tsx`, `ModelViewer.tsx`, list in `src/twins.ts`): 3D view, wall condition grid with Moisture / Risk, attention list, sensors, cameras, activity, agent. The map stays loaded and paused behind them; browser back works.
- Clicking Qasr Salwa opens its page with our model. Clicking "palace of Nasr" opens the AL Turaif House page with the Sketchfab scan. The scan popup is no longer used (`ScanViewer.tsx` kept).
- Removed the ornament from the floating panel header.
- Toned down the daytime look: bloom only at golden hour and night, exposure and fill light drop as the sun climbs (`setTime` in `twin3d/Twin.ts`), so noon no longer washes out white.
- Checked: `npm run build` passes; both pages and the back button checked in the browser.

### 2026-11-19 to 2026-11-20 (Omar, with an AI assistant)

- Replaced the Cesium + Google 3D map with a three.js twin modelled on the Budots Media Diriyah twin: imagery-draped terrain, about 10k low-detail buildings, palms, roads, water, car parks, lanterns, simulated visitors, real sun for any date and time, bloom at night.
- Added the open-data bake (`bake.html`, `src/bake/bake.ts`, `tools/site_data/README.md`) and committed the baked pack in `apps/web/public/site/`.
- Placed the Turaif citadel model (`b01_v3.glb`) at B01 with flattened ground, wall zones and attention boxes; clicking it still opens the AL Turaif House scan.
- New UI in the Budots style: top tabs (Explore, Building twin, Agent, Activity), floating panel with views, layers and sources, bottom day clock with a simulation row.
- Removed Cesium (`cesium`, `vite-plugin-static-copy`, `src/twin/district.ts`, `geo.ts`, `focusModel.ts`); added `three`, `three-mesh-bvh`, `@types/three`, `@types/node`.
- Updated README, AGENTS.md, CLAUDE.md rule for this file, `docs/demo-script.md`, `docs/scan-guide.md`, and the Omar, Turki and Saleh team guides.
- Checked: `npm run build` (typecheck + build) passes; views, hover, click, scan popup and night lighting checked in the browser.
