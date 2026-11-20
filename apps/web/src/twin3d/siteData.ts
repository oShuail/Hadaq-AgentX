// Loads the baked site pack (public/site/) and answers "how high is the ground here" (owner: Omar).
import { makeProjection, type Projection } from "../site/projection";
import { pointInRing, ringBounds } from "../site/poly";
import type { SitePack, TerrainGrid, SiteBox } from "../site/types";

export interface HeightGrid {
  grid: TerrainGrid;
  box: SiteBox;
  data: Float32Array;
}

export interface Site {
  pack: SitePack;
  proj: Projection;
  core: HeightGrid;
  wide: HeightGrid;
  palms: Float32Array;
  /** Ground height (m, relative to the origin) at local x (east) / y (north). */
  ground: (x: number, y: number) => number;
  /** Flatten the fine terrain inside a polygon (plus a soft margin) to one height, e.g. under a placed model. */
  flatten: (ring: number[], height: number, margin: number) => void;
}

const BASE = "/site/";

async function bin(file: string): Promise<Float32Array> {
  const res = await fetch(BASE + file);
  if (!res.ok) throw new Error(`${file}: ${res.status}`);
  return new Float32Array(await res.arrayBuffer());
}

function sampler(h: HeightGrid) {
  const { nx, ny } = h.grid;
  const { x0, x1, y0, y1 } = h.box;
  return (x: number, y: number): number | null => {
    const fx = ((x - x0) / (x1 - x0)) * (nx - 1);
    const fy = ((y1 - y) / (y1 - y0)) * (ny - 1);
    if (fx < 0 || fy < 0 || fx > nx - 1 || fy > ny - 1) return null;
    const i = Math.min(nx - 2, Math.floor(fx));
    const j = Math.min(ny - 2, Math.floor(fy));
    const tx = fx - i;
    const ty = fy - j;
    const d = h.data;
    const a = d[j * nx + i];
    const b = d[j * nx + i + 1];
    const c = d[(j + 1) * nx + i];
    const e = d[(j + 1) * nx + i + 1];
    // Same split as the terrain mesh triangles (PlaneGeometry: a-c-b and c-e-b), so things sit exactly on the surface.
    if (tx + ty <= 1) return a + (b - a) * tx + (c - a) * ty;
    return e + (c - e) * (1 - tx) + (b - e) * (1 - ty);
  };
}

export async function loadSite(onProgress: (msg: string) => void): Promise<Site | null> {
  const res = await fetch(BASE + "site.json");
  if (!res.ok || !(res.headers.get("content-type") ?? "").includes("json")) return null;
  onProgress("Reading the site model");
  const pack = (await res.json()) as SitePack;
  onProgress("Loading terrain and palms");
  const [coreData, wideData, palms] = await Promise.all([bin(pack.terrain.core.file), bin(pack.terrain.wide.file), bin(pack.palms.file)]);
  const core: HeightGrid = { grid: pack.terrain.core, box: pack.boxes.core, data: coreData };
  const wide: HeightGrid = { grid: pack.terrain.wide, box: pack.boxes.wide, data: wideData };
  const coreAt = sampler(core);
  const wideAt = sampler(wide);
  const ground = (x: number, y: number) => coreAt(x, y) ?? wideAt(x, y) ?? 0;

  const flatten = (ring: number[], height: number, margin: number) => {
    const { nx, ny } = core.grid;
    const { x0, x1, y0, y1 } = core.box;
    const [bx0, by0, bx1, by1] = ringBounds(ring);
    const dx = (x1 - x0) / (nx - 1);
    const dy = (y1 - y0) / (ny - 1);
    const i0 = Math.max(0, Math.floor((bx0 - margin - x0) / dx));
    const i1 = Math.min(nx - 1, Math.ceil((bx1 + margin - x0) / dx));
    const j0 = Math.max(0, Math.floor((y1 - by1 - margin) / dy));
    const j1 = Math.min(ny - 1, Math.ceil((y1 - by0 + margin) / dy));
    for (let j = j0; j <= j1; j++)
      for (let i = i0; i <= i1; i++) {
        const x = x0 + i * dx;
        const y = y1 - j * dy;
        let w = 0;
        if (pointInRing(x, y, ring)) w = 1;
        else {
          // Distance to the polygon edge for a soft blend into the surrounding ground.
          let best = Infinity;
          for (let k = 0, n = ring.length; k < n; k += 2) {
            const ax = ring[k];
            const ay = ring[k + 1];
            const bx = ring[(k + 2) % n];
            const by = ring[(k + 3) % n];
            const ex = bx - ax;
            const ey = by - ay;
            const t = Math.max(0, Math.min(1, ((x - ax) * ex + (y - ay) * ey) / (ex * ex + ey * ey || 1)));
            best = Math.min(best, Math.hypot(x - ax - t * ex, y - ay - t * ey));
          }
          if (best < margin) w = 1 - best / margin;
        }
        if (w > 0) core.data[j * nx + i] = core.data[j * nx + i] * (1 - w) + height * w;
      }
  };

  return { pack, proj: makeProjection(pack.origin.lat, pack.origin.lon), core, wide, palms, ground, flatten };
}
