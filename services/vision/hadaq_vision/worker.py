"""Vision worker: poll the API for new photos, detect defects, map to zones, compare, post a DetectionBatch.

    python -m hadaq_vision.worker --backend mock
    python -m hadaq_vision.worker --backend rfdetr --checkpoint runs/hadaq-seg/checkpoint_best_total.pth
"""

from __future__ import annotations

import argparse
import logging
import tempfile
import time
from pathlib import Path

import httpx

from hadaq_schemas import Detection, DetectionBatch, Provenance, load_registry
from hadaq_vision.change import label_changes
from hadaq_vision.detect import load_detector
from hadaq_vision.zones import bbox_center, pixel_to_zone

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("hadaq.vision")


def process_photo(api: httpx.Client, detector, photo: dict, registry: dict) -> DetectionBatch:
    camera = next(c for c in registry["cameras"] if c["id"] == photo["camera_id"])
    wall = next(w for w in registry["walls"] if w["id"] == camera["wall_id"])
    image = api.get(f"/photos/{photo['photo_id']}/file")
    image.raise_for_status()
    with tempfile.TemporaryDirectory() as tmp:
        name = f"{photo.get('sequence') or 'frame'}_{photo.get('frame') or 1:03d}.jpg"
        path = Path(tmp) / name
        path.write_bytes(image.content)
        raw = detector.detect(path)

    current = []
    for det in raw:
        cx, cy = bbox_center(det.bbox_xyxy)
        zid = pixel_to_zone(cx, cy, camera["wall_bbox_px"], photo["building_id"], wall["id"], wall["rows"], wall["cols"])
        if zid:
            current.append({"zone_id": zid, "cls": det.cls, "confidence": det.confidence, "bbox_xyxy": det.bbox_xyxy,
                            "length_px": det.length_px, "area_px": det.area_px})
    previous = api.get(f"/photos/{photo['photo_id']}/previous-detections").json()
    label_changes(current, previous)

    provenance = Provenance.SYNTHETIC if detector.synthetic else Provenance.MODEL_OUTPUT
    detections = [
        Detection(photo_id=photo["photo_id"], camera_id=photo["camera_id"], building_id=photo["building_id"],
                  model=detector.name, provenance=provenance, **d)
        for d in current
    ]
    return DetectionBatch(photo_id=photo["photo_id"], building_id=photo["building_id"], camera_id=photo["camera_id"],
                          model=detector.name, detections=detections)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8000")
    parser.add_argument("--building", default="B01")
    parser.add_argument("--backend", choices=["mock", "rfdetr"], default="mock")
    parser.add_argument("--checkpoint", default=None)
    parser.add_argument("--interval", type=float, default=2.0)
    args = parser.parse_args()

    registry = load_registry()
    detector = load_detector(args.backend, args.checkpoint, registry["cameras"][0]["wall_bbox_px"])
    log.info("Vision worker running with %s", detector.name)
    with httpx.Client(base_url=args.api, timeout=60) as api:
        while True:
            try:
                for photo in api.get(f"/buildings/{args.building}/photos", params={"status": "new"}).json():
                    batch = process_photo(api, detector, photo, registry)
                    api.post("/detections", json=batch.model_dump(mode="json")).raise_for_status()
                    log.info("%s: %d detections", photo["photo_id"], len(batch.detections))
            except httpx.HTTPError as exc:
                log.warning("API problem: %s", exc)
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
