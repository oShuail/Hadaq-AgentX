# Camera photo series (owner: Abdurhmn)

Photos are NOT committed to git (too big). Keep them on the team Drive and copy them here locally:

```

assets/camera/B01-CAM01/baseline/frame_001.jpg, frame_002.jpg, ...   same view, normal wall (loops)
assets/camera/B01-CAM01/damage/frame_001.jpg, frame_002.jpg, ...     same view, damage growing step by step
```

If no photos are present, `make camera` creates a deterministic synthetic wall
series locally (4 baseline and 8 progressive damage frames, 1600 x 1200). Its
wall face is `[80, 80, 1520, 1120]` in `contracts/building_b01.json`.
The generated wall also shows older damage in every frame (holes R3C2, rain gullies R3C6, salt R1C7, a broken parapet R4C7, an eroded base R1C4), and the mock detector reports it, so the damage map shows low and medium priorities before the crack grows (high). A `.demo_frames_version` file marks a generated series; when the artwork changes, `make camera` regenerates it by itself. A series without that file (real photos, or one generated before 21 Nov) is never touched: delete the two folders once to get the new artwork.
This is demo artwork, not an inspection photo. Replace both folders with a real
fixed-camera series and remeasure `wall_bbox_px` before using actual photos.

Rules:
- Same tripod position and framing in every frame (the camera simulator pretends to be a fixed camera).
- Frame numbers are 3 digits. The mock detector reads them to grow the crack.
- `damage/` frames are edited copies of a real photo: longer crack, darker wet base, a patch of plaster missing. They are `synthetic`; say so in the video.
- Measure where the wall sits in the photo and set `cameras[0].wall_bbox_px` in `contracts/building_b01.json` (`[x0, y0, x1, y1]` in pixels).
