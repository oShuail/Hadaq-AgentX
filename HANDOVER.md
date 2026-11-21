# Hadaq handover

Read this first when you start a session (human or AI). Update it before you end one.

**Challenge requirements, gaps and plan:** [docs/challenge-plan.md](docs/challenge-plan.md) (Arabic). **Agents reference:** [services/agents/README.md](services/agents/README.md).

**How to update:** rewrite "Current state", "Known issues" and "Next steps" so they stay true (do not just append), then add one entry at the top of "Session log". Keep it short: what changed, why, what is left.

---

## Current state (2026-11-21)

| Area | State | Owner |
| --- | --- | --- |
| Web app (`apps/web`) | Working. Budots-style 3D twin of Diriyah in three.js with the **Courtyard HUD** (design 3): the map is full screen and four corner towers sit over it (brand and tabs top left, status and the Layers menu top right, views and About bottom left, the panel bottom right), a sun-arc clock bottom centre, and sensor readings pinned to the B01 facade. Clicking a twin building opens its own page at `/building/<id>` (not a popup) | Omar (twin), Turky (shell) |
| Building pages (`src/building/`, list in `src/twins.ts`) | **B01 Qasr Salwa (قصر سلوى)**: our model with the wall zone grid, wall condition, sensors, camera, activity, agent. **B02 AL Turaif House**: opened from "palace of Nasr" on the map, shows the Sketchfab scan with placeholders (no registry, sensors or camera yet). The Activity feed shows damage reports as cards; the chat suggestions cover damage (one in Arabic) | Omar |
| 3D map data (`apps/web/public/site/`) | Baked and committed (about 9 MB): OSM + Microsoft footprints, Esri imagery, AWS terrain, 76k palms. No keys needed | Omar |
| Focus building model | B01 is named Qasr Salwa (قصر سلوى). Model `apps/web/public/models/b01_v3.glb` (decimated Meshy citadel from `assets/turaif/`), placed by `contracts/building_b01.json` `base_line` and `azimuth_deg` | Omar |
| Real scan | AL Turaif House (Sketchfab embed, by ATS) is the 3D view of the B02 page. Our own photogrammetry scan is not done | Omar |
| Agents (`services/agents/`) | Three LangGraph agents: Orchestrator, **Damage and Treatment** (new) and Simulation and Advisory. The damage agent maps the latest photo's detections onto the team's treatment table (`hadaq_agents/treatments.json`: 11 damage types in Arabic and English) and, on its own when a photo with new or grown damage is analysed, sends a damage report (type, zone, severity, treatment, materials, tools, source) for expert review. Mock mode answers in Arabic and English. The real LLM path is tested with a scripted model only (no key yet). See `services/agents/README.md` | agents task owner |
| Challenge fit (`docs/challenge-plan.md`, Arabic) | Organisers' requirements and scoring, what we have, gaps, prioritised plan. Built: damage types, treatments, materials and tools. Not built: real photos, face blurring, damage map with a colour legend, expert review screen, accuracy check against an expert | abooda7m |
| API, simulation, vision, devices | Scaffolded and tested (see each team guide in `docs/team/`). Not changed in the last session | Turky, Basma, Abdurhmn, Saleh |

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
- `tools/turaif_model/` (the old Cesium district builder) is superseded and kept only for reference.
- `apps/web/.env.local` files from the Cesium version still have `VITE_CESIUM_ION_TOKEN`, `VITE_DISTRICT_URL`, `VITE_GOOGLE_3D`. They are unused and can be deleted.
- The demo story (`docs/demo-script.md`) still centres on the storm. The challenge makes sensors optional and scores damage types, treatments, materials and tools from real photos, a clear damage map, expert correction and face blurring. Proposed new story: `docs/challenge-plan.md` section 9.
- Damage agent: the crack-width thresholds (2 mm, 10 mm) and the priority rule are ours (`assumed`). The treatment table was transcribed from a screenshot and its source document ("الهيئة") is not named yet. Incompatible repairs, timber decay and structural deformation cannot be found by the current detector classes.
- `omar/turaif-district` is already merged and is now behind `main` (Basma's storm data). Start new branches from `main`.
- Without Docker (no MQTT) the camera simulator cannot switch to the damage series. To test anyway, post frames to `POST /photos` with `sequence` and `frame`: the vision worker reads them from the photo record.

## Next steps

- **Everyone:** read `docs/challenge-plan.md` and answer its open questions (section 12). Pull `main`, then `make setup` and `npm install` in `apps/web` once.
- **Omar:** decide the new demo story and the contract changes for expert review and face blurring (`docs/challenge-plan.md` sections 9 and 10); fill the building pages (wall condition details, camera per wall, per-building agent context); register B02 in `contracts/` when it gets sensors; check the wall zones line up with the B01 facade in the Focus building view; full demo run twice.
- **Agents task owner:** put the LLM key in `.env` and run the chat suggestions in Arabic and English with the real model (no verifier warnings, damage report on a new photo); then build the approval step (approve, then a work order) with the expert review screen.
- **Turky:** app shell is now `App.tsx` + `hud/` + `twin3d/`; panel polish in the Building twin tab (it now lives in the bottom-right tower: one screen at 1080p, no scrolling for key info).
- **Saleh:** scenario buttons moved from the bottom bar to the **Activity** tab; the phone page `?panel=1` is unchanged.
- **Basma:** no web changes needed; Moisture and Risk toggles now sit in the simulation pill above the sun clock.
- **Abdurhmn:** demo video shots changed again (HUD layout, views bottom left, readings pinned on the facade in the Qasr Salwa view), see `docs/demo-script.md`.

---

## Session log

### 2026-11-21 (abooda7m)

- Read the challenge page (requirements and scoring) and wrote `docs/challenge-plan.md` (Arabic): the idea, what exists, the gaps, the team's treatment table, a prioritised plan, a proposed demo story and open questions.
- Built the Damage and Treatment agent in `services/agents/`: `hadaq_agents/treatments.json` (the team's table: 11 damage types in Arabic and English, detector rules, crack-width thresholds), `knowledge.py` (the rules), four tools (`get_damage_findings`, `get_treatment`, `list_damage_types`, `propose_damage_report`), and `ask_damage_advisor` for the Orchestrator. A photo with new or grown damage now produces a damage report on its own. Trigger cooldowns are per track (damage, moisture). Mock mode answers damage questions in Arabic and English. Reference: `services/agents/README.md`.
- Web: damage reports show as cards in the Activity feed, "Vision finished" lines with no change are hidden, chat suggestions cover damage (one in Arabic), chat text and suggestions use `dir="auto"`.
- No change to `contracts/`.
- Checked: `make test` 39 passed (22 new); `npm run build` passes; a live run of the API, agents (mock) and vision worker gave a damage report about half a second after a photo, a second high-priority report when the crack grew, and a storm investigation on its own track; a screenshot of the Qasr Salwa page shows the report card. Not checked: the real LLM (no key on this machine).

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
