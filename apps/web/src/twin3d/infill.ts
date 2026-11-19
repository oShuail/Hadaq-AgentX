// Ruin infill for At-Turaif (owner: Omar). The district is a dense town of mud-brick ruins, but only its
// restored core is mapped, so the open parts are filled with courtyard houses whose walls stand at uneven
// heights: rooms along two to four sides of a yard, a few restored to full height with crenellations.
// Illustrative only, not surveyed (ids "i...", heights "estimated"). Deterministic: same town every load.
import { Grid2D, centroid, hash01, pointInRing, ringBounds, signedArea } from "../site/poly";
import type { SiteBuilding, SitePack } from "../site/types";

/** Dominant street direction of the mapped buildings inside the district (radians, 0..90 degrees). */
function gridAngle(buildings: SiteBuilding[]): number {
  const bins = new Float64Array(90);
  for (const b of buildings)
    for (let i = 0; i < b.o.length; i += 2) {
      const j = (i + 2) % b.o.length;
      const dx = b.o[j] - b.o[i];
      const dy = b.o[j + 1] - b.o[i + 1];
      const len = Math.hypot(dx, dy);
      let a = (Math.atan2(dy, dx) * 180) / Math.PI;
      a = ((a % 90) + 90) % 90;
      bins[Math.floor(a) % 90] += len;
    }
  let best = 0;
  let bestW = -1;
  for (let a = 0; a < 90; a++) {
    // Smooth over +-3 degrees.
    let w = 0;
    for (let k = -3; k <= 3; k++) w += bins[(a + k + 90) % 90];
    if (w > bestW) {
      bestW = w;
      best = a;
    }
  }
  return (best * Math.PI) / 180;
}

const near = ([a, b]: [number, number], x: number, y: number, r: number) => Math.hypot(a - x, b - y) < r;

function rect(cx: number, cy: number, w: number, d: number, ang: number): number[] {
  const c = Math.cos(ang);
  const s = Math.sin(ang);
  const out: number[] = [];
  for (const [u, v] of [[-w / 2, -d / 2], [w / 2, -d / 2], [w / 2, d / 2], [-w / 2, d / 2]]) {
    out.push(Math.round((cx + u * c - v * s) * 10) / 10, Math.round((cy + u * s + v * c) * 10) / 10);
  }
  return signedArea(out) >= 0 ? out : [out[6], out[7], out[4], out[5], out[2], out[3], out[0], out[1]];
}

export function turaifInfill(pack: SitePack, keepClear: (x: number, y: number) => boolean): SiteBuilding[] {
  const turaif = pack.areas.find((a) => a.k === "heritage" && /turaif/i.test(a.n ?? ""));
  if (!turaif) return [];
  const area = turaif.p;
  const mapped = pack.buildings.filter((b) => !b.id.startsWith("i") && pointInRing(...centroid(b.o), area));
  const ang = gridAngle(mapped.length ? mapped : pack.buildings.slice(0, 200));
  const ca = Math.cos(ang);
  const sa = Math.sin(ang);

  const taken = new Grid2D<number[]>(30);
  for (const b of pack.buildings) {
    if (b.id.startsWith("i")) continue;
    const bb = ringBounds(b.o);
    taken.insert(b.o, bb[0] - 3, bb[1] - 3, bb[2] + 3, bb[3] + 3);
  }
  const lanes = new Grid2D<[number, number]>(15);
  for (const l of [...pack.paths, ...pack.roads]) {
    const p = l.p;
    for (let i = 0; i + 3 < p.length; i += 2) {
      const n = Math.max(1, Math.ceil(Math.hypot(p[i + 2] - p[i], p[i + 3] - p[i + 1]) / 2));
      for (let k = 0; k <= n; k++) {
        const x = p[i] + ((p[i + 2] - p[i]) * k) / n;
        const y = p[i + 1] + ((p[i + 3] - p[i + 1]) * k) / n;
        lanes.insert([x, y], x - l.w / 2 - 2, y - l.w / 2 - 2, x + l.w / 2 + 2, y + l.w / 2 + 2);
      }
    }
  }
  const blocked = (x: number, y: number) =>
    keepClear(x, y) ||
    !pointInRing(x, y, area) ||
    taken.query(x, y).some((o) => pointInRing(x, y, o) || near(centroid(o), x, y, 6)) ||
    lanes.query(x, y).length > 0;

  const out: SiteBuilding[] = [];
  const [bx0, by0, bx1, by1] = ringBounds(area);
  const cx0 = (bx0 + bx1) / 2;
  const cy0 = (by0 + by1) / 2;
  const reach = Math.hypot(bx1 - bx0, by1 - by0) / 2 + 20;
  const CELL = 15;
  let k = 0;
  let id = 0;
  for (let v = -reach; v < reach; v += CELL) {
    // Every other row is shifted, so the blocks do not line up like a chessboard.
    const shift = (Math.floor((v + reach) / CELL) % 2) * CELL * 0.45;
    for (let u = -reach + shift; u < reach; u += CELL) {
      k++;
      const r = (n: number) => hash01(k * 31 + n);
      if (r(0) < 0.1) continue; // open yards and squares
      const w = CELL - 1.6 - r(1) * 2.4; // house plot, metres (lanes of 1.5 to 4 m between plots)
      const d = CELL - 1.6 - r(2) * 2.4;
      const uu = u + (r(3) - 0.5) * 3;
      const vv = v + (r(4) - 0.5) * 3;
      const x = cx0 + uu * ca - vv * sa;
      const y = cy0 + uu * sa + vv * ca;
      const corners = [[0, 0], [w / 2, d / 2], [-w / 2, d / 2], [w / 2, -d / 2], [-w / 2, -d / 2]].map(([a, b]) => [x + a * ca - b * sa, y + a * sa + b * ca]);
      if (corners.some(([px, py]) => blocked(px, py))) continue;
      const a = ang + (r(5) - 0.5) * 0.08;
      const restored = r(6) < 0.1;
      const wing = 3.8 + r(7) * 1.6; // room depth
      const pattern = r(8);
      // Wings as [offset along u, offset along v, width, depth] in the plot's frame.
      const wings: [number, number, number, number][] = [];
      if (restored || pattern < 0.12) {
        wings.push([0, 0, w * 0.8, d * 0.8]); // a solid house
      } else {
        wings.push([0, -(d - wing) / 2, w, wing]); // south range
        if (pattern > 0.3) wings.push([-(w - wing) / 2, wing / 2, wing, d - wing]); // west range
        if (pattern > 0.55) wings.push([wing / 2, (d - wing) / 2, w - wing, wing]); // north range
        if (pattern > 0.8) wings.push([(w - wing) / 2, 0, wing, d - wing * 2]); // east range, closing the yard
      }
      const c = Math.cos(a);
      const s = Math.sin(a);
      wings.forEach(([ou, ov, ww, dd], wi) => {
        if (ww < 2 || dd < 2) return;
        const h = restored ? 6 + r(10) * 2.2 : r(11 + wi) < 0.25 ? 1.2 + r(20 + wi) * 1.2 : 2.6 + r(20 + wi) * 2.6;
        out.push({
          id: `i${++id}`,
          h: Math.round(h * 10) / 10,
          hs: "estimated",
          k: restored ? "najdi" : "ruin",
          o: rect(x + ou * c - ov * s, y + ou * s + ov * c, ww, dd, a),
        });
      });
    }
  }
  return out;
}
