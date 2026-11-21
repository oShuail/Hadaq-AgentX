"""Privacy: hide the faces of people in uploaded photos before anything is stored (challenge criterion).

Faces are found with YuNet, a small face detector from the OpenCV Zoo (models/face_detection_yunet_2023mar.onnx,
MIT licence next to it). It runs on the CPU inside OpenCV in a few tens of milliseconds, with no network, and finds
small and turned faces. Each face is pixelated and then blurred, so it cannot be recovered. The score threshold is
below the usual 0.9: when in doubt we hide, because a missed face is worse than a few hidden wall pixels.
Re-encoding the photo also drops EXIF metadata such as the GPS position.
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np

log = logging.getLogger("hadaq.privacy")

MODEL_FILE = Path(__file__).parent / "models" / "face_detection_yunet_2023mar.onnx"
SCORE_THRESHOLD = 0.6
NMS_THRESHOLD = 0.3
DETECT_MAX_SIDE = 2048  # detect on a downscaled copy; a 4000 px photo keeps faces of about 20 px and more
PAD = 0.3  # grow each face box by this share on every side (hair, ears, chin)
JPEG_QUALITY = 95

Box = tuple[int, int, int, int]  # x, y, width, height in full-image pixels

_detector = None
_lock = threading.Lock()  # the detector keeps its input size as state


@dataclass
class PrivacyResult:
    data: bytes
    checked: bool  # False when the bytes could not be decoded as an image (stored unchanged)
    boxes: list[Box] = field(default_factory=list)

    @property
    def faces_hidden(self) -> int:
        return len(self.boxes)


def detect_faces(image: np.ndarray) -> list[Box]:
    """Face boxes in full-image pixels."""
    global _detector
    height, width = image.shape[:2]
    scale = min(1.0, DETECT_MAX_SIDE / max(height, width))
    small = cv2.resize(image, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA) if scale < 1 else image
    with _lock:
        if _detector is None:
            _detector = cv2.FaceDetectorYN.create(str(MODEL_FILE), "", (320, 320), SCORE_THRESHOLD, NMS_THRESHOLD, 5000)
        _detector.setInputSize((small.shape[1], small.shape[0]))
        _, faces = _detector.detect(small)
    if faces is None:
        return []
    return [(round(x / scale), round(y / scale), round(w / scale), round(h / scale)) for x, y, w, h in faces[:, :4]]


def hide(image: np.ndarray, boxes: list[Box]) -> np.ndarray:
    """Pixelate then blur each box (grown by PAD), in place; returns the image."""
    height, width = image.shape[:2]
    for x, y, w, h in boxes:
        x0, y0 = max(0, int(x - w * PAD)), max(0, int(y - h * PAD))
        x1, y1 = min(width, int(x + w * (1 + PAD))), min(height, int(y + h * (1 + PAD)))
        if x1 - x0 < 2 or y1 - y0 < 2:
            continue
        region = image[y0:y1, x0:x1]
        blocks = cv2.resize(region, (max(1, (x1 - x0) // 12), max(1, (y1 - y0) // 12)), interpolation=cv2.INTER_AREA)
        region = cv2.resize(blocks, (x1 - x0, y1 - y0), interpolation=cv2.INTER_NEAREST)
        image[y0:y1, x0:x1] = cv2.GaussianBlur(region, (0, 0), sigmaX=max(x1 - x0, y1 - y0) / 10)
    return image


def protect(data: bytes, suffix: str = ".jpg", detector=None) -> PrivacyResult:
    """Hide people's faces in an uploaded image. Undecodable bytes come back unchanged with checked=False."""
    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        log.warning("Upload is not a decodable image; stored as is, privacy not checked")
        return PrivacyResult(data=data, checked=False)
    boxes = (detector or detect_faces)(image)
    hide(image, boxes)
    if suffix == ".png":
        ok, encoded = cv2.imencode(".png", image)
    else:
        ok, encoded = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
    if not ok:  # pragma: no cover - encoding a decoded image does not fail in practice
        raise ValueError("could not re-encode the photo after hiding faces")
    if boxes:
        log.info("Hid %d faces", len(boxes))
    return PrivacyResult(data=encoded.tobytes(), checked=True, boxes=boxes)
