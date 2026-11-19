# 3D scan of the focus building

The scan is **not** on the critical path. The app works today with the OSM building highlighted as the focus building; the scan replaces it when ready. Do the code setup first, scan when the light is right.

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
2. Set the model's origin at the middle of the wall base, front facing +Y. Scale with the measuring stick.
3. Save as `apps/web/public/models/b01.glb`, set `VITE_FOCUS_MODEL_URL=/models/b01.glb` in `apps/web/.env.local`.
4. Check alignment with the wall zones; adjust heading in `SiteMap.tsx` if needed.

## Fallback

No scan by 21 Nov: keep the highlighted OSM building, or model a simple massing in Blender (box + crenellations + mud texture). Say on the slide that the photogrammetry scan is next.
