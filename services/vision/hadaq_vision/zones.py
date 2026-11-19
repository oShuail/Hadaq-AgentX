"""Map detections in a photo to wall zones.

Round 1 assumes a fixed camera looking straight at the wall. camera.wall_bbox_px = [x0, y0, x1, y1] is the wall face
inside the photo (set it once per camera in contracts/building_b01.json). The face is split into rows x cols.
R1 is the bottom row of the wall = the BOTTOM of the photo. Round 2 replaces this with projection onto the 3D mesh.
"""

from __future__ import annotations

from hadaq_schemas import zone_id


def pixel_to_zone(
    cx: float, cy: float, wall_bbox_px: list[float], building_id: str, wall_id: str, rows: int = 4, cols: int = 8
) -> str | None:
    x0, y0, x1, y1 = wall_bbox_px
    if not (x0 <= cx <= x1 and y0 <= cy <= y1):
        return None
    col = min(cols, int((cx - x0) / (x1 - x0) * cols) + 1)
    row_from_top = min(rows, int((cy - y0) / (y1 - y0) * rows) + 1)
    row = rows - row_from_top + 1
    return zone_id(building_id, wall_id, row, col)


def bbox_center(bbox_xyxy: list[float]) -> tuple[float, float]:
    x0, y0, x1, y1 = bbox_xyxy
    return (x0 + x1) / 2, (y0 + y1) / 2
