"""Fake fixed camera B01-CAM01.

Folder layout (photos are not committed to git; share them on Drive):
    assets/camera/B01-CAM01/baseline/frame_001.jpg ...   normal wall, loops
    assets/camera/B01-CAM01/damage/frame_001.jpg ...     same view, damage growing frame by frame

Plays 'baseline' in a loop. On a new_crack event it switches to 'damage' and advances one frame per interval.
On demo_reset it goes back to 'baseline'. Uploads each frame to POST /photos with provenance "synthetic".

    python -m hadaq_vision.camera_sim --folder assets/camera/B01-CAM01 --interval 10
"""

from __future__ import annotations

import argparse
import json
import logging
import threading
import time
from pathlib import Path

import httpx
import paho.mqtt.client as mqtt

from hadaq_vision.demo_frames import create_demo_frames

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("hadaq.camera_sim")
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}


class CameraSim:
    def __init__(self, folder: Path) -> None:
        if not any(p.suffix.lower() in IMAGE_SUFFIXES for p in (folder / "baseline").glob("*")):
            log.info("No camera photos found; creating a synthetic fixed-camera demo series in %s", folder)
            create_demo_frames(folder)
        self.sequences = {name: sorted(p for p in (folder / name).glob("*") if p.suffix.lower() in IMAGE_SUFFIXES)
                          for name in ("baseline", "damage")}
        if not self.sequences["baseline"]:
            raise SystemExit(f"No photos in {folder / 'baseline'}")
        self.mode, self.index = "baseline", 0
        self.lock = threading.Lock()

    def on_event(self, event_type: str) -> None:
        with self.lock:
            if event_type == "new_crack" and self.sequences["damage"]:
                self.mode, self.index = "damage", 0
            elif event_type == "demo_reset":
                self.mode, self.index = "baseline", 0
        log.info("event %s -> sequence %s", event_type, self.mode)

    def next_frame(self) -> tuple[str, int, Path]:
        with self.lock:
            frames = self.sequences[self.mode]
            if self.mode == "damage":
                i = min(self.index, len(frames) - 1)  # stay on the last damage frame
            else:
                i = self.index % len(frames)
            self.index += 1
            return self.mode, i + 1, frames[i]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--folder", type=Path, default=Path("assets/camera/B01-CAM01"))
    parser.add_argument("--api", default="http://localhost:8000")
    parser.add_argument("--mqtt-host", default="localhost")
    parser.add_argument("--building", default="B01")
    parser.add_argument("--camera", default="B01-CAM01")
    parser.add_argument("--interval", type=float, default=10.0)
    args = parser.parse_args()

    sim = CameraSim(args.folder)
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"camera-sim-{args.camera}")
    client.on_message = lambda c, u, msg: sim.on_event(json.loads(msg.payload).get("type", ""))
    client.on_connect = lambda c, u, f, rc, p: c.subscribe(f"hadaq/v1/{args.building}/events/#")
    try:
        client.connect(args.mqtt_host, 1883)
        client.loop_start()
    except OSError as exc:
        log.warning("MQTT unavailable (%s); scenario events will not switch sequences", exc)

    with httpx.Client(base_url=args.api, timeout=30) as api:
        while True:
            sequence, frame, path = sim.next_frame()
            with path.open("rb") as handle:
                files = {"file": (path.name, handle, "image/jpeg")}
                data = {"camera_id": args.camera, "building_id": args.building, "provenance": "synthetic",
                        "sequence": sequence, "frame": str(frame)}
                try:
                    photo = api.post("/photos", files=files, data=data).json()
                    log.info("uploaded %s/%s as %s", sequence, path.name, photo.get("photo_id"))
                except httpx.HTTPError as exc:
                    log.warning("upload failed: %s", exc)
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
