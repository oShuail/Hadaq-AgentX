# Hadaq handover

Read this first when you start a session (human or AI). Update it before you end one.

**How to update:** rewrite "Current state", "Known issues" and "Next steps" so they stay true (do not just append), then add one entry at the top of "Session log". Keep it short: what changed, why, what is left.

---

## Current state (2026-11-21)

| Area | State | Owner |
| --- | --- | --- |
| Web app (`apps/web`) | Working. Budots-style 3D twin of Diriyah in three.js with the **Courtyard HUD** (design 3): the map is full screen and four corner towers sit over it (brand and tabs top left, status and the Layers menu top right, views and About bottom left, the panel bottom right), a sun-arc clock bottom centre, and sensor readings pinned to the B01 facade. Clicking a twin building opens its own page at `/building/<id>` (not a popup). The Activity tab has a two-minute B01 sensor playback that works without the API and updates the map, twin panel and building page | Omar (twin), Turky (shell), Saleh (sensor playback) |
| Building pages (`src/building/`, list in `src/twins.ts`) | **B01 Qasr Salwa (قصر سلوى)**: our model with the wall zone grid, wall condition, sensors, camera, activity, agent. **B02 AL Turaif House**: opened from "palace of Nasr" on the map, shows the Sketchfab scan with placeholders (no registry, sensors or camera yet) | Omar |
| 3D map data (`apps/web/public/site/`) | Baked and committed (about 9 MB): OSM + Microsoft footprints, Esri imagery, AWS terrain, 76k palms. No keys needed | Omar |
| Focus building model | B01 is named Qasr Salwa (قصر سلوى). Model `apps/web/public/models/b01_v3.glb` (decimated Meshy citadel from `assets/turaif/`), placed by `contracts/building_b01.json` `base_line` and `azimuth_deg` | Omar |
| Real scan | AL Turaif House (Sketchfab embed, by ATS) is the 3D view of the B02 page. Our own photogrammetry scan is not done | Omar |
| API, simulation, agents, vision, devices | Scaffolded and tested (see each team guide in `docs/team/`). On `saleh/data-feeds`, a full live sensor scenario passed through MQTT and the API: all six readings fresh, storm rise, drying, fault recovery, crack step and reset. The camera simulator generates a fixed synthetic wall series locally when no photos are present; API and vision recognize the growing R2C5 crack with synthetic provenance | Turky, Basma, Omar, Abdurhmn, Saleh |

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

- The B01-CAM01 photo folders are empty in git by design. Without an actual wall photo, `make camera` generates illustrative synthetic artwork at 1600 x 1200 with wall bounds `[80, 80, 1520, 1120]`. Replace both folders and remeasure the bounds when the real fixed-camera series is available; no photos have been shared to Drive.
- The Activity tab's browser sensor playback is local and does not publish MQTT events or persist API readings. With the backend connected, the existing live scenario controls are available whenever playback is cleared. Camera frames and crack detection still require the API, camera and vision services.
- This Windows/WSL host stops its Docker MQTT broker when WSL becomes idle. Keeping WSL alive enabled two fresh end-to-end sensor cycles and a complete timed sensor scenario including fault recovery. Camera upload, worker comparison and the synthetic provenance event passed twice through the API. The web shows sensor values, the photo detection boxes and the R2C5 attention box on the 3D wall; two mock agent drainage answers returned. This is an illustrative demo, not real photos or a calibrated model.
- Map layers switched off for now (built but hidden, kept in code): car parks and cars, lanterns, street lights, visitors, helipads. Turn one back on by removing it from `DISABLED_LAYERS` in `apps/web/src/twin3d/Twin.ts`.
- B01 is called Qasr Salwa but stands at a placeholder spot west of the real Salwa Palace; the real palace is not a separate outline in our OSM data. Say "placeholder location" if asked, or move it with `base_line`.
- B02 ("AL Turaif House", the OSM "palace of Nasr") has no registry in `contracts/`, so its page has placeholders and the agent does not answer for it.
- Building heights are estimates unless OSM has them. The ruins filling At-Turaif are illustrative, not surveyed. Visitors and cars are simulated. Say this on the slide.
- B01 is an AI-generated model at a placeholder spot, not a survey of a real building.
- Esri imagery is fine for a non-commercial demo with the attribution kept (Explore, About).
- Chrome slows the 3D view to about 1 fps when its window is hidden or covered. That is the browser, not the app. Measured on Omar's laptop (AMD integrated GPU): about 27 ms per frame, roughly 35 fps.
- `tools/turaif_model/` (the old Cesium district builder) is superseded and kept only for reference.
- `apps/web/.env.local` files from the Cesium version still have `VITE_CESIUM_ION_TOKEN`, `VITE_DISTRICT_URL`, `VITE_GOOGLE_3D`. They are unused and can be deleted.

## Next steps

- **Saleh and Abdurhmn:** rehearse the complete two-minute shot sequence on the demo laptop with all services running, and share the photo series to the team Drive if needed. Replace the illustrative series with a real straight-on source photo and remeasure `wall_bbox_px` when available.
- **Everyone:** pull `omar/turaif-district` (or `main` once merged), `npm install` in `apps/web`, open the app and check your part still shows up in the new tabs.
- **Omar:** merge `omar/turaif-district` into `main`; fill the building pages (wall condition details, camera per wall, per-building agent context); register B02 in `contracts/` when it gets sensors; real LLM answers with evidence chips; check the wall zones line up with the B01 facade in the Focus building view; full demo run twice (`docs/demo-script.md`).
- **Turky:** app shell is now `App.tsx` + `hud/` + `twin3d/`; panel polish in the Building twin tab (it now lives in the bottom-right tower: one screen at 1080p, no scrolling for key info).
- **Saleh:** use Activity → Play sensor run for the standalone two-minute sensor playback. Clear it to return to live API readings and the scenario buttons. The phone page `?panel=1` is unchanged.
- **Basma:** no web changes needed; Moisture and Risk toggles now sit in the simulation pill above the sun clock.
- **Abdurhmn:** demo video shots changed again (HUD layout, views bottom left, readings pinned on the facade in the Qasr Salwa view), see `docs/demo-script.md`.

---

## Session log

### 2026-11-21, site sensor playback (Saleh data feeds)

- Added a browser-only two-minute B01 playback in Activity with storm, slow wall drying, S04 fault and recovery, and an S06 crack step. Its synthetic readings update the map callouts, Building twin panel and B01 building page; the latter now uses the registry sensor list when the API is offline. Existing live controls remain available when connected and playback is cleared. Web build and 19 Python tests passed.

### 2026-11-21, full B01 sensor run (Saleh data feeds)

- Ran `demo_reset` → `storm_started` (30 s) → `storm_ended` (24 s) → `sensor_fault` (through recovery) → `new_crack` → `demo_reset` against the live MQTT broker and API. All six sensor timestamps advanced. S01/S04 reached 64.3 %rel; S05 reached 29.8 %rel; humidity rose 28.3 to 77.7 %RH and temperature fell 28.7 to 25.3 degC. S04 reported `fault` at 8.0 %rel, recovered to `ok` after 45 s, S06 rose to 1.602 mm, and reset returned S04 to 18.2 %rel and S06 to 1.196 mm. No code changes.

### 2026-11-21 (Saleh data feeds)

- Tuned the sensor simulator's storm, drying, humidity, temperature, fault and crack curves for the two-minute demo without changing IDs, topics or provenance; added a 30-second scenario timing test to `make test`.
- With no wall photo available, added deterministic locally generated baseline/damage frames as a camera fallback and aligned the mock detector to the measured generated wall bounds. The API now marks mock `detection_ready` events synthetic.
- Python suite: 19 passed with pytest plugin autoload disabled because this host's application control blocks the `xxhash` DLL. Web build passed. Two fresh MQTT/API sensor cycles reached 62–63 %rel at the base within 27 seconds, showed fault quality and a stepped crack. Two camera/API/vision cycles labeled the R2C5 crack grown. The web displayed the photo, live readings and 3D attention box; made narrow crack outlines easier to see. Two mock agent drainage queries succeeded; Drive sharing and an on-laptop rehearsal remain pending.

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
