import numpy as np

from hadaq_vision.detect import RawDetection
from hadaq_vision.findings import label_change, to_findings
from hadaq_vision.rectify import rectify

WALL = {"id": "W01", "rows": 4, "cols": 8}


def _rect():
    # a 16 m x 8 m wall at 100 px per metre, already flat
    return rectify(np.zeros((800, 1600, 3), np.uint8), [[0, 0], [1599, 0], [1599, 799], [0, 799]], 16.0, 8.0, px_per_m=100)


def test_detection_lands_in_the_right_zone_in_metres():
    # a box 2.2..2.8 m from the left and 0.2..1.0 m above the base -> column 2, base row
    det = RawDetection("erosion", 0.9, [220, 700, 280, 780], area_px=60 * 80, length_px=80)
    [f] = to_findings([det], _rect(), WALL, "B01", "cap_test", "mock", synthetic=True)
    assert f.zone_id == "B01-W01-R1C2"
    assert f.damage_type == "base_erosion"  # old "erosion" on the base row
    assert f.box_wall_m == [2.2, 0.2, 2.8, 1.0]
    assert f.provenance == "synthetic"


def test_crack_that_grew_is_marked_grown():
    rect = _rect()
    old = to_findings([RawDetection("crack", 0.8, [500, 300, 510, 400], length_px=100)], rect, WALL, "B01", "cap_1", "m", True)
    new = to_findings([RawDetection("crack", 0.8, [500, 290, 510, 410], length_px=120)], rect, WALL, "B01", "cap_2", "m", True)
    label_change(new, old)
    assert new[0].change == "grown" and new[0].previous_finding_id == old[0].finding_id