# Hadaq handover

Read this first when you start a session (human or AI). Update it before you end one.

**How to update:** rewrite "Current state", "Known issues" and "Next steps" so they stay true (do not just append), then add one entry at the top of "Session log". Keep it short: what changed, why, what is left.

---

## Current state (2026-11-20)

| Area | State | Owner |
| --- | --- | --- |
| Web app (`apps/web`) | Working. Budots-style 3D twin of Diriyah in three.js, focus building B01 with wall zones and attention boxes, Explore / Building twin / Agent / Activity tabs, day clock with real sun | Omar (twin), Turky (shell) |
| 3D map data (`apps/web/public/site/`) | Baked and committed (about 9 MB): OSM + Microsoft footprints, Esri imagery, AWS terrain, 76k palms. No keys needed | Omar |
| Focus building model | `apps/web/public/models/b01_v3.glb` (decimated Meshy citadel from `assets/turaif/`), placed by `contracts/building_b01.json` `base_line` and `azimuth_deg` | Omar |
| Real scan | AL Turaif House (Sketchfab embed, by ATS) opens when you click B01. Our own photogrammetry scan is not done | Omar |
| API, simulation, agents, vision, devices | Scaffolded and tested (see each team guide in `docs/team/`). Not changed in the last session | Turky, Basma, Omar, Abdurhmn, Saleh |

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
| `apps/web/src/App.tsx`, `TimeBar.tsx`, `panel/ExplorePanel.tsx` | Layout, clock bar, Explore panel |

## Known issues and honesty notes

- Building heights are estimates unless OSM has them. The ruins filling At-Turaif are illustrative, not surveyed. Visitors and cars are simulated. Say this on the slide.
- B01 is an AI-generated model at a placeholder spot, not a survey of a real building.
- Esri imagery is fine for a non-commercial demo with the attribution kept (Explore, About).
- Chrome slows the 3D view to about 1 fps when its window is hidden or covered. That is the browser, not the app. Measured on Omar's laptop (AMD integrated GPU): about 27 ms per frame, roughly 35 fps.
- `tools/turaif_model/` (the old Cesium district builder) is superseded and kept only for reference.
- `apps/web/.env.local` files from the Cesium version still have `VITE_CESIUM_ION_TOKEN`, `VITE_DISTRICT_URL`, `VITE_GOOGLE_3D`. They are unused and can be deleted.

## Next steps

- **Everyone:** pull `omar/turaif-district` (or `main` once merged), `npm install` in `apps/web`, open the app and check your part still shows up in the new tabs.
- **Omar:** merge `omar/turaif-district` into `main`; real LLM answers with evidence chips; check the wall zones line up with the B01 facade in the Focus building view; full demo run twice (`docs/demo-script.md`).
- **Turky:** app shell is now `App.tsx` + `twin3d/`; panel polish in the Building twin tab (one screen at 1080p, no scrolling for key info).
- **Saleh:** scenario buttons moved from the bottom bar to the **Activity** tab; the phone page `?panel=1` is unchanged.
- **Basma:** no web changes needed; Moisture and Risk toggles now sit in the simulation row under the clock.
- **Abdurhmn:** demo video shots changed (tabs and views), see the updated `docs/demo-script.md`.

---

## Session log

### 2026-11-19 to 2026-11-20 (Omar, with an AI assistant)

- Replaced the Cesium + Google 3D map with a three.js twin modelled on the Budots Media Diriyah twin: imagery-draped terrain, about 10k low-detail buildings, palms, roads, water, car parks, lanterns, simulated visitors, real sun for any date and time, bloom at night.
- Added the open-data bake (`bake.html`, `src/bake/bake.ts`, `tools/site_data/README.md`) and committed the baked pack in `apps/web/public/site/`.
- Placed the Turaif citadel model (`b01_v3.glb`) at B01 with flattened ground, wall zones and attention boxes; clicking it still opens the AL Turaif House scan.
- New UI in the Budots style: top tabs (Explore, Building twin, Agent, Activity), floating panel with views, layers and sources, bottom day clock with a simulation row.
- Removed Cesium (`cesium`, `vite-plugin-static-copy`, `src/twin/district.ts`, `geo.ts`, `focusModel.ts`); added `three`, `three-mesh-bvh`, `@types/three`, `@types/node`.
- Updated README, AGENTS.md, CLAUDE.md rule for this file, `docs/demo-script.md`, `docs/scan-guide.md`, and the Omar, Turky and Saleh team guides.
- Checked: `npm run build` (typecheck + build) passes; views, hover, click, scan popup and night lighting checked in the browser.
