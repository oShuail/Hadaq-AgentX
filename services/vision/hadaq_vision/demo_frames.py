"""Deterministic synthetic fixed-camera frames for demos without a wall photograph.

The generated JPEGs are local assets, ignored by git. Replacing them with a
fixed-camera photo series requires no API or web changes.
"""

from __future__ import annotations

import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


WIDTH, HEIGHT = 1600, 1200
WALL_BBOX = (80, 80, 1520, 1120)
# Bump when the artwork changes: camera_sim regenerates a generated series whose marker is older.
VERSION = "2"
MARKER = ".demo_frames_version"

# Damage that is on the wall from the first frame, as boxes in photo pixels (the mock detector reports the same boxes).
# Zones: each column is 180 px wide, each row 260 px high, row 1 at the bottom.
STATIC_DAMAGE = {
    "holes": (320, 430, 420, 530),          # R3C2 small burrow holes
    "gullies": (1020, 370, 1110, 590),      # R3C6 rain gullies
    "salt": (1190, 980, 1330, 1100),        # R1C7 white salt bloom
    "parapet": (1180, 80, 1325, 175),       # R4C7 broken parapet edge
    "base_erosion": (630, 1060, 800, 1120),  # R1C4 eroded wall foot
}


def _draw_static_damage(image: Image.Image) -> Image.Image:
    """Older damage visible in every frame: holes, rain gullies, salt, a broken parapet, an eroded base."""
    overlay = Image.new("RGBA", image.size)
    d = ImageDraw.Draw(overlay)
    rng = random.Random(7)
    x0, y0, x1, y1 = STATIC_DAMAGE["holes"]
    for cx, cy, r in ((345, 455, 11), (378, 470, 8), (360, 500, 13), (398, 448, 7), (392, 512, 9)):
        d.ellipse((cx - r, cy - r * 0.8, cx + r, cy + r * 0.8), fill=(48, 34, 24, 235), outline=(120, 95, 70, 200), width=2)
    x0, y0, x1, y1 = STATIC_DAMAGE["gullies"]
    for gx in (1035, 1066, 1094):
        pts = [(gx + rng.randint(-4, 4), y) for y in range(y0, y1, 22)]
        d.line(pts, fill=(118, 88, 62, 210), width=7, joint="curve")
        d.line([(x + 4, y) for x, y in pts], fill=(205, 170, 130, 120), width=2)
    x0, y0, x1, y1 = STATIC_DAMAGE["salt"]
    for _ in range(26):
        cx, cy = rng.randint(x0 + 15, x1 - 15), rng.randint(y0 + 15, y1 - 10)
        r = rng.randint(8, 22)
        d.ellipse((cx - r, cy - r * 0.7, cx + r, cy + r * 0.7), fill=(236, 228, 210, rng.randint(90, 170)))
    x0, y0, x1, y1 = STATIC_DAMAGE["parapet"]
    d.polygon([(x0, y0), (x1, y0), (x1 - 10, y0 + 40), (x1 - 45, y0 + 62), (x1 - 70, y1 - 8), (x0 + 60, y1),
               (x0 + 30, y0 + 70), (x0 + 6, y0 + 30)], fill=(177, 157, 124, 255))
    d.line([(x1 - 10, y0 + 40), (x1 - 45, y0 + 62), (x1 - 70, y1 - 8), (x0 + 60, y1), (x0 + 30, y0 + 70)],
           fill=(110, 82, 58, 230), width=4)
    x0, y0, x1, y1 = STATIC_DAMAGE["base_erosion"]
    edge = [(x0, y1)] + [(x, y0 + 8 + int(14 * abs(((x - x0) % 56) - 28) / 28)) for x in range(x0, x1 + 1, 14)] + [(x1, y1)]
    d.polygon(edge, fill=(104, 78, 56, 235))
    return Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")


def create_demo_frames(folder: Path) -> None:
    rng = random.Random(101)
    image = Image.new("RGB", (WIDTH, HEIGHT), (177, 157, 124))
    draw = ImageDraw.Draw(image)
    draw.rectangle(WALL_BBOX, fill=(177, 139, 99))

    # Irregular adobe courses and fine grain remain identical in every frame.
    x0, y0, x1, y1 = WALL_BBOX
    for row, y in enumerate(range(y0, y1, 95)):
        offset = -100 if row % 2 else 0
        for x in range(x0 + offset, x1, 220):
            bx0, by0 = max(x0, x + 5), y + 5
            bx1, by1 = min(x1, x + 216), min(y1, y + 90)
            if bx0 >= bx1 or by0 >= by1:
                continue
            tone = rng.randint(-14, 14)
            draw.rectangle((bx0, by0, bx1, by1), fill=(179 + tone, 139 + tone, 99 + tone))
            draw.line((bx0, by1, bx1, by1), fill=(133, 105, 77), width=3)
            for _ in range(85):
                px, py = rng.randint(bx0, bx1), rng.randint(by0, by1)
                delta = rng.randint(-22, 20)
                draw.point((px, py), fill=(max(0, 176 + delta), max(0, 137 + delta), max(0, 99 + delta)))
    image = image.filter(ImageFilter.GaussianBlur(0.35))
    ImageDraw.Draw(image).line([(900, 680), (905, 700), (896, 719), (909, 740)], fill=(70, 52, 39), width=2)
    image = _draw_static_damage(image)

    for mode, count in (("baseline", 4), ("damage", 8)):
        target = folder / mode
        target.mkdir(parents=True, exist_ok=True)
        for index in range(1, count + 1):
            frame = image.copy()
            if mode == "damage":
                overlay = Image.new("RGBA", frame.size)
                mark = ImageDraw.Draw(overlay)
                # The base darkens, the plaster patch grows, and one crack extends.
                wet_height = 45 + index * 14
                mark.rectangle((x0 + 5, y1 - wet_height, x1 - 5, y1 - 5), fill=(56, 40, 31, 20 + index * 5))
                mark.ellipse((285 - index * 5, 980 - index * 7, 375 + index * 7, 1045 + index * 7),
                             fill=(94, 74, 55, 175), outline=(130, 108, 81, 220), width=4)
                crack = [(900, 680), (905, 700), (896, 719), (909, 740), (900, 761),
                         (910, 783), (899, 808), (907, 830), (897, 855), (904, 875)]
                visible = crack[:min(len(crack), 3 + index)]
                mark.line(visible, fill=(51, 40, 32, 230), width=3 + index // 3, joint="curve")
                frame = Image.alpha_composite(frame.convert("RGBA"), overlay).convert("RGB")
            frame.save(target / f"frame_{index:03d}.jpg", quality=90, subsampling=0)
    (folder / MARKER).write_text(VERSION, encoding="utf-8")
