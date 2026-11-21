"""Faces are hidden before a photo is stored. The detector itself is the YuNet model; here it is replaced by a fixed
box where needed, so the tests check the hiding, the storage path and the event, not the model's accuracy."""

import os

os.environ.setdefault("MQTT_ENABLED", "false")
os.environ.setdefault("AGENTS_ENABLED", "false")
os.environ.setdefault("DATABASE_URL", "sqlite:///./data/test_hadaq.db")
os.environ.setdefault("PHOTO_DIR", "./data/test_photos")

import cv2  # noqa: E402
import numpy as np  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from hadaq_api import privacy  # noqa: E402
from hadaq_api.main import app  # noqa: E402

FACE = (100, 80, 60, 60)  # x, y, w, h


def textured(height: int = 300, width: int = 400) -> np.ndarray:
    return np.random.default_rng(7).integers(40, 220, (height, width, 3)).astype(np.uint8)


def jpeg(image: np.ndarray) -> bytes:
    return cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, 98])[1].tobytes()


def test_hide_wipes_the_face_area_and_nothing_else():
    original = textured()
    hidden = privacy.hide(original.copy(), [FACE])
    x, y, w, h = FACE
    assert hidden[y:y + h, x:x + w].std() < original[y:y + h, x:x + w].std() / 4  # detail gone
    pad_x, pad_y = int(w * privacy.PAD) + 1, int(h * privacy.PAD) + 1
    outside = np.ones(original.shape[:2], bool)
    outside[max(0, y - pad_y):y + h + pad_y, max(0, x - pad_x):x + w + pad_x] = False
    assert np.array_equal(hidden[outside], original[outside])  # the wall is untouched


def test_protect_reencodes_and_counts_faces():
    result = privacy.protect(jpeg(textured()), ".jpg", detector=lambda image: [FACE])
    assert result.checked and result.faces_hidden == 1
    assert cv2.imdecode(np.frombuffer(result.data, np.uint8), cv2.IMREAD_COLOR) is not None


def test_undecodable_upload_is_kept_and_flagged():
    result = privacy.protect(b"not an image", ".jpg")
    assert not result.checked and result.data == b"not an image"


def test_yunet_loads_and_finds_no_face_in_plain_wall_texture():
    assert privacy.detect_faces(textured(1536, 2048)) == []


def test_upload_stores_only_the_hidden_photo(monkeypatch):
    monkeypatch.setattr(privacy, "detect_faces", lambda image: [FACE])
    original = textured()
    with TestClient(app) as client:
        photo = client.post("/photos", files={"file": ("visitor.jpg", jpeg(original), "image/jpeg")},
                            data={"camera_id": "B01-CAM01", "building_id": "B01", "provenance": "synthetic"}).json()
        stored = cv2.imdecode(np.frombuffer(client.get(f"/photos/{photo['photo_id']}/file").content, np.uint8), cv2.IMREAD_COLOR)
        events = client.get("/buildings/B01/events", params={"limit": 10}).json()
    x, y, w, h = FACE
    assert stored[y:y + h, x:x + w].std() < original[y:y + h, x:x + w].std() / 4
    note = next(e for e in events if e["type"] == "new_photo" and e["payload"]["photo_id"] == photo["photo_id"])
    assert note["payload"]["privacy"] == {"checked": True, "faces_hidden": 1}
