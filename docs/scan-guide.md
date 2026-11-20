# 3D scan of the focus building

The scan is **not** on the critical path. The app works today with our citadel model (`apps/web/public/models/b01_v3.glb`) as the focus building; a real scan replaces it when ready. Do the code setup first, scan when the light is right.

## When and how

- **Light:** overcast, or early morning / late afternoon with the facade in shade. No hard shadows, no night.
- **Permission:** photograph only from public paths where photography is allowed. No drone without GACA and site permission. Never touch the walls.
- **Phone app:** Scaniverse or Polycam (LiDAR on iPhone Pro gives faster results), or plain photos for photogrammetry.
- **Photos for photogrammetry:** 100 to 200 photos, walking along the facade at 2 to 4 m, every step about 60 to 70% overlap, three heights (crouch, chest, arms up), plus a few wider shots. Put a 1 m stick or tape measure in a few shots for scale.

## Processing

| Option | Notes |
| --- | --- |
| Scaniverse / Polycam export | Fastest. Export GLB (mesh). A Gaussian splat looks great but is for looks only, not measurement |
| RealityScan or COLMAP on a PC | Better quality from photos; export GLB/OBJ |

Then:
1. Decimate to under 50 MB (Blender: Decimate modifier; or the app's export settings).
2. Set the model's origin at the middle of the wall base: +Y up, +X straight out of the facade, +Z along the wall towards `base_line.start` (in Blender with "+Y Up" export: face the facade towards +X). Scale with the measuring stick.
3. Save as `apps/web/public/models/b01.glb` with its outline in model metres as `b01.footprint.json` next to it (same format as `b01_v3.footprint.json`), set `VITE_FOCUS_MODEL_URL=/models/b01.glb` in `apps/web/.env.local`.
4. Open the Qasr Salwa view (or its building page) and check the wall zones sit on the facade; fix `base_line` / `azimuth_deg` in `contracts/building_b01.json` if not.

## Fallback

No scan by 21 Nov: keep the citadel model (AI-generated, not a survey) and say on the slide that the photogrammetry scan is next. The detailed real scan we show today is the AL Turaif House embed (Sketchfab, by ATS): click "palace of Nasr" on the map to open its building page.
