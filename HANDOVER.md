# Hadaq handover

Read this first when you start a session (human or AI). Update it before you end one.

**How to update:** rewrite "Current state", "Known issues" and "Next steps" so they stay true (do not just append), then add one entry at the top of "Session log". Keep it short: what changed, why, what is left.

---

## Current state (2026-11-21)

| Area | State | Owner |
| --- | --- | --- |
| Web app (`apps/web`) | Working. Budots-style 3D twin of Diriyah in three.js with the **Courtyard HUD** (design 3): the map is full screen and four corner towers sit over it (brand and tabs top left, status and the Layers menu top right, views and About bottom left, the panel bottom right), a sun-arc clock bottom centre, and sensor readings pinned to the B01 facade. Clicking a twin building opens its own page at `/building/<id>` (not a popup) | Omar (twin), Turky (shell) |
| Building pages (`src/building/`, list in `src/twins.ts`) | **B01 Qasr Salwa (قصر سلوى)**: our model with the wall zone grid, wall condition, sensors, camera, activity, agent. **B02 AL Turaif House**: opened from "palace of Nasr" on the map, shows the Sketchfab scan with placeholders (no registry, sensors or camera yet) | Omar |
| 3D map data (`apps/web/public/site/`) | Baked and committed (about 9 MB): OSM + Microsoft footprints, Esri imagery, AWS terrain, 76k palms. No keys needed | Omar |
| Focus building model | B01 is named Qasr Salwa (قصر سلوى). Model `apps/web/public/models/b01_v3.glb` (decimated Meshy citadel from `assets/turaif/`), placed by `contracts/building_b01.json` `base_line` and `azimuth_deg` | Omar |
| Real scan | AL Turaif House (Sketchfab embed, by ATS) is the 3D view of the B02 page. Our own photogrammetry scan is not done | Omar |
| Sensors (B01) | **Working end to end.** 9 sensors (S01 to S09: base, mid and upper moisture, temperature, humidity, crack gauge, tilt) from `devices/sensor-sim/` over MQTT, or HTTP when there is no broker. The API gives each sensor a health status (ok, suspect, fault, stale, no_data) and a trend (`GET /buildings/B01/sensors`, `/sensors/{id}`, `/zones/{zone_id}`, `/devices`); the agents read them through `get_sensor_overview`, `get_sensor_history`, `get_zone_detail`; the web shows a trend line and the status per sensor. The brick node (S01 to S03) is still simulated with `--include-real` | Saleh, Turky |
| API, simulation, agents, vision, devices | Scaffolded and tested (see each team guide in `docs/team/`) | Turky, Basma, Omar, Abdurhmn, Saleh |
| Simulation site data (`contracts/building_b01.json` `site.*`) | Set from Open-Meteo ERA5 by Basma (PR #3): `heavy_rain_days_per_year` 1.91 (2015 to 2025, ASSUMED 10 mm/day threshold), `demo_storm_date` 2023-01-05. Only used by `mode: "replay"` runs, which nothing sends yet (see Known issues) | Basma |

### Run it

```bash
make up && make api      # API on :8000
make agents              # agents on :8100 (HADAQ_LLM=mock works without a key)
make web                 # http://localhost:5173
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
| `apps/web/src/TimeBar.tsx` | Sun-arc clock (drag the sun, play, date, speed, Now) and the simulation pill above it |

## Known issues and honesty notes

- Map layers switched off for now (built but hidden, kept in code): car parks and cars, lanterns, street lights, visitors, helipads. Turn one back on by removing it from `DISABLED_LAYERS` in `apps/web/src/twin3d/Twin.ts`.
- B01 is called Qasr Salwa but stands at a placeholder spot west of the real Salwa Palace; the real palace is not a separate outline in our OSM data. Say "placeholder location" if asked, or move it with `base_line`.
- B02 ("AL Turaif House", the OSM "palace of Nasr") has no registry in `contracts/`, so its page has placeholders and the agent does not answer for it.
- Building heights are estimates unless OSM has them. The ruins filling At-Turaif are illustrative, not surveyed. Visitors and cars are simulated. Say this on the slide.
- B01 is an AI-generated model at a placeholder spot, not a survey of a real building.
- Esri imagery is fine for a non-commercial demo with the attribution kept (Explore, About).
- Chrome slows the 3D view to about 1 fps when its window is hidden or covered. That is the browser, not the app. Measured on Omar's laptop (AMD integrated GPU): about 27 ms per frame, roughly 35 fps.
- The real storm (`demo_storm_date`) is not used in the demo yet: the web client (`apps/web/src/api/client.ts` `runSimulation`) and the agent tools (`run_rain_simulation`, `compare_scenarios`) all send `mode: "synthetic_storm"`. The API only fills `demo_storm_date` for `mode: "replay"` with no date.
- A replay run needs the internet or a filled weather cache (`data/weather_cache/`). Offline with no cache, the simulation job fails; there is no fallback to the synthetic storm yet.
- Sensor readings carry real timestamps, but the simulator compresses a day into two minutes (`--speed 720`). Rates "per minute" therefore look fast, and the anomaly rate rule (it needs 15 real minutes of history) never fires in a short demo; the absolute 60 %rel rule does.
- A stuck sensor is only caught while another sensor of the same kind is moving, so press **Stuck sensor** during a storm. A wall that is calm everywhere is never flagged (on purpose: the real brick node can sit at one value for minutes).
- `contracts/building_b01.json` gained S07 (base moisture R1C1), S08 (mid-wall moisture R2C3) and S09 (tilt R4C5, unit `deg`). The building page and the facade cards show them; when the panel is open only the zones that need attention get a card.
- `tools/turaif_model/` (the old Cesium district builder) is superseded and kept only for reference.
- `apps/web/.env.local` files from the Cesium version still have `VITE_CESIUM_ION_TOKEN`, `VITE_DISTRICT_URL`, `VITE_GOOGLE_3D`. They are unused and can be deleted.

## Next steps

- **Everyone:** pull `omar/turaif-district` (or `main` once merged), `npm install` in `apps/web`, open the app and check your part still shows up in the new tabs.
- **Omar:** merge `omar/turaif-district` into `main`; fill the building pages (wall condition details, camera per wall, per-building agent context); register B02 in `contracts/` when it gets sensors; real LLM answers with evidence chips; check the wall zones line up with the B01 facade in the Focus building view; full demo run twice (`docs/demo-script.md`).
- **Turky:** app shell is now `App.tsx` + `hud/` + `twin3d/`; panel polish in the Building twin tab (it now lives in the bottom-right tower: one screen at 1080p, no scrolling for key info).
- **Saleh:** the simulator side is done (see `docs/team/saleh.md`). Left: build the brick node and run the simulator without `--include-real` once it streams; film the water-pour shot; tune `wall_model.py` if the storm curve looks too fast or slow on the demo laptop.
- **Whoever does the agents:** the sensor tools are in `tools.py` and both agents have them; with a real LLM, check it skips sensors that are not `ok` and cites the `rd_` evidence IDs from the trends.
- **Basma:** share the `hadaq_sim.cli storms` row for 2023-01-05 (total mm and peak mm/h) for the slide; run a replay once on the demo laptop to fill `data/weather_cache/`. Moisture and Risk toggles now sit in the simulation pill above the sun clock.
- **Omar or Turky:** switch the web client and the agent tools to `mode: "replay"` so the demo uses the real storm, and make the API fall back to the synthetic storm if the weather fetch fails offline.
- **Abdurhmn:** demo video shots changed again (HUD layout, views bottom left, readings pinned on the facade in the Qasr Salwa view), see `docs/demo-script.md`.

---

## Session log

### 2026-11-21 (Omar, with an AI assistant)

- Built the sensor part end to end so the agents can read it. New `devices/sensor-sim/wall_model.py`: rain wets the base most, water ponds at the wall foot and keeps the base wet after the rain, base dries slower, daily temperature and humidity, crack and tilt; fault modes spike, stuck and dropout. `sensor_sim.py` now talks MQTT or falls back to HTTP (no Docker needed).
- Added S07, S08, S09 to the registry (base north corner, mid-wall, tilt).
- API: `services/api/hadaq_api/sensors.py` (health and trend per sensor), new routes `/buildings/{b}/sensors`, `/sensors/{id}`, `/zones/{zone_id}`, `/devices`; device online/offline from the MQTT last will; the live `reading` message now carries `status` and `reasons`; faulty, implausible or stuck sensors no longer raise moisture anomalies.
- Agents: `get_sensor_overview`, `get_sensor_history`, `get_zone_detail`; `get_building_status` includes each sensor's status; the mock plan reports the fastest rising sensor and lists the sensors it did not use. One prompt line added.
- Web: trend line and status (fault, check, stale) per sensor in the Building twin panel and the building page (`panel/SensorList.tsx`); facade cards update in place instead of being rebuilt on every reading (they used to flicker and could miss clicks); a **Stuck sensor** button in the demo controls.
- Checked: all tests pass (28, with new ones for the wall model, sensor health and the sensor tools); `npm run build` passes; ran Mosquitto + API + agents (mock) + simulator together: storm, anomaly, automatic investigation citing the sensor trend, spike, stuck, dropout, reset, and the HTTP fallback with no broker.

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
- Updated README, AGENTS.md, CLAUDE.md rule for this file, `docs/demo-script.md`, `docs/scan-guide.md`, and the Omar, Turky and Saleh team guides.
- Checked: `npm run build` (typecheck + build) passes; views, hover, click, scan popup and night lighting checked in the browser.
