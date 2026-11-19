# At-Turaif district model (superseded)

> Superseded by the three.js twin and its baked site pack: see [tools/site_data/README.md](../site_data/README.md). Kept for reference; the web app no longer reads `turaif.glb`.

`build_district.py` turns the real OpenStreetMap building outlines of At-Turaif into one 3D model (`apps/web/public/models/turaif.glb`) plus a small sidecar (`turaif.json`) that the web app reads for names, heights and outlines.

- Every building sits on its real outline, gets its OSM height (or an estimate from its size, flagged `estimated` in the JSON), a mud texture with a darker base, a parapet and a row of triangular crenellations. Courtyards stay open.
- One glTF node per building, named by its OSM id (`w123` for a way, `r456` for a relation), so a click in the app knows which building it hit, and the focus building can be hidden when our own model of it loads.
- It is a massing model for context, not a survey. Say so on the slide. OSM data is © OpenStreetMap contributors (ODbL).

## 1. Get the outlines (2 minutes, in the browser)

1. Open https://overpass-turbo.eu
2. Replace the query with this and press **Run**:

```
[out:json][timeout:60];
(
  way["building"](24.7295,46.5670,24.7375,46.5775);
  relation["building"](24.7295,46.5670,24.7375,46.5775);
);
out body;
>;
out skel qt;
```

3. **Export**, then **GeoJSON**, then **download**. Save it as `assets/turaif/turaif_osm.geojson` in this repo.

## 2. Build the model

```bash
.venv/bin/pip install mapbox-earcut          # numpy and pillow already come with the vision service
.venv/bin/python tools/turaif_model/build_district.py --input assets/turaif/turaif_osm.geojson
```

It prints how many buildings it made and the largest named ones with their node names (Salwa Palace should be in the list). Put that building's number in `apps/web/.env.local` as `VITE_FOCUS_OSM_ID=<number>` and in `contracts/building_b01.json` as `building.osm_element_id`.

## 3. See it

`make web`, open http://localhost:5173 and press **At-Turaif**. Inside the district the grey OSM boxes are replaced by this model; outside it they stay. Click a building: it gets a gold outline and opens in the panel. The focus building's own model (`VITE_FOCUS_MODEL_URL`) loads only when you open it, and its district copy hides.

`VITE_DISTRICT_URL=` (empty) in `.env.local` switches back to OSM boxes only.
