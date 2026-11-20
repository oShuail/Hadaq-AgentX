# Turky: API, database, MQTT, shared formats, map shell

**Mission:** you own the hub. Every reading, photo, detection, simulation and event flows through your API, gets stored, gets an evidence ID, and is pushed live to the browser. You also own the shared formats and the app shell: the 3D map, click-to-select and the side panel.

## What you own

| Path | What |
| --- | --- |
| `services/api/hadaq_api/main.py` | Routes, event handling, agent forwarding, simulation jobs |
| `services/api/hadaq_api/db.py` | Tables and queries (SQLite now, Postgres later) |
| `services/api/hadaq_api/mqtt_bridge.py` | Subscribes to `hadaq/v1/#` |
| `services/api/hadaq_api/live.py` | Websocket fan-out |
| `packages/hadaq_schemas/` | Shared Pydantic models |
| `contracts/README.md` | The written contract |
| `apps/web/src/App.tsx`, `map/SiteMap.tsx`, `panel/*`, `api/client.ts`, `state/useLive.ts`, `TimeBar.tsx`, `styles.css` | App shell |
| `docker-compose.yml`, `infra/`, `.github/workflows/ci.yml` | Infra and CI |

## Setup

```bash
make setup && make up
make api             # http://localhost:8000/docs
.venv/bin/pytest -q services/api/tests packages/hadaq_schemas/tests
```

## How the API works (you will be asked)

- Devices and simulators publish to MQTT; `MqttBridge` validates each message with the shared model and calls `handle_reading` or `handle_event`.
- `handle_reading` stores the reading (evidence ID `rd_<row>`), pushes it to `/ws/live`, and runs Basma's anomaly rule on moisture. An anomaly becomes a `moisture_anomaly` event.
- `handle_event` stores events idempotently (our own MQTT echo is ignored), pushes them live, republishes them to MQTT when they came over HTTP, and forwards `storm_started`, `moisture_anomaly` and `detection_ready` (with changes) to the agents.
- `POST /simulations` runs Basma's engine in a thread pool, stores the result, recomputes risk and emits `simulation_done`.
- `GET /evidence/{id}` resolves any evidence ID, so every number the agent cites can be opened.

## Tasks for Round 1, in order

### 1. Freeze the contracts tonight (with Omar and Saleh)

Walk through `contracts/README.md` with them. You own keeping it, `hadaq_schemas` and `apps/web/src/types/contracts.ts` in sync.

### 2. Run against a real broker

```bash
make api       # terminal 1
make sensors   # terminal 2
```

Open `/docs`, try `GET /buildings/B01/readings/latest`, then `POST /events` with `storm_started` and watch `moisture_anomaly` appear in `GET /buildings/B01/events`. Check `GET /health` shows `"mqtt": true`.

### 3. 3D map

1. `make web`. No keys needed: the map is a three.js scene (`apps/web/src/twin3d/`) built from the baked open-data pack in `apps/web/public/site/`.
2. You should see imagery-draped terrain, mud-style buildings in At-Turaif, palms, roads and our B01 model; the views (Overview, At-Turaif, Qasr Salwa ...) sit bottom left and the Layers button top right; hover shows a building card, click selects it and opens the Building twin tab.
3. The sun-arc clock at the bottom moves the real sun for any date and time (drag the sun); at night the floodlights come on.
4. Style ideas: colours and lighting live in `twin3d/buildings.ts` (palette, parapets, night glow) and `twin3d/Twin.ts` (`setTime`). To refresh the data, see `tools/site_data/README.md`.

### 4. Panel polish

`BuildingPanel.tsx` shows sensors (REAL / SIM tags), camera, activity, chat tab. Keep it to one screen in a 1080p video: no scrolling for the key info.

### 5. Add an endpoint (pattern)

```python
# services/api/hadaq_api/main.py
@app.get("/buildings/{building_id}/zones/{zone_id}")
def zone_detail(building_id: str, zone_id: str) -> dict:
    registry = _registry_for(building_id)
    sensors = [s for s in registry["sensors"] if s["zone_id"] == zone_id]
    risk = next((r for r in (_risk_cache.get(building_id) or compute_risk(building_id)) if r["zone_id"] == zone_id), None)
    return {"zone_id": zone_id, "sensors": sensors, "risk": risk}
```

Then add it to `contracts/README.md` and `apps/web/src/api/client.ts`, plus a test in `services/api/tests/test_api.py`.

### 6. Postgres (optional for Round 1)

```bash
docker compose --profile postgres up -d
pip install -e "services/api[postgres]"
# .env
DATABASE_URL=postgresql+psycopg://hadaq:hadaq@localhost:5432/hadaq
```

SQLite is fine for the video. Switch only if you have time.

### 7. Deployment

Not needed for Round 1 (the deliverable is a video). For Round 2: one Docker image per service, a `docker-compose.prod.yml`, and a small VPS or Render/Fly.

## Naming rules

- Routes: plural nouns, building-scoped (`/buildings/{id}/...`), `snake_case` query params.
- Tables: `<Thing>Row` with indexed columns plus the full JSON in `data`.
- Never rename a field in a model without a contracts PR.

## Contracts

- Produces: every HTTP response and live message in `contracts/README.md`.
- Consumes: MQTT readings and events, `DetectionBatch`, `SimulationRequest`.

## Definition of done (21 Nov)

- [ ] API runs with the real broker; readings, anomaly, simulation, risk, evidence all work
- [ ] Map with OSM buildings, presets, click-to-select, panel
- [ ] CI green on `main`
- [ ] Architecture slide (use the diagram in the architecture doc)

## Round 2

Postgres + TimescaleDB, auth, job queue for simulations, Docker deployment, 3D Tiles for our own scans, multi-building registry.
