// Small 2D polygon helpers on flat [x0, y0, x1, y1, ...] rings (owner: Omar).

/** Signed area, positive when counter-clockwise (x east, y north). */
export function signedArea(r: number[]): number {
  let a = 0;
  for (let i = 0, n = r.length; i < n; i += 2) {
    const j = (i + 2) % n;
    a += r[i] * r[j + 1] - r[j] * r[i + 1];
  }
  return a / 2;
}

export function centroid(r: number[]): [number, number] {
  let cx = 0;
  let cy = 0;
  let a = 0;
  for (let i = 0, n = r.length; i < n; i += 2) {
    const j = (i + 2) % n;
    const f = r[i] * r[j + 1] - r[j] * r[i + 1];
    cx += (r[i] + r[j]) * f;
    cy += (r[i + 1] + r[j + 1]) * f;
    a += f;
  }
  if (Math.abs(a) < 1e-9) {
    // Degenerate: average of the points.
    let sx = 0;
    let sy = 0;
    for (let i = 0; i < r.length; i += 2) {
      sx += r[i];
      sy += r[i + 1];
    }
    return [sx / (r.length / 2), sy / (r.length / 2)];
  }
  return [cx / (3 * a), cy / (3 * a)];
}

export function pointInRing(x: number, y: number, r: number[]): boolean {
  let inside = false;
  for (let i = 0, n = r.length, j = n - 2; i < n; j = i, i += 2) {
    const xi = r[i];
    const yi = r[i + 1];
    const xj = r[j];
    const yj = r[j + 1];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

export function ringBounds(r: number[]): [number, number, number, number] {
  let x0 = Infinity;
  let y0 = Infinity;
  let x1 = -Infinity;
  let y1 = -Infinity;
  for (let i = 0; i < r.length; i += 2) {
    if (r[i] < x0) x0 = r[i];
    if (r[i] > x1) x1 = r[i];
    if (r[i + 1] < y0) y0 = r[i + 1];
    if (r[i + 1] > y1) y1 = r[i + 1];
  }
  return [x0, y0, x1, y1];
}

export function perimeter(r: number[], closed = true): number {
  let p = 0;
  const n = r.length;
  const last = closed ? n : n - 2;
  for (let i = 0; i < last; i += 2) {
    const j = (i + 2) % n;
    p += Math.hypot(r[j] - r[i], r[j + 1] - r[i + 1]);
  }
  return p;
}

/** Douglas-Peucker on an open polyline (keeps both ends). */
export function simplifyLine(p: number[], tolerance: number): number[] {
  const n = p.length / 2;
  if (n <= 2) return p.slice();
  const keep = new Uint8Array(n);
  keep[0] = keep[n - 1] = 1;
  const stack: [number, number][] = [[0, n - 1]];
  const t2 = tolerance * tolerance;
  while (stack.length) {
    const [a, b] = stack.pop()!;
    const ax = p[a * 2];
    const ay = p[a * 2 + 1];
    const dx = p[b * 2] - ax;
    const dy = p[b * 2 + 1] - ay;
    const len2 = dx * dx + dy * dy || 1e-12;
    let worst = -1;
    let worstD = t2;
    for (let i = a + 1; i < b; i++) {
      const px = p[i * 2] - ax;
      const py = p[i * 2 + 1] - ay;
      const t = Math.max(0, Math.min(1, (px * dx + py * dy) / len2));
      const ex = px - t * dx;
      const ey = py - t * dy;
      const d = ex * ex + ey * ey;
      if (d > worstD) {
        worstD = d;
        worst = i;
      }
    }
    if (worst >= 0) {
      keep[worst] = 1;
      stack.push([a, worst], [worst, b]);
    }
  }
  const out: number[] = [];
  for (let i = 0; i < n; i++) if (keep[i]) out.push(p[i * 2], p[i * 2 + 1]);
  return out;
}

/** Simplify a closed ring; returns null when it collapses below a triangle. */
export function simplifyRing(r: number[], tolerance: number): number[] | null {
  if (r.length < 6) return null;
  // Split at the point farthest from the first so both halves keep their shape.
  let far = 0;
  let farD = -1;
  for (let i = 2; i < r.length; i += 2) {
    const d = (r[i] - r[0]) ** 2 + (r[i + 1] - r[1]) ** 2;
    if (d > farD) {
      farD = d;
      far = i;
    }
  }
  const a = simplifyLine(r.slice(0, far + 2), tolerance);
  const b = simplifyLine([...r.slice(far), r[0], r[1]], tolerance);
  const out = [...a, ...b.slice(2, -2)];
  return out.length >= 6 ? out : null;
}

/** Spatial hash for "which of these items is near this point" queries. */
export class Grid2D<T> {
  private cells = new Map<number, T[]>();
  constructor(private cell: number) {}
  private key(ix: number, iy: number) {
    return (ix + 32768) * 65536 + (iy + 32768);
  }
  insert(item: T, x0: number, y0: number, x1: number, y1: number) {
    const c = this.cell;
    for (let ix = Math.floor(x0 / c); ix <= Math.floor(x1 / c); ix++)
      for (let iy = Math.floor(y0 / c); iy <= Math.floor(y1 / c); iy++) {
        const k = this.key(ix, iy);
        const list = this.cells.get(k);
        if (list) list.push(item);
        else this.cells.set(k, [item]);
      }
  }
  query(x: number, y: number): T[] {
    return this.cells.get(this.key(Math.floor(x / this.cell), Math.floor(y / this.cell))) ?? [];
  }
}

/** Deterministic pseudo-random number in [0, 1) from a seed (same city every reload). */
export function hash01(seed: number): number {
  let h = Math.imul(seed ^ 0x9e3779b9, 0x85ebca6b);
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}
