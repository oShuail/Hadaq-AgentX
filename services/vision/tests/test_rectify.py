import cv2
import numpy as np

from hadaq_vision.rectify import rectify


def test_known_quad_maps_to_wall_metres():
    # a 10 m x 4 m wall drawn as a skewed quad, with a white square at u = 2..3 m, v = 1..2 m
    W, H, ppm = 10.0, 4.0, 100
    flat = np.zeros((int(H * ppm), int(W * ppm), 3), np.uint8)
    flat[int((H - 2) * ppm):int((H - 1) * ppm), int(2 * ppm):int(3 * ppm)] = 255
    corners = np.float32([[120, 80], [1100, 140], [1080, 600], [140, 560]])
    m = cv2.getPerspectiveTransform(np.float32([[0, 0], [999, 0], [999, 399], [0, 399]]), corners)
    photo = cv2.warpPerspective(flat, m, (1300, 700))
    r = rectify(photo, corners.tolist(), W, H, px_per_m=100)
    ys, xs = np.where(r.image[:, :, 0] > 128)
    u0, v0, u1, v1 = r.box_px_to_wall_m([xs.min(), ys.min(), xs.max(), ys.max()])
    assert abs(u0 - 2) < 0.1 and abs(u1 - 3) < 0.1 and abs(v0 - 1) < 0.1 and abs(v1 - 2) < 0.1
    assert r.scale == "absolute"


def test_no_corners_is_relative():
    r = rectify(np.zeros((300, 600, 3), np.uint8), None, 12.0, 6.0)
    assert r.scale == "relative" and r.px_per_m == 50