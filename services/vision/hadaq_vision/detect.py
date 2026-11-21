"""Defect detectors.

RfdetrDetector  real model (pip install -e "services/vision[rfdetr]"). Fine-tune first (train.py), then pass --checkpoint.
MockDetector    no model: fake but deterministic detections so the whole pipeline can be built and filmed today.
                Its output is labeled provenance "synthetic".
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

CLASSES = ["crack", "erosion", "plaster_loss", "moisture_stain", "missing_material"]


@dataclass
class RawDetection:
    cls: str
    confidence: float
    bbox_xyxy: list[float]
    area_px: float | None = None
    length_px: float | None = None


class RfdetrDetector:
    name = "rfdetr-seg-medium"
    synthetic = False

    def __init__(self, checkpoint: str | None = None, threshold: float = 0.4) -> None:
        import rfdetr  # heavy import, only when the real model is used

        self.model = rfdetr.from_checkpoint(checkpoint) if checkpoint else rfdetr.RFDETRSegMedium()
        self.threshold = threshold
        names = getattr(self.model, "class_names", None)
        self.class_names = list(names.values()) if isinstance(names, dict) else list(names or CLASSES)

    def detect(self, image_path: Path) -> list[RawDetection]:
        detections = self.model.predict(str(image_path), threshold=self.threshold)
        out: list[RawDetection] = []
        for i in range(len(detections)):
            class_id = int(detections.class_id[i])
            name = self.class_names[class_id] if class_id < len(self.class_names) else str(class_id)
            name = name.lower().replace(" ", "_")
            if name not in CLASSES:
                continue  # pretrained COCO classes (person, car...) are not wall defects
            x0, y0, x1, y1 = (float(v) for v in detections.xyxy[i])
            mask = detections.mask[i] if getattr(detections, "mask", None) is not None else None
            area = float(mask.sum()) if mask is not None else (x1 - x0) * (y1 - y0)
            out.append(RawDetection(name, float(detections.confidence[i]), [x0, y0, x1, y1], area, max(x1 - x0, y1 - y0)))
        return out


class MockDetector:
    """Frame N of the 'damage' series shows a crack that grows 15% per frame; 'baseline' frames show a small crack."""

    name = "mock-detector"
    synthetic = True

    def __init__(self, wall_bbox_px: list[float] | None = None) -> None:
        x0, y0, x1, y1 = wall_bbox_px or [0, 0, 4000, 3000]
        self.cx = x0 + (4.5 / 8) * (x1 - x0)  # W01 R2C5
        self.cy = y0 + (2.5 / 4) * (y1 - y0)
        self.scale = (y1 - y0) / 3000
        self.wall_bbox_px = [x0, y0, x1, y1]

    def detect(self, image_path: Path) -> list[RawDetection]:
        frame_match = re.search(r"(\d+)", image_path.stem)
        frame = int(frame_match.group(1)) if frame_match else 1
        damaged = "damage" in str(image_path).lower()
        length = 300.0 * self.scale * (1.15 ** frame if damaged else 1.0)
        box = [self.cx - 20 * self.scale, self.cy - length / 2,
               self.cx + 20 * self.scale, self.cy + length / 2]
        detections = [RawDetection("crack", 0.83, box, area_px=length * 6, length_px=length)]
        if damaged and frame >= 2:
            x0, y0, x1, y1 = self.wall_bbox_px
            box = [x0 + .13 * (x1 - x0), y0 + .83 * (y1 - y0),
                   x0 + .25 * (x1 - x0), y0 + .96 * (y1 - y0)]
            detections.append(RawDetection("plaster_loss", 0.71, box,
                                           area_px=(box[2] - box[0]) * (box[3] - box[1])))
        return detections


def load_detector(backend: str, checkpoint: str | None = None, wall_bbox_px: list[float] | None = None):
    return RfdetrDetector(checkpoint) if backend == "rfdetr" else MockDetector(wall_bbox_px)
