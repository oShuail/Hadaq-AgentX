from pathlib import Path

from hadaq_vision.change import label_changes
from hadaq_vision.detect import MockDetector
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
