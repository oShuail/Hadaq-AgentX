"""Build one 3D model of the At-Turaif district (GLB) from OpenStreetMap building outlines (owner: Omar).

Input:  a GeoJSON export from overpass-turbo.eu (see tools/turaif_model/README.md for the query).
Output: apps/web/public/models/turaif.glb   the district, one glTF node per building, named by its OSM id ("w123", "r456")
        apps/web/public/models/turaif.json  origin, plus name, height, centroid and outline of every building (the web app reads it)

Every building is its real OSM outline, extruded to its OSM height (or an estimate from its size, marked as such),
with a mud-plaster texture, a parapet and a row of triangular Najdi crenellations. Courtyards (inner rings) stay open.
This is a massing model for context, not a survey: say so on the slide.

Axes: the web app places the GLB with no rotation, and Cesium turns glTF +X to north, +Y to up, +Z to east,
so a local point (east, north, up) is written as (north, up, east).

Run:
    .venv/bin/pip install mapbox-earcut      # numpy and pillow come with the vision service
    .venv/bin/python tools/turaif_model/build_district.py --input assets/turaif/turaif_osm.geojson
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import struct
from dataclasses import dataclass, field
from pathlib import Path

import mapbox_earcut as earcut
import numpy as np
from PIL import Image

EARTH_R = 6378137.0
SKIRT_M = 3.0  # walls go this far below ground so slopes never show a gap under a building
PARAPET_M = 0.9  # parapet above the roof
MERLON_W, MERLON_H, MERLON_STEP = 0.55, 0.6, 1.0  # triangular crenellations (shurfat)
WALL_TEX_METERS = 3.0  # wall texture repeats every 3 m along the wall


# ---------- input ----------


@dataclass
class Building:
    node: str  # glTF node name, e.g. "w123456"
    osm_type: str
    osm_id: int
    tags: dict
    polygons: list[list[list[tuple[float, float]]]]  # polygons -> rings (outer first) -> (lon, lat)
    height_m: float = 0.0
    height_source: str = ""
    area_m2: float = 0.0
    rings_local: list[list[np.ndarray]] = field(default_factory=list)


def parse_osm_id(feature: dict) -> tuple[str, int] | None:
    raw = str(feature.get("id") or feature.get("properties", {}).get("@id") or "")
    if "/" not in raw:
        return None
    kind, num = raw.split("/", 1)
    try:
        return kind, int(num)
    except ValueError:
        return None


def load_buildings(path: Path) -> list[Building]:
    data = json.loads(path.read_text(encoding="utf-8"))
    out: list[Building] = []
    for f in data.get("features", []):
        props = f.get("properties") or {}
        tags = props.get("tags", props)  # some exporters nest tags
        if "building" not in tags or tags.get("building") == "no":
            continue
        ident = parse_osm_id(f)
        geom = f.get("geometry") or {}
        if ident is None or geom.get("type") not in ("Polygon", "MultiPolygon"):
            continue
        polys = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]
        polys = [[[(float(p[0]), float(p[1])) for p in ring] for ring in poly] for poly in polys]
        kind, num = ident
        out.append(Building(node=f"{kind[0]}{num}", osm_type=kind, osm_id=num, tags=tags, polygons=polys))
    return out


# ---------- geometry helpers ----------


def to_local(lon: float, lat: float, lon0: float, lat0: float) -> tuple[float, float]:
    east = math.radians(lon - lon0) * EARTH_R * math.cos(math.radians(lat0))
    north = math.radians(lat - lat0) * EARTH_R
    return east, north


def to_lonlat(east: float, north: float, lon0: float, lat0: float) -> tuple[float, float]:
    lat = lat0 + math.degrees(north / EARTH_R)
    lon = lon0 + math.degrees(east / (EARTH_R * math.cos(math.radians(lat0))))
    return lon, lat


def signed_area(ring: np.ndarray) -> float:
    x, y = ring[:, 0], ring[:, 1]
    return 0.5 * float(np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y))


def clean_ring(ring: np.ndarray) -> np.ndarray | None:
    if len(ring) > 1 and np.allclose(ring[0], ring[-1]):
        ring = ring[:-1]
    keep = [ring[0]]
    for p in ring[1:]:
        if np.hypot(*(p - keep[-1])) > 0.05:
            keep.append(p)
    if len(keep) > 2 and np.hypot(*(keep[0] - keep[-1])) <= 0.05:
        keep.pop()
    return np.array(keep) if len(keep) >= 3 else None


def parse_height(tags: dict) -> float | None:
    for key in ("height", "building:height"):
        raw = str(tags.get(key, "")).lower().replace("m", "").strip()
        try:
            if raw:
                return float(raw)
        except ValueError:
            pass
    try:
        levels = float(tags.get("building:levels", ""))
        return levels * 3.6 + 0.5
    except ValueError:
        return None


def estimate_height(area: float, seed: str) -> float:
    jitter = (int(hashlib.md5(seed.encode()).hexdigest()[:6], 16) / 0xFFFFFF - 0.5) * 1.6
    base = 4.5 if area < 60 else 6.0 if area < 300 else 7.5 if area < 1000 else 9.0
    return round(base + jitter, 1)


# ---------- textures (periodic noise, so they tile without seams) ----------


def periodic_noise(h: int, w: int, rng: np.random.Generator, power: float = 2.0, stretch_y: float = 1.0) -> np.ndarray:
    white = rng.standard_normal((h, w))
    fy = np.fft.fftfreq(h)[:, None] / stretch_y
    fx = np.fft.fftfreq(w)[None, :]
    f = np.sqrt(fx**2 + fy**2)
    f[0, 0] = 1.0
    field_ = np.real(np.fft.ifft2(np.fft.fft2(white) / f ** (power / 2)))
    return (field_ - field_.mean()) / (field_.std() + 1e-9)


def mud_texture(size: int, base_rgb: tuple[int, int, int], seed: int, wall: bool) -> bytes:
    rng = np.random.default_rng(seed)
    n = 0.06 * periodic_noise(size, size, rng, 2.2) + 0.025 * periodic_noise(size, size, rng, 0.8)
    if wall:
        n += 0.035 * periodic_noise(size, size, rng, 1.6, stretch_y=0.08)  # vertical rain streaks
    shade = 1.0 + n
    speck = rng.random((size, size))
    shade[speck > 0.996] *= 0.82  # straw and grit
    shade[speck < 0.003] *= 1.08
    if wall:
        v = np.linspace(0.0, 1.0, size)[:, None]  # 0 = top of wall, 1 = ground
        shade *= 1.0 - 0.22 * np.clip((v - 0.86) / 0.14, 0, 1)  # darker, damp-looking base
        shade *= 1.0 - 0.08 * np.clip((0.06 - v) / 0.06, 0, 1)  # weathered top under the parapet
    rgb = np.clip(np.array(base_rgb, dtype=float)[None, None, :] * shade[:, :, None], 0, 255).astype(np.uint8)
    buf = io.BytesIO()
    Image.fromarray(rgb, "RGB").save(buf, "PNG", optimize=True)
    return buf.getvalue()


# ---------- mesh building ----------


class MeshPart:
    def __init__(self) -> None:
        self.pos: list[tuple[float, float, float]] = []  # (east, north, up)
        self.nrm: list[tuple[float, float, float]] = []
        self.uv: list[tuple[float, float]] = []
        self.idx: list[int] = []

    def tri(self, a, b, c, uva, uvb, uvc) -> None:
        a, b, c = np.asarray(a, float), np.asarray(b, float), np.asarray(c, float)
        n = np.cross(b - a, c - a)
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            return
        n = tuple(n / ln)
        base = len(self.pos)
        for p, t in ((a, uva), (b, uvb), (c, uvc)):
            self.pos.append(tuple(p))
            self.nrm.append(n)
            self.uv.append(t)
        self.idx += [base, base + 1, base + 2]

    def quad(self, a, b, c, d, uva, uvb, uvc, uvd) -> None:
        self.tri(a, b, c, uva, uvb, uvc)
        self.tri(a, c, d, uva, uvc, uvd)


def wall_v(z: float, top: float) -> float:
    """Texture row: 0 at the top of the wall, 1 at the ground (the skirt below ground stays at 1)."""
    return float(np.clip(1.0 - z / top, 0.0, 1.0))


def build_walls(part: MeshPart, ring: np.ndarray, top: float, merlons: bool) -> None:
    dist = 0.0
    for i in range(len(ring)):
        p0, p1 = ring[i], ring[(i + 1) % len(ring)]
        length = float(np.hypot(*(p1 - p0)))
        u0, u1 = dist / WALL_TEX_METERS, (dist + length) / WALL_TEX_METERS
        a, b = (p0[0], p0[1], -SKIRT_M), (p1[0], p1[1], -SKIRT_M)
        c, d = (p1[0], p1[1], top), (p0[0], p0[1], top)
        part.quad(a, b, c, d, (u0, 1.0), (u1, 1.0), (u1, 0.0), (u0, 0.0))
        if merlons and length > MERLON_STEP:
            direction = (p1 - p0) / length
            count = int((length - 0.3) // MERLON_STEP)
            start = (length - (count - 1) * MERLON_STEP) / 2 if count > 0 else 0
            for k in range(count):
                mid = start + k * MERLON_STEP
                left = p0 + direction * (mid - MERLON_W / 2)
                right = p0 + direction * (mid + MERLON_W / 2)
                apex = p0 + direction * mid
                um = (dist + mid) / WALL_TEX_METERS
                part.tri(
                    (left[0], left[1], top), (right[0], right[1], top), (apex[0], apex[1], top + MERLON_H),
                    (um - 0.09, 0.02), (um + 0.09, 0.02), (um, 0.0),
                )
        dist += length


def build_roof(part: MeshPart, rings: list[np.ndarray], z: float) -> None:
    verts = np.concatenate(rings).astype(np.float64)
    ends = np.cumsum([len(r) for r in rings]).astype(np.uint32)
    tris = np.asarray(earcut.triangulate_float64(verts, ends)).reshape(-1, 3)
    for i, j, k in tris:
        a, b, c = verts[i], verts[j], verts[k]
        if signed_area(np.array([a, b, c])) < 0:  # make every roof triangle face up
            b, c = c, b
        part.tri((a[0], a[1], z), (b[0], b[1], z), (c[0], c[1], z),
                 (a[0] / 8, a[1] / 8), (b[0] / 8, b[1] / 8), (c[0] / 8, c[1] / 8))


# ---------- GLB writer ----------


class GlbWriter:
    def __init__(self) -> None:
        self.bin = bytearray()
        self.views: list[dict] = []
        self.accessors: list[dict] = []

    def _view(self, data: bytes, target: int | None) -> int:
        while len(self.bin) % 4:
            self.bin.append(0)
        view = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)}
        if target:
            view["target"] = target
        self.bin += data
        self.views.append(view)
        return len(self.views) - 1

    def accessor(self, arr: np.ndarray, kind: str, component: int, target: int | None, minmax: bool = False) -> int:
        view = self._view(arr.tobytes(), target)
        acc = {"bufferView": view, "componentType": component, "count": int(arr.shape[0]), "type": kind}
        if minmax:
            acc["min"] = arr.min(axis=0).astype(float).tolist()
            acc["max"] = arr.max(axis=0).astype(float).tolist()
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def image(self, png: bytes) -> int:
        return self._view(png, None)

    def primitive(self, part: MeshPart, material: int) -> dict | None:
        if not part.idx:
            return None
        enu = np.array(part.pos, dtype=np.float32)
        gltf_pos = enu[:, [1, 2, 0]]  # (east, north, up) -> glTF (north, up, east)
        gltf_nrm = np.array(part.nrm, dtype=np.float32)[:, [1, 2, 0]]
        return {
            "attributes": {
                "POSITION": self.accessor(np.ascontiguousarray(gltf_pos), "VEC3", 5126, 34962, minmax=True),
                "NORMAL": self.accessor(np.ascontiguousarray(gltf_nrm), "VEC3", 5126, 34962),
                "TEXCOORD_0": self.accessor(np.array(part.uv, dtype=np.float32), "VEC2", 5126, 34962),
            },
            "indices": self.accessor(np.array(part.idx, dtype=np.uint32), "SCALAR", 5125, 34963),
            "material": material,
        }

    def write(self, path: Path, gltf: dict) -> None:
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        js += b" " * (-len(js) % 4)
        binary = bytes(self.bin) + b"\0" * (-len(self.bin) % 4)
        total = 12 + 8 + len(js) + 8 + len(binary)
        with path.open("wb") as fh:
            fh.write(struct.pack("<III", 0x46546C67, 2, total))
            fh.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            fh.write(struct.pack("<II", len(binary), 0x004E4942) + binary)


# ---------- main ----------


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", type=Path, required=True, help="GeoJSON from overpass-turbo")
    ap.add_argument("--out-glb", type=Path, default=Path("apps/web/public/models/turaif.glb"))
    ap.add_argument("--out-json", type=Path, default=Path("apps/web/public/models/turaif.json"))
    args = ap.parse_args()

    buildings = load_buildings(args.input)
    if not buildings:
        raise SystemExit("No building polygons in the input. Check the overpass query and the export format (GeoJSON).")

    all_pts = np.array([p for b in buildings for poly in b.polygons for ring in poly for p in ring])
    lon0 = float((all_pts[:, 0].min() + all_pts[:, 0].max()) / 2)
    lat0 = float((all_pts[:, 1].min() + all_pts[:, 1].max()) / 2)

    writer = GlbWriter()
    wall_img = writer.image(mud_texture(512, (201, 162, 126), 7, wall=True))
    roof_img = writer.image(mud_texture(256, (214, 181, 147), 11, wall=False))
    gltf: dict = {
        "asset": {"version": "2.0", "generator": "hadaq build_district.py (OSM outlines, massing model)"},
        "scene": 0,
        "images": [{"bufferView": wall_img, "mimeType": "image/png"}, {"bufferView": roof_img, "mimeType": "image/png"}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 33071},
                     {"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
        "textures": [{"source": 0, "sampler": 0}, {"source": 1, "sampler": 1}],
        "materials": [
            {"name": "mud_wall", "doubleSided": True,
             "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0, "roughnessFactor": 1.0}},
            {"name": "mud_roof",
             "pbrMetallicRoughness": {"baseColorTexture": {"index": 1}, "metallicFactor": 0.0, "roughnessFactor": 1.0}},
        ],
        "meshes": [],
        "nodes": [],
    }
    sidecar = []
    skipped = 0
    for b in buildings:
        walls, roof = MeshPart(), MeshPart()
        area = 0.0
        polys_local = []
        for poly in b.polygons:
            rings = []
            for k, ring in enumerate(poly):
                local = clean_ring(np.array([to_local(lon, lat, lon0, lat0) for lon, lat in ring]))
                if local is None:
                    continue
                if k == 0 and signed_area(local) < 0 or k > 0 and signed_area(local) > 0:
                    local = local[::-1]  # outer ring counter-clockwise, courtyards clockwise
                rings.append(local)
            if rings and len(rings[0]) >= 3:
                polys_local.append(rings)
                area += sum(signed_area(r) for r in rings)
        if not polys_local or area < 4:
            skipped += 1
            continue
        h = parse_height(b.tags)
        b.height_source = "osm" if h else "estimated"
        b.height_m = h if h else estimate_height(area, b.node)
        b.area_m2 = area
        roof_z = b.height_m - PARAPET_M
        for rings in polys_local:
            build_roof(roof, rings, roof_z)
            for k, ring in enumerate(rings):
                build_walls(walls, ring, b.height_m, merlons=(k == 0))
        prims = [p for p in (writer.primitive(walls, 0), writer.primitive(roof, 1)) if p]
        gltf["meshes"].append({"name": b.node, "primitives": prims})
        gltf["nodes"].append({"name": b.node, "mesh": len(gltf["meshes"]) - 1})
        outer = max(polys_local, key=lambda rs: signed_area(rs[0]))[0]
        ce, cn = outer.mean(axis=0)
        clon, clat = to_lonlat(float(ce), float(cn), lon0, lat0)
        sidecar.append({
            "node": b.node, "osm_type": b.osm_type, "osm_id": b.osm_id,
            "name": b.tags.get("name", ""), "name_en": b.tags.get("name:en", ""),
            "height_m": round(b.height_m, 1), "height_source": b.height_source, "area_m2": round(area, 1),
            "centroid": {"lon": round(clon, 7), "lat": round(clat, 7)},
            "outline": [[round(lon, 7), round(lat, 7)] for lon, lat in (to_lonlat(e, n, lon0, lat0) for e, n in outer)],
        })

    gltf["scenes"] = [{"nodes": list(range(len(gltf["nodes"])))}]
    args.out_glb.parent.mkdir(parents=True, exist_ok=True)
    writer.write(args.out_glb, gltf)
    meta = {
        "origin": {"lon": lon0, "lat": lat0},
        "note": "Massing model generated from OpenStreetMap outlines (ODbL). Heights from OSM where tagged, otherwise estimated from footprint size.",
        "buildings": sidecar,
    }
    args.out_json.write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")

    named = [s for s in sidecar if s["name"] or s["name_en"]]
    print(f"{len(sidecar)} buildings -> {args.out_glb} ({args.out_glb.stat().st_size / 1e6:.1f} MB), skipped {skipped}")
    print(f"heights: {sum(s['height_source'] == 'osm' for s in sidecar)} from OSM, {sum(s['height_source'] == 'estimated' for s in sidecar)} estimated")
    for s in sorted(named, key=lambda s: -s["area_m2"])[:25]:
        print(f"  {s['node']:>12}  {s['area_m2']:8.0f} m2  {s['height_m']:4.1f} m  {s['name_en'] or ''} {s['name'] or ''}")


if __name__ == "__main__":
    main()
