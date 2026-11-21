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
