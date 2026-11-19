# Abdurhmn: vision, camera feed, demo video

**Mission:** you own Hadaq's eyes. Photos come in from the (simulated) fixed camera, your pipeline finds cracks, erosion and plaster loss, maps them to wall zones and says what changed since the last photo. A grown crack triggers the agent. You also produce the demo video that Round 1 asks for.

## What you own

| Path | What |
| --- | --- |
| `services/vision/hadaq_vision/detect.py` | `RfdetrDetector` (RF-DETR-Seg) and `MockDetector` |
| `services/vision/hadaq_vision/zones.py` | Photo pixels to zone IDs |
| `services/vision/hadaq_vision/change.py` | New / grown / unchanged per zone and class |
| `services/vision/hadaq_vision/worker.py` | Polls new photos, detects, posts `DetectionBatch` |
| `services/vision/hadaq_vision/camera_sim.py` | Fake fixed camera `B01-CAM01` |
| `services/vision/hadaq_vision/train.py` | Fine-tune RF-DETR-Seg |
| `assets/camera/` | Photo series (on Drive, not git) |
| `apps/web/src/panel/CameraFeed.tsx` | Frame with detection boxes |
| The demo video | Recording and editing |

## Setup

```bash
make setup && make up
make api                 # terminal 1
make vision              # terminal 2: worker with the mock detector
.venv/bin/pytest -q services/vision/tests
```

## Tasks for Round 1, in order

### 1. Run the pipeline end to end with the mock detector (today)

Put any 3 photos in `assets/camera/B01-CAM01/baseline/` and 3 in `.../damage/` (names `frame_001.jpg`...), then `make camera`. In the web panel you should see the frame, a dashed box, and after **New crack** on the scenario panel: "crack in B01-W01-R2C5 | grown +32%". Everything still says SIM because the mock detector's output is `synthetic`.

### 2. Shoot the photo series

- Fixed view of one mud wall from 5 to 8 m, tripod or phone on a wall, same zoom, 20 to 30 frames, ideally overcast or shade (no hard shadows).
- Separate close-ups at about 1 m (about 0.3 mm per pixel with a 12 MP phone) for the dataset: cracks, eroded base, plaster loss, wet stains.
- Only photograph where photography is allowed. Never touch the walls.

Build `damage/` from a copy of one baseline frame: in GIMP, Photoshop or an AI image editor, extend a crack a bit more in each frame, darken the base as if wet, remove a patch of plaster. Keep the camera framing identical.

Then open one frame in any image viewer and read the pixel box of the wall face. Set it in `contracts/building_b01.json`:

```json
"cameras": [{ "id": "B01-CAM01", "wall_id": "W01", "source": "sim", "wall_bbox_px": [412, 380, 3620, 2710] }]
```

(These numbers are an example; use yours.)

### 3. Build the dataset

1. Create a Roboflow project "hadaq-defects", type Instance Segmentation, classes exactly: `crack`, `erosion`, `plaster_loss`, `moisture_stain`, `missing_material`.
2. Upload your close-ups. Use SAM 3 auto-labeling in Roboflow with text prompts (for example "crack", "eroded mud plaster") to propose masks, then fix every mask by hand. Do not accept unchecked auto-labels.
3. Optional: add public crack images from Roboflow Universe for pretraining. Check each dataset's license and note it.
4. Split **by wall**, not by photo (photos of the same wall must not be in both train and test), or the score is fake.
5. Export "COCO Segmentation" into `services/vision/data/hadaq-defects/{train,valid,test}/`.

### 4. Train RF-DETR-Seg (GPU: Colab or Kaggle)

```bash
pip install -e packages/hadaq_schemas -e "services/vision[rfdetr]"
python -m hadaq_vision.train --dataset services/vision/data/hadaq-defects --epochs 30 --batch 4
```

RF-DETR-Seg is Apache 2.0 at every size, which is why we use it instead of YOLO (AGPL). Then run the worker with the real model:

```bash
.venv/bin/python -m hadaq_vision.worker --backend rfdetr --checkpoint runs/hadaq-seg/checkpoint_best_total.pth
```

(Check the actual checkpoint file name in `runs/hadaq-seg/`.) Real detections are labeled `model_output`.

If training is not ready by 21 Nov, the video uses the mock detector and says so. Honest beats broken.

### 5. Measure and report honestly

On the held-out wall: per-class precision and recall (RF-DETR prints mAP during validation). Put the numbers and the dataset size on the "current status" slide. Small dataset = say "early results on N images".

### 6. Optional bonus: a VLM photo check

A vision-language model can describe a new photo and check its quality before detection. It never measures. Example with the Anthropic SDK (`pip install anthropic`):

```python
import base64, os
import anthropic

def describe(image_path: str) -> str:
    client = anthropic.Anthropic()
    data = base64.standard_b64encode(open(image_path, "rb").read()).decode()
    message = client.messages.create(
        model=os.getenv("HADAQ_VLM", "claude-sonnet-5-5"),
        max_tokens=300,
        messages=[{"role": "user", "content": [
            {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": data}},
            {"type": "text", "text": "This is an inspection photo of a historic mud-brick wall. "
             "Is it sharp, well lit and straight-on? Then list visible defects in one line each. Do not estimate sizes."},
        ]}],
    )
    return message.content[0].text
```

### 7. The demo video (21 Nov)

Storyline in `docs/demo-script.md`. Record the screen with OBS at 1080p, about 2 minutes, plus Saleh's water-pour shot. Voice-over in clear English (or Arabic with English subtitles). Upload unlisted to YouTube or a public Drive link and give the link to Basma for the status slide.

## Naming rules

- Classes exactly as in `CLASSES` (snake_case). Camera IDs `B01-CAM01`. Frames `frame_001.jpg`.
- Detector `name` goes into every detection (`rfdetr-seg-medium`, `mock-detector`).

## Contracts

- Produces: `DetectionBatch` (`POST /detections`), photos (`POST /photos`).
- Consumes: `GET /buildings/B01/photos?status=new`, `GET /photos/{id}/file`, `GET /photos/{id}/previous-detections`, events `new_crack`, `demo_reset`.

## Definition of done (21 Nov)

- [ ] Real photo series on Drive, `wall_bbox_px` set
- [ ] New crack on the panel leads to "grown" in the UI and an agent investigation
- [ ] Either a trained model with honest metrics, or the mock clearly labeled
- [ ] Demo video uploaded, link in the deck

## Round 2

Photo registration (LightGlue or LoFTR) before comparing, masks projected onto the 3D mesh, crack width in mm from a scale bar, more data from the iOS capture app.
