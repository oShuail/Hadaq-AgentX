"""Rectify a wall photo to a flat, scaled orthophoto using the 4 wall corners the surveyor marked.

corners_px order: top-left, top-right, bottom-right, bottom-left of the wall face (left = the C1 end).
Output: an image where 1 pixel = 1 / px_per_m metres, (0, 0) = top-left of the wall.
Wall coordinates: u from the left end, v up from the base (so v = height_m - y / px_per_m).
"""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

MAX_SIDE_PX = 8000  # keep the orthophoto a sane size on long walls


@dataclass
class Rectified:
    image: np.ndarray
    px_per_m: float
    width_m: float
    height_m: float
    scale: str  # "absolute" when corners were given, else "relative"

    def box_px_to_wall_m(self, box: list[float]) -> list[float]:
        """[x0, y0, x1, y1] in the orthophoto -> [u0, v0, u1, v1] in wall metres (v up from the base)."""
        x0, y0, x1, y1 = box
        u0, u1 = x0 / self.px_per_m, x1 / self.px_per_m
        v0, v1 = self.height_m - y1 / self.px_per_m, self.height_m - y0 / self.px_per_m
        clamp = lambda v, hi: round(min(hi, max(0.0, v)), 3)  # noqa: E731
        return [clamp(u0, self.width_m), clamp(v0, self.height_m), clamp(u1, self.width_m), clamp(v1, self.height_m)]


def rectify(image: np.ndarray, corners_px: list[list[float]] | None, width_m: float, height_m: float,
            px_per_m: float = 250.0) -> Rectified:
    if not corners_px:
        h, w = image.shape[:2]
        # No corners: treat the whole photo as the wall. Scale is only relative.
        return Rectified(image=image, px_per_m=w / width_m, width_m=width_m, height_m=h * width_m / w, scale="relative")
    px_per_m = min(px_per_m, MAX_SIDE_PX / max(width_m, height_m))
    out_w, out_h = int(round(width_m * px_per_m)), int(round(height_m * px_per_m))
    src = np.array(corners_px, dtype=np.float32)
    dst = np.array([[0, 0], [out_w - 1, 0], [out_w - 1, out_h - 1], [0, out_h - 1]], dtype=np.float32)
    matrix = cv2.getPerspectiveTransform(src, dst)
    ortho = cv2.warpPerspective(image, matrix, (out_w, out_h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    return Rectified(image=ortho, px_per_m=px_per_m, width_m=width_m, height_m=height_m, scale="absolute")