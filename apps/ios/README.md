# Hadaq surveyor app (الماسح الميداني)

The iPhone app of tasks S1 (capture) and S2 (offline queue and sync) in the Phase 2 plan. The surveyor picks a wall,
takes a guided photo (level, steady, light, scale reference), the phone hides faces, the surveyor drags the wall's four
corners, and the photo waits in an offline queue until Spring (`services/core`, port 8080) is reachable.

SwiftUI, iOS 17+, AVFoundation, CoreMotion, CoreLocation, Vision, Core Image, SwiftData. No third-party packages.

## Open and run (needs a Mac with Xcode 16 or newer)

1. `open apps/ios/Hadaq.xcodeproj`
2. Target Hadaq > Signing & Capabilities: pick your team (a free Apple ID works). Change the bundle id
   `sa.hadaq.surveyor` if Xcode says it is taken.
3. Plug in the iPhone, choose it as the run destination, Run. On the phone: Settings > Privacy & Security >
   Developer Mode on (first time only), and trust the developer under Settings > General > VPN & Device Management.

If the project does not open in your Xcode: `brew install xcodegen && cd apps/ios && xcodegen` makes it from `project.yml`.

The simulator has no camera: there you can test Import from Photos, corners, the queue and sync, not the camera chips.

## Point it at Spring

- Laptop and phone on one personal hotspot, never the venue Wi-Fi.
- Laptop IP: Windows `ipconfig` (Windows Mobile Hotspot is usually `192.168.137.1`), Mac `ipconfig getifaddr en0`.
- Start Spring on the laptop (`make core`, or `services/core/mvnw spring-boot:run`). Windows Firewall must allow
  Java on port 8080 for private networks.
- Phone Safari: `http://<laptop-ip>:8080/health` must answer.
- App > الإعدادات: server URL `http://<laptop-ip>:8080`, then "فحص الاتصال". iOS asks for local network access the first
  time: allow it.

## What is where

| Path | What |
| --- | --- |
| `Hadaq/Core/` | Foundation only, unit-tested: contract models (`Models.swift`), snake_case JSON with microsecond times (`HadaqJSON.swift`), requests and multipart (`HadaqAPI.swift`), quality warnings, corner order, queue states, wall progress, EXIF dates (`CaptureRules.swift`) |
| `Hadaq/Capture/` | Camera, level and GPS, face hiding and blur score (`ImageChecks.swift`), capture screen, corners screen, import metadata |
| `Hadaq/Queue/` | SwiftData record (`QueuedCapture`), saving and reset (`CaptureStore`), upload, retries and polling (`Uploader`), queue screen |
| `Hadaq/Tasks/` | Registry and survey cache (`TaskStore`), task list, wall screen with Import from Photos |
| `Hadaq/Settings/` | Server URL, user picker, connection check, Reset |
| `Hadaq/Resources/` | Copies of `contracts/v2/examples/registry.json` and `survey.json`, used until the phone has reached Spring once |
| `Support/Info.plist` | Camera, location, photo library and local network texts; http to the laptop (ATS, hackathon only); Files app sharing |
| `Tests/HadaqCoreTests/` | `swift test`: the models against `contracts/v2/`, multipart bytes, queue rules |

## How it behaves

- **Quality never blocks a shot.** Tilt over 5°, movement, bad light and no scale reference go into `quality.warnings`.
- **Privacy:** every photo is drawn upright, Vision finds faces, they are pixelated, and only that version is written
  to disk and uploaded. `faces_hidden` is always set, so Spring does not hide faces a second time.
- **Corners:** handles 1 to 4 (top-left = the C1 end, then clockwise). They are re-sorted into contract order on save, so
  crossed handles still give a valid wall. "كامل الصورة" sends `corners_px: null` (Spring then uses relative scale).
- **Offline queue:** a photo is saved as `Documents/captures/<client_uuid>.jpg` plus `<client_uuid>.json` (the exact meta
  that is sent), visible in the Files app under On My iPhone > حدق.
- **Sync:** the queue drains when the network comes back, right after a capture, and every 15 s. Each retry sends the same
  `client_uuid` and the same meta bytes; Spring answers a repeat with `duplicate: true`, so a photo is stored once.
- **Errors:** no network or a 5xx keeps the photo queued. A 4xx (Spring refused the meta) marks it "رفضها الخادم" and waits
  for the retry button. If Spring's analysis failed, retry calls `POST /captures/{id}/retry`. An upload cut off by closing
  the app goes back to the queue on the next launch.
- **Status:** while a capture is uploaded or processing, the app polls `GET /api/v2/captures/{id}` every 3 s and shows
  the findings count and an urgent badge when it is analysed.
- **survey_id** is sent only when the survey came from Spring. The bundled example survey's id is not real, so photos
  taken before the first sync go up without one (Spring still files them under the wall).

## Test on the phone (the plan's "Done when" lines)

S1:
1. Tasks tab: B01 > W01 shows "لم يبدأ" and "ناقص" in red. Open it, "التقط صورة".
2. Hold the phone upright: the level chip turns green (within 5°); tilt it and it turns orange.
3. Photograph someone's face (or a face on a screen): on the corners screen the face is pixelated and the line
   "أُخفي 1 وجه" shows.
4. Drag the four corners onto the wall, "حفظ".
5. Photos tab > the photo > the meta: every field of `contracts/v2/examples/capture_meta.json` is there. In the Files app
   the `.jpg` (face hidden) and `.json` are in `captures/`.
6. Wall screen > "استيراد من الصور": a site-visit photo keeps its original date (check `captured_at` in the meta).

S2 (with Spring running, `AI_MODE=mock` is fine):
1. Airplane mode on. Take 3 photos of the printed At-Turaif wall, one with a face. The Photos tab shows the offline
   banner and "بانتظار الاتصال" three times.
2. Airplane mode off (rejoin the hotspot). Within 15 s: "جاري الرفع", "قيد التحليل", then "تم التحليل" with the findings
   count, and "عاجل" when a finding is urgent.
3. On the laptop: exactly 3 new captures (`GET http://localhost:8080/api/v2/buildings/B01/inspections`, or the database
   table `captures`), faces hidden in `/api/v2/captures/<id>/file`, `corners_px` and `quality` filled.
4. Pull to refresh on the Photos tab and toggle airplane mode again: still 3 captures on the server. A repeat upload
   (for example when the answer was lost on the way back) carries the same client_uuid, and Spring answers it with
   `duplicate: true` instead of storing a second copy.
5. Settings > Reset clears the phone for the next run.
