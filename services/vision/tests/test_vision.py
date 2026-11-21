from pathlib import Path

from hadaq_vision.change import label_changes
from hadaq_vision.detect import MockDetector
from hadaq_vision.demo_frames import WALL_BBOX, WIDTH, HEIGHT, create_demo_frames
from hadaq_vision.zones import pixel_to_zone


def test_pixel_to_zone_bottom_is_row_1():
    bbox = [0, 0, 800, 400]
    assert pixel_to_zone(10, 390, bbox, "B01", "W01") == "B01-W01-R1C1"
    assert pixel_to_zone(790, 10, bbox, "B01", "W01") == "B01-W01-R4C8"
    assert pixel_to_zone(900, 10, bbox, "B01", "W01") is None


def test_mock_detector_grows_and_changes_are_labeled():
    det = MockDetector()
    small = det.detect(Path("damage/frame_001.jpg"))[0]
    big = det.detect(Path("damage/frame_003.jpg"))[0]
    assert big.length_px > small.length_px
    prev = [{"zone_id": "B01-W01-R2C5", "cls": "crack", "length_px": 100.0}]
    cur = [{"zone_id": "B01-W01-R2C5", "cls": "crack", "length_px": 130.0},
           {"zone_id": "B01-W01-R1C1", "cls": "erosion", "area_px": 50.0}]
    label_changes(cur, prev)
    assert cur[0]["change"] == "grown" and cur[0]["growth_pct"] == 30.0
    assert cur[1]["change"] == "new"


def test_generated_camera_series_and_zone_mapping(tmp_path):
    from PIL import Image

    create_demo_frames(tmp_path)
    baseline = sorted((tmp_path / "baseline").glob("frame_*.jpg"))
    damage = sorted((tmp_path / "damage").glob("frame_*.jpg"))
    assert len(baseline) == 4 and len(damage) == 8
    assert baseline[0].read_bytes() == baseline[-1].read_bytes()
    assert damage[0].read_bytes() != damage[-1].read_bytes()
    with Image.open(damage[-1]) as image:
        assert image.size == (WIDTH, HEIGHT)
    detector = MockDetector(list(WALL_BBOX))
    for path in (baseline[0], damage[0], damage[-1]):
        crack = detector.detect(path)[0]
        x0, y0, x1, y1 = crack.bbox_xyxy
        assert pixel_to_zone((x0+x1)/2, (y0+y1)/2, list(WALL_BBOX), "B01", "W01") == "B01-W01-R2C5"
    assert detector.detect(damage[-1])[0].length_px > detector.detect(baseline[0])[0].length_px
