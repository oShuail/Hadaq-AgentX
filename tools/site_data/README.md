# Site data for the 3D twin

The map in `apps/web` is a three.js scene of Diriyah built from open data, in the style of the Budots Media
Diriyah twin: imagery-draped terrain, low-detail extruded buildings (Najdi mud style in the heritage
districts), date palms, roads, water, car parks, lanterns and simulated visitors, lit by the real sun for any
date and time. Our own model of the focus building (`apps/web/public/models/b01_v3.glb`, made from
`assets/turaif/`) stands at its spot in At-Turaif with the wall zones on its facade.

Everything the scene needs is baked once into `apps/web/public/site/` (about 9 MB):

| File | What |
| --- | --- |
| `site.json` | buildings, roads, paths, water, walls, car parks, place names, in local metres around At-Turaif |
| `terrain_core.bin`, `terrain_wide.bin` | heights (Float32), 10 m grid over 5.5 x 5 km, coarse grid out to 27 km |
| `sat_detail.jpg`, `sat_core.jpg`, `sat_wide.jpg` | satellite imagery, sharpest over At-Turaif |
| `palms.bin` | one palm per 9 m where the imagery shows palm canopy |

## Re-bake (only when you want fresh data)

1. `make web` (the bake needs the dev server: it writes files through it).
2. Open http://localhost:5173/bake.html and press **Bake**. It takes 2 to 4 minutes; the log shows each step.
3. Reload the app.

The bake runs in the browser (it needs canvas to stitch imagery). Sources:

- OpenStreetMap through the OSM API (Overpass as a fallback), © OpenStreetMap contributors, ODbL.
- Microsoft Global ML Building Footprints for the buildings OSM has no outline for (ODbL). Fetched through the
  dev server proxy (`/__proxy/msb`) because that host sends no CORS headers.
- Esri World Imagery (Esri, Maxar, Earthstar Geographics). Fine for a non-commercial demo with attribution.
- Terrain Tiles on AWS (Terrarium encoding; SRTM-class in Saudi Arabia).

## Honesty notes (say these on the slide)

- Building heights are estimates unless OSM has them (`hs` in `site.json`).
- At-Turaif is a dense town of mud ruins but only its restored core is mapped, so the bake fills the open parts
  of the district with low courtyard ruins on a loose grid when the app loads (`twin3d/infill.ts`, ids `i...`).
  They are illustrative, not surveyed.
- Palms come from the colour of the imagery, so a few dark shadows become palms and a few palms are missed.
- Visitors and parked cars are simulated and follow assumed opening hours.

To change the area or the Najdi style zones, edit the constants at the top of `apps/web/src/bake/bake.ts`.
