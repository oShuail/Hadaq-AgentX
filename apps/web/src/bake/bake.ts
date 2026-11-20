// Site data bake (owner: Omar). Run once with the dev server up: http://localhost:5173/bake.html
// Fetches open data for Diriyah in the browser, turns it into the compact pack in public/site/ that the
// 3D twin loads, and writes each file through the dev server (vite.config.ts, /__bake/).
//
// Sources: OpenStreetMap (Overpass, or the OSM API as a fallback), Microsoft Global ML Building
// Footprints, Esri World Imagery, Terrain Tiles on AWS (Terrarium encoding, SRTM-class in Saudi Arabia).
// Nothing here is measured by us: building heights are mostly estimates and say so (`hs`).

import { makeProjection, type Projection } from "../site/projection";
import { Grid2D, centroid, hash01, pointInRing, ringBounds, signedArea, simplifyLine, simplifyRing } from "../site/poly";
import type { LonLatBox, SiteBox, SiteBuilding, SiteLine, SitePack, SitePoi, SitePolygon } from "../site/types";

// ------------------------------------------------------------------------------------------------
// Where

const ORIGIN = { lat: 24.7336, lon: 46.5725 }; // At-Turaif, same as config.SITE
const DETAIL: LonLatBox = { s: 24.7255, n: 24.7425, w: 46.56, e: 46.583 }; // At-Turaif, Bujairi, the wadi
const CORE: LonLatBox = { s: 24.715, n: 24.76, w: 46.545, e: 46.6 }; // buildings, palms, fine terrain
const WIDE: LonLatBox = { s: 24.63, n: 24.84, w: 46.44, e: 46.71 }; // context terrain to the horizon

const ZOOM = { detail: 17, core: 16, wide: 13 };
const TERRAIN = { coreZoom: 14, coreStep: 10, wideZoom: 11, wideCells: 288 };

const ESRI = (z: number, x: number, y: number) =>
  `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`;
const TERRARIUM = (z: number, x: number, y: number) => `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${z}/${x}/${y}.png`;
const OVERPASS = [
  "https://overpass-api.de/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
  "https://overpass.private.coffee/api/interpreter",
];
const MSB_LINKS = "/__proxy/msb/global-buildings/dataset-links.csv";

// Around Bujairi (across the wadi from At-Turaif) the new buildings are built in Najdi mud style too.
const BUJAIRI = { lon: 46.57485, lat: 24.73649 }; // Bujairi Terrace (Al Bujairi, east bank of the wadi)
const NAJDI_DISCS = [{ ...BUJAIRI, r: 300 }];

// Heritage districts: buildings inside these get the Najdi mud style. Matched on the OSM area name.
const HERITAGE_NAME = /turaif|طريف|bujairi|bujayri|بجيري|samhan|سمحان|ghusaib|غصيب|zallal|ظلال|diriyah gate|الدرعية التاريخية|historic/i;

// ------------------------------------------------------------------------------------------------
// Small utilities

const logEl = () => document.getElementById("log")!;
function log(msg: string) {
  const line = `[${new Date().toLocaleTimeString()}] ${msg}`;
  console.info(line);
  logEl().textContent += line + "\n";
  logEl().scrollTop = logEl().scrollHeight;
}

async function retry<T>(what: string, fn: () => Promise<T>, tries = 4): Promise<T> {
  let last: unknown;
  for (let i = 0; i < tries; i++) {
    try {
      return await fn();
    } catch (err) {
      last = err;
      await new Promise((r) => setTimeout(r, 800 * (i + 1)));
    }
  }
  throw new Error(`${what} failed: ${String(last)}`);
}

async function pool<T, R>(items: T[], limit: number, fn: (item: T, i: number) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i], i);
      }
    }),
  );
  return out;
}

async function save(name: string, body: Blob | ArrayBuffer | string) {
  const res = await fetch(`/__bake/${name}`, { method: "POST", body });
  if (!res.ok) throw new Error(`could not write ${name}: ${res.status} ${await res.text()}`);
  log(await res.text());
}

const r1 = (v: number) => Math.round(v * 10) / 10;

// ------------------------------------------------------------------------------------------------
// Web Mercator tiles

const tileX = (lon: number, z: number) => ((lon + 180) / 360) * 2 ** z;
const tileY = (lat: number, z: number) => {
  const r = (lat * Math.PI) / 180;
  return ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * 2 ** z;
};

interface Mosaic {
  canvas: OffscreenCanvas;
  z: number;
  tx0: number;
  ty0: number;
}

async function mosaic(box: LonLatBox, z: number, url: (z: number, x: number, y: number) => string, label: string): Promise<Mosaic> {
  const tx0 = Math.floor(tileX(box.w, z));
  const tx1 = Math.floor(tileX(box.e, z));
  const ty0 = Math.floor(tileY(box.n, z));
  const ty1 = Math.floor(tileY(box.s, z));
  const canvas = new OffscreenCanvas((tx1 - tx0 + 1) * 256, (ty1 - ty0 + 1) * 256);
  const ctx = canvas.getContext("2d")!;
  const tiles: [number, number][] = [];
  for (let x = tx0; x <= tx1; x++) for (let y = ty0; y <= ty1; y++) tiles.push([x, y]);
  let done = 0;
  await pool(tiles, 8, async ([x, y]) => {
    const bitmap = await retry(`${label} tile ${z}/${x}/${y}`, async () => {
      const res = await fetch(url(z, x, y));
      if (!res.ok) throw new Error(String(res.status));
      return createImageBitmap(await res.blob(), { colorSpaceConversion: "none", premultiplyAlpha: "none" });
    });
    ctx.drawImage(bitmap, (x - tx0) * 256, (y - ty0) * 256);
    bitmap.close();
    if (++done % 20 === 0 || done === tiles.length) log(`${label}: ${done}/${tiles.length} tiles`);
  });
  return { canvas, z, tx0, ty0 };
}

/** Resample a Mercator mosaic into an image that is linear in lon/lat over `box` (top row = north). */
function toLonLatImage(m: Mosaic, box: LonLatBox, w: number, h: number): OffscreenCanvas {
  const out = new OffscreenCanvas(w, h);
  const ctx = out.getContext("2d")!;
  ctx.imageSmoothingQuality = "high";
  const sx0 = (tileX(box.w, m.z) - m.tx0) * 256;
  const sx1 = (tileX(box.e, m.z) - m.tx0) * 256;
  for (let j = 0; j < h; j++) {
    const lat = box.n - ((j + 0.5) / h) * (box.n - box.s);
    const sy = (tileY(lat, m.z) - m.ty0) * 256;
    ctx.drawImage(m.canvas, sx0, sy - 0.5, sx1 - sx0, 1, 0, j, w, 1);
  }
  return out;
}

// ------------------------------------------------------------------------------------------------
// Terrain

function terrainSampler(m: Mosaic) {
  const { width, height } = m.canvas;
  const data = m.canvas.getContext("2d")!.getImageData(0, 0, width, height).data;
  const at = (px: number, py: number) => {
    const x = Math.min(width - 1, Math.max(0, px));
    const y = Math.min(height - 1, Math.max(0, py));
    const i = (y * width + x) * 4;
    return data[i] * 256 + data[i + 1] + data[i + 2] / 256 - 32768;
  };
  return (lon: number, lat: number) => {
    const fx = (tileX(lon, m.z) - m.tx0) * 256 - 0.5;
    const fy = (tileY(lat, m.z) - m.ty0) * 256 - 0.5;
    const x = Math.floor(fx);
    const y = Math.floor(fy);
    const tx = fx - x;
    const ty = fy - y;
    return (
      at(x, y) * (1 - tx) * (1 - ty) + at(x + 1, y) * tx * (1 - ty) + at(x, y + 1) * (1 - tx) * ty + at(x + 1, y + 1) * tx * ty
    );
  };
}

function gridFrom(sample: (lon: number, lat: number) => number, box: LonLatBox, nx: number, ny: number, h0: number) {
  const g = new Float32Array(nx * ny);
  for (let j = 0; j < ny; j++) {
    const lat = box.n - (j / (ny - 1)) * (box.n - box.s);
    for (let i = 0; i < nx; i++) g[j * nx + i] = sample(box.w + (i / (nx - 1)) * (box.e - box.w), lat) - h0;
  }
  return g;
}

function blur(g: Float32Array, nx: number, ny: number): Float32Array {
  const out = new Float32Array(g.length);
  for (let j = 0; j < ny; j++)
    for (let i = 0; i < nx; i++) {
      let s = 0;
      let c = 0;
      for (let dj = -1; dj <= 1; dj++)
        for (let di = -1; di <= 1; di++) {
          const x = i + di;
          const y = j + dj;
          if (x < 0 || y < 0 || x >= nx || y >= ny) continue;
          const w = di === 0 && dj === 0 ? 4 : di === 0 || dj === 0 ? 2 : 1;
          s += g[y * nx + x] * w;
          c += w;
        }
      out[j * nx + i] = s / c;
    }
  return out;
}

// ------------------------------------------------------------------------------------------------
// OpenStreetMap

interface OsmNode { type: "node"; id: number; lat: number; lon: number; tags?: Record<string, string> }
interface OsmWay { type: "way"; id: number; nodes: number[]; tags?: Record<string, string> }
interface OsmRel { type: "relation"; id: number; members: { type: string; ref: number; role: string }[]; tags?: Record<string, string> }
type OsmElement = OsmNode | OsmWay | OsmRel;

function overpassQuery(b: LonLatBox) {
  const bb = `(${b.s},${b.w},${b.n},${b.e})`;
  return `[out:json][timeout:180];
(
  way["building"]${bb}; relation["building"]${bb};
  way["highway"]${bb};
  way["waterway"]${bb}; way["natural"="water"]${bb}; relation["natural"="water"]${bb}; way["water"]${bb};
  way["landuse"="reservoir"]${bb}; way["landuse"="basin"]${bb};
  way["amenity"="parking"]${bb}; relation["amenity"="parking"]${bb};
  way["barrier"="city_wall"]${bb}; way["historic"]${bb}; relation["historic"]${bb}; node["historic"]${bb};
  way["heritage"]${bb}; relation["heritage"]${bb}; way["boundary"="protected_area"]${bb};
  node["tourism"]${bb}; way["tourism"]${bb}; relation["tourism"]${bb};
  node["man_made"="tower"]${bb}; node["aeroway"="helipad"]${bb}; way["aeroway"="helipad"]${bb};
  node["place"]${bb};
  node["name"]["amenity"]${bb}; way["name"]["amenity"]${bb};
  node["name"]["leisure"]${bb}; way["name"]["leisure"]${bb};
  node["name"]["shop"]${bb};
);
out body; >; out skel qt;`;
}

async function fetchOsmApi(box: LonLatBox): Promise<OsmElement[]> {
  // The main OSM API returns every element in a small box (max 50 000 nodes), so ask in tiles and split a
  // tile in four when the API says it is too big.
  const seen = new Map<string, OsmElement>();
  const queue: LonLatBox[] = [];
  const step = 0.01;
  for (let s = box.s; s < box.n - 1e-9; s += step)
    for (let w = box.w; w < box.e - 1e-9; w += step) queue.push({ s, w, n: Math.min(box.n, s + step), e: Math.min(box.e, w + step) });
  let done = 0;
  const one = async (c: LonLatBox): Promise<void> => {
    const res = await retry("OSM API tile", async () => {
      const r = await fetch(`https://api.openstreetmap.org/api/0.6/map.json?bbox=${c.w},${c.s},${c.e},${c.n}`);
      if (!r.ok && r.status !== 400) throw new Error(String(r.status));
      return r;
    });
    if (res.status === 400) {
      const ms = (c.s + c.n) / 2;
      const mw = (c.w + c.e) / 2;
      log("OSM API: tile too dense, splitting in four");
      await Promise.all([
        one({ s: c.s, w: c.w, n: ms, e: mw }), one({ s: c.s, w: mw, n: ms, e: c.e }),
        one({ s: ms, w: c.w, n: c.n, e: mw }), one({ s: ms, w: mw, n: c.n, e: c.e }),
      ]);
      return;
    }
    const json = (await res.json()) as { elements: OsmElement[] };
    for (const e of json.elements) {
      const key = `${e.type}${e.id}`;
      const prev = seen.get(key);
      if (!prev || (e.type === "relation" && (e as OsmRel).members.length > (prev as OsmRel).members.length)) seen.set(key, e);
    }
  };
  await pool(queue, 3, async (c) => {
    await one(c);
    log(`OSM API: ${++done}/${queue.length} tiles, ${seen.size.toLocaleString()} elements`);
  });
  return [...seen.values()];
}

async function fetchOsm(box: LonLatBox): Promise<OsmElement[]> {
  try {
    return await fetchOsmApi(box);
  } catch (err) {
    log(`OSM API failed (${String(err)}), trying Overpass`);
  }
  const body = "data=" + encodeURIComponent(overpassQuery(box));
  for (const url of OVERPASS) {
    try {
      log(`OSM: asking ${new URL(url).host} ...`);
      const res = await fetch(url, {
        method: "POST",
        body,
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        signal: AbortSignal.timeout(180_000),
      });
      if (!res.ok) throw new Error(String(res.status));
      const json = (await res.json()) as { elements: OsmElement[] };
      log(`OSM: ${json.elements.length} elements from ${new URL(url).host}`);
      return json.elements;
    } catch (err) {
      log(`OSM: ${new URL(url).host} failed (${String(err)})`);
    }
  }
  throw new Error("no OpenStreetMap source answered");
}

// ------------------------------------------------------------------------------------------------
// Microsoft building footprints (fills the many buildings OSM has no outline for)

function quadkey(lon: number, lat: number, z: number) {
  const x = Math.floor(tileX(lon, z));
  const y = Math.floor(tileY(lat, z));
  let q = "";
  for (let i = z; i > 0; i--) {
    const mask = 1 << (i - 1);
    q += String((x & mask ? 1 : 0) + (y & mask ? 2 : 0));
  }
  return q;
}

async function fetchMsFootprints(box: LonLatBox): Promise<number[][][][]> {
  const keys = new Set([
    quadkey(box.w, box.s, 9), quadkey(box.e, box.s, 9), quadkey(box.w, box.n, 9), quadkey(box.e, box.n, 9),
  ]);
  const csv = await retry("footprint index", async () => {
    const res = await fetch(MSB_LINKS);
    if (!res.ok) throw new Error(String(res.status));
    return res.text();
  });
  const urls = csv
    .split("\n")
    .map((l) => l.split(","))
    .filter((c) => c.length >= 3 && keys.has(c[1]))
    .map((c) => c[2].trim());
  log(`Footprints: ${urls.length} file(s) for quadkeys ${[...keys].join(", ")}`);
  const out: number[][][][] = [];
  for (const url of urls) {
    const u = new URL(url);
    if (u.host !== "minedbuildings.z5.web.core.windows.net") {
      log(`Footprints: skipping ${u.host} (not proxied)`);
      continue;
    }
    const res = await fetch(`/__proxy/msb${u.pathname}`);
    if (!res.ok || !res.body) {
      log(`Footprints: ${u.pathname} failed (${res.status})`);
      continue;
    }
    const lines = res.body.pipeThrough(new DecompressionStream("gzip")).pipeThrough(new TextDecoderStream());
    const reader = lines.getReader();
    let rest = "";
    let total = 0;
    const take = (line: string) => {
      if (!line) return;
      total++;
      // Cheap reject before parsing: first coordinate pair.
      const m = line.match(/\[\[\[\s*([-0-9.]+)\s*,\s*([-0-9.]+)/);
      if (!m) return;
      const lon = Number(m[1]);
      const lat = Number(m[2]);
      if (lon < box.w || lon > box.e || lat < box.s || lat > box.n) return;
      const f = JSON.parse(line) as { geometry: { type: string; coordinates: number[][][] } };
      if (f.geometry.type === "Polygon") out.push(f.geometry.coordinates);
    };
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      const parts = (rest + value).split("\n");
      rest = parts.pop() ?? "";
      for (const p of parts) take(p);
      if (total % 200000 < parts.length) log(`Footprints: read ${total.toLocaleString()} lines, kept ${out.length.toLocaleString()}`);
    }
    take(rest);
    log(`Footprints: ${total.toLocaleString()} read, ${out.length.toLocaleString()} inside the core area`);
  }
  return out;
}

// ------------------------------------------------------------------------------------------------
// Turning OSM into the pack

const ROAD_W: Record<string, number> = {
  motorway: 24, trunk: 20, primary: 15, secondary: 12, tertiary: 9, unclassified: 7, residential: 7, living_street: 6,
  service: 4.5, road: 6, motorway_link: 7, trunk_link: 7, primary_link: 7, secondary_link: 6, tertiary_link: 6,
};
const PATH_W: Record<string, number> = { pedestrian: 5, footway: 2.5, path: 2, steps: 2.5, cycleway: 2.5, track: 3, bridleway: 2 };
const WATERWAY_W: Record<string, number> = { river: 14, stream: 4, canal: 6, drain: 2, ditch: 2, wadi: 10 };

function nameOf(tags: Record<string, string>) {
  const ar = tags["name:ar"] ?? (/[؀-ۿ]/.test(tags.name ?? "") ? tags.name : undefined);
  const n = tags["name:en"] ?? (tags.name && !/[؀-ۿ]/.test(tags.name) ? tags.name : undefined) ?? ar;
  return { n, ar };
}

function parseMeters(v: string | undefined): number | null {
  if (!v) return null;
  const m = v.replace(",", ".").match(/[0-9.]+/);
  if (!m) return null;
  const n = Number(m[0]);
  return Number.isFinite(n) && n > 0 && n < 300 ? n : null;
}

class OsmIndex {
  nodes = new Map<number, OsmNode>();
  ways = new Map<number, OsmWay>();
  rels: OsmRel[] = [];
  constructor(elements: OsmElement[], private proj: Projection) {
    for (const e of elements) {
      if (e.type === "node") {
        const prev = this.nodes.get(e.id);
        if (!prev || !prev.tags) this.nodes.set(e.id, e); // Overpass sends tagged nodes twice; keep the tags
      }
      else if (e.type === "way") this.ways.set(e.id, e);
      else this.rels.push(e);
    }
  }
  /** Way as flat local coordinates, or null when nodes are missing. */
  line(way: OsmWay): number[] | null {
    const out: number[] = [];
    for (const id of way.nodes) {
      const n = this.nodes.get(id);
      if (!n) return null;
      const [x, y] = this.proj.toLocal(n.lon, n.lat);
      out.push(x, y);
    }
    return out;
  }
  /** Closed way as a ring (closing point removed). */
  ring(way: OsmWay): number[] | null {
    if (way.nodes.length < 4 || way.nodes[0] !== way.nodes[way.nodes.length - 1]) return null;
    const l = this.line(way);
    return l ? l.slice(0, -2) : null;
  }
  /** Multipolygon relation: join member ways into closed outer and inner rings. */
  multipolygon(rel: OsmRel): { outers: number[][]; inners: number[][] } {
    const join = (role: string) => {
      const parts = rel.members
        .filter((m) => m.type === "way" && (m.role || "outer") === role)
        .map((m) => this.ways.get(m.ref)?.nodes.slice())
        .filter((n): n is number[] => !!n && n.length > 1);
      const rings: number[][] = [];
      while (parts.length) {
        let cur = parts.shift()!;
        let grew = true;
        while (cur[0] !== cur[cur.length - 1] && grew) {
          grew = false;
          for (let i = 0; i < parts.length; i++) {
            const p = parts[i];
            const end = cur[cur.length - 1];
            if (p[0] === end) cur = cur.concat(p.slice(1));
            else if (p[p.length - 1] === end) cur = cur.concat(p.slice(0, -1).reverse());
            else if (p[p.length - 1] === cur[0]) cur = p.concat(cur.slice(1));
            else if (p[0] === cur[0]) cur = p.slice().reverse().concat(cur.slice(1));
            else continue;
            parts.splice(i, 1);
            grew = true;
            break;
          }
        }
        if (cur[0] === cur[cur.length - 1] && cur.length >= 4) {
          const r = this.line({ type: "way", id: 0, nodes: cur });
          if (r) rings.push(r.slice(0, -2));
        }
      }
      return rings;
    };
    return { outers: join("outer"), inners: join("inner") };
  }
}

function ccw(r: number[]) {
  if (signedArea(r) >= 0) return r;
  const out: number[] = [];
  for (let i = r.length - 2; i >= 0; i -= 2) out.push(r[i], r[i + 1]);
  return out;
}
function cw(r: number[]) {
  const c = ccw(r);
  const out: number[] = [];
  for (let i = c.length - 2; i >= 0; i -= 2) out.push(c[i], c[i + 1]);
  return out;
}
const round = (p: number[]) => p.map(r1);

function estimateHeight(kind: SiteBuilding["k"], tag: string, area: number, seed: number): number {
  const r = hash01(seed);
  if (kind === "ruin") return 2.2 + r * 2.2;
  if (kind === "najdi") {
    if (area < 60) return 4.5 + r;
    if (area < 250) return 6 + r * 1.5;
    if (area < 900) return 7.5 + r * 2;
    return 9 + r * 3;
  }
  switch (tag) {
    case "house": case "villa": case "detached": case "residential": case "semidetached_house": case "terrace":
      return area < 60 ? 3.5 + r : 7.5 + r * 2.5;
    case "apartments": return 12 + r * 6;
    case "commercial": case "retail": case "office": case "hotel": return 9 + r * 7;
    case "garage": case "garages": case "shed": case "roof": case "hut": case "carport": case "kiosk": return 3 + r;
    case "mosque": return 8 + r * 3;
    case "school": case "hospital": case "university": case "college": case "public": case "government": return 10 + r * 4;
    case "industrial": case "warehouse": return 8 + r * 3;
  }
  if (area < 40) return 3.2 + r;
  if (area < 150) return 4 + r * 3.5;
  if (area < 600) return 7.5 + r * 3;
  if (area < 2500) return 9 + r * 5;
  return 11 + r * 6;
}

// ------------------------------------------------------------------------------------------------

export async function bake() {
  const t0 = performance.now();
  const proj = makeProjection(ORIGIN.lat, ORIGIN.lon);
  const toBox = (b: LonLatBox): SiteBox => {
    const [x0, y0] = proj.toLocal(b.w, b.s);
    const [x1, y1] = proj.toLocal(b.e, b.n);
    return { ...b, x0: r1(x0), y0: r1(y0), x1: r1(x1), y1: r1(y1) };
  };
  const boxes = { detail: toBox(DETAIL), core: toBox(CORE), wide: toBox(WIDE) };

  // ---- Terrain
  log("Terrain: core tiles");
  const coreDem = terrainSampler(await mosaic(CORE, TERRAIN.coreZoom, TERRARIUM, "terrain core"));
  log("Terrain: wide tiles");
  const wideDem = terrainSampler(await mosaic(WIDE, TERRAIN.wideZoom, TERRARIUM, "terrain wide"));
  const h0 = coreDem(ORIGIN.lon, ORIGIN.lat);
  log(`Terrain: ground at the origin ${h0.toFixed(1)} m above sea level`);
  const cnx = Math.round((boxes.core.x1 - boxes.core.x0) / TERRAIN.coreStep) + 1;
  const cny = Math.round((boxes.core.y1 - boxes.core.y0) / TERRAIN.coreStep) + 1;
  const coreGrid = blur(gridFrom(coreDem, CORE, cnx, cny, h0), cnx, cny);
  const wnx = TERRAIN.wideCells + 1;
  const wny = Math.round((TERRAIN.wideCells * (boxes.wide.y1 - boxes.wide.y0)) / (boxes.wide.x1 - boxes.wide.x0)) + 1;
  const wideGrid = gridFrom(wideDem, WIDE, wnx, wny, h0);
  const minmax = (g: Float32Array) => g.reduce((a, v) => [Math.min(a[0], v), Math.max(a[1], v)], [Infinity, -Infinity]);
  const [cmin, cmax] = minmax(coreGrid);
  const [wmin, wmax] = minmax(wideGrid);
  await save("terrain_core.bin", coreGrid.buffer as ArrayBuffer);
  await save("terrain_wide.bin", wideGrid.buffer as ArrayBuffer);

  // ---- Imagery
  const imagery = {} as SitePack["imagery"];
  const pixels: Partial<Record<"detail" | "core", { data: Uint8ClampedArray; w: number; h: number; box: LonLatBox }>> = {};
  for (const [key, box] of [["wide", WIDE], ["core", CORE], ["detail", DETAIL]] as const) {
    const z = ZOOM[key];
    log(`Imagery: ${key} (zoom ${z})`);
    const m = await mosaic(box, z, ESRI, `imagery ${key}`);
    const nativeW = (tileX(box.e, z) - tileX(box.w, z)) * 256;
    const nativeH = (tileY(box.s, z) - tileY(box.n, z)) * 256;
    const scale = Math.min(1, 4096 / Math.max(nativeW, nativeH));
    const w = Math.round((nativeW * scale) / 4) * 4;
    const h = Math.round((nativeH * scale) / 4) * 4;
    const img = toLonLatImage(m, box, w, h);
    const blob = await img.convertToBlob({ type: "image/jpeg", quality: key === "wide" ? 0.82 : 0.86 });
    await save(`sat_${key}.jpg`, blob);
    imagery[key] = { file: `sat_${key}.jpg`, w, h, box: key, zoom: z };
    if (key !== "wide") pixels[key] = { data: img.getContext("2d")!.getImageData(0, 0, w, h).data, w, h, box };
  }

  // ---- OpenStreetMap
  const osm = new OsmIndex(await fetchOsm(CORE), proj);
  const buildings: SiteBuilding[] = [];
  const roads: SiteLine[] = [];
  const paths: SiteLine[] = [];
  const waterways: SiteLine[] = [];
  const water: SitePolygon[] = [];
  const walls: SiteLine[] = [];
  const towers: SitePack["towers"] = [];
  const parking: SitePolygon[] = [];
  const helipads: SitePack["helipads"] = [];
  const areas: SitePolygon[] = [];
  const pois: SitePoi[] = [];
  const coreBox = boxes.core;
  const insideCore = (x: number, y: number) => x > coreBox.x0 && x < coreBox.x1 && y > coreBox.y0 && y < coreBox.y1;

  // Polygons of every tagged way / relation.
  const polygons: { id: string; tags: Record<string, string>; outers: number[][]; inners: number[][] }[] = [];
  for (const w of osm.ways.values()) {
    if (!w.tags) continue;
    const ring = osm.ring(w);
    if (ring && ring.length >= 6) polygons.push({ id: `w${w.id}`, tags: w.tags, outers: [ring], inners: [] });
  }
  for (const r of osm.rels) {
    if (!r.tags || (r.tags.type !== "multipolygon" && r.tags.type !== "boundary")) continue;
    const mp = osm.multipolygon(r);
    if (mp.outers.length) polygons.push({ id: `r${r.id}`, tags: r.tags, ...mp });
  }

  // Heritage districts first: they decide the building style.
  for (const p of polygons) {
    const t = p.tags;
    if (t.building) continue;
    const { n, ar } = nameOf(t);
    const named = `${t.name ?? ""} ${t["name:en"] ?? ""} ${t["name:ar"] ?? ""}`;
    const heritage =
      ((t.historic || t.heritage || t.boundary === "protected_area" || t.tourism === "attraction") && HERITAGE_NAME.test(named)) ||
      t.historic === "district" || t.historic === "archaeological_site" || t.historic === "city";
    if (!heritage && !(t.tourism === "attraction" && n)) continue;
    for (const o of p.outers) {
      if (Math.abs(signedArea(o)) < 3000) continue;
      areas.push({ k: heritage ? "heritage" : "attraction", n, ar, p: round(ccw(simplifyRing(o, 1) ?? o)) });
    }
  }
  for (const a of areas) if (a.n) a.n = a.n.replace(/ District in ad-Dir.iyah$/i, "").replace(/^Al-Mulaybeed$/, "Al-Mulaybeed"); // "At-Turaif District in ad-Dir'iyah" -> "At-Turaif"
  const heritageAreas = areas.filter((a) => a.k === "heritage");
  // Najdi style: inside the heritage districts, and around Bujairi across the wadi (rebuilt in mud style).
  const najdiDiscs = NAJDI_DISCS.map(({ lon, lat, r }) => [...proj.toLocal(lon, lat), r] as const);
  const inHeritage = (x: number, y: number) =>
    heritageAreas.some((a) => pointInRing(x, y, a.p)) || najdiDiscs.some(([dx, dy, r]) => Math.hypot(x - dx, y - dy) < r);
  log(`Areas: ${heritageAreas.length} heritage districts (${heritageAreas.map((a) => a.n).join(", ")}), ${areas.length - heritageAreas.length} attractions`);

  // Buildings
  const osmBuildingGrid = new Grid2D<number[]>(60);
  for (const p of polygons) {
    const t = p.tags;
    if (!t.building || t.building === "no" || (t.building === "construction" && !t.height)) continue;
    if (t.location === "underground" || t.layer?.startsWith("-")) continue;
    for (const o of p.outers) {
      const outer = simplifyRing(o, 0.25);
      if (!outer) continue;
      const area = Math.abs(signedArea(outer));
      if (area < 8) continue;
      const [cx, cy] = centroid(outer);
      if (!insideCore(cx, cy)) continue;
      const ruin = t.building === "ruins" || t.ruins === "yes" || t.historic === "ruins";
      const najdi = ruin || inHeritage(cx, cy) || t.historic === "building" || t.building === "castle" || t.building === "fort";
      const kind: SiteBuilding["k"] = ruin ? "ruin" : najdi ? "najdi" : "modern";
      const seed = Number(p.id.slice(1)) % 2147483647;
      let h = parseMeters(t.height);
      let hs: SiteBuilding["hs"] = "osm";
      if (h === null && t["building:levels"]) {
        const lv = parseMeters(t["building:levels"]);
        if (lv !== null) {
          h = lv * 3.4 + 1.2;
          hs = "levels";
        }
      }
      if (h === null) {
        h = estimateHeight(kind, t.building, area, seed);
        hs = "estimated";
      }
      const { n, ar } = nameOf(t);
      const holes = p.inners.filter((i) => pointInRing(...centroid(i), outer)).map((i) => round(cw(i)));
      const b: SiteBuilding = { id: p.id, h: r1(h), hs, k: kind, o: round(ccw(outer)) };
      if (n) b.n = n;
      if (ar) b.ar = ar;
      if (holes.length) b.holes = holes;
      buildings.push(b);
      const bb = ringBounds(outer);
      osmBuildingGrid.insert(b.o, bb[0], bb[1], bb[2], bb[3]);
    }
  }
  const osmCount = buildings.length;
  log(`Buildings: ${osmCount} from OpenStreetMap`);

  // Microsoft footprints where OSM has nothing.
  try {
    const fps = await fetchMsFootprints(CORE);
    let added = 0;
    for (let i = 0; i < fps.length; i++) {
      if (i % 5000 === 0) await new Promise((r) => setTimeout(r, 0));
      const ring: number[] = [];
      for (const [lon, lat] of fps[i][0]) ring.push(...proj.toLocal(lon, lat));
      const outer = simplifyRing(ring.slice(0, -2), 0.4);
      if (!outer) continue;
      const area = Math.abs(signedArea(outer));
      if (area < 12) continue;
      const [cx, cy] = centroid(outer);
      if (!insideCore(cx, cy)) continue;
      const near = osmBuildingGrid.query(cx, cy);
      if (near.some((o) => pointInRing(cx, cy, o) || pointInRing(...centroid(o), outer))) continue;
      const kind: SiteBuilding["k"] = inHeritage(cx, cy) ? "najdi" : "modern";
      const seed = 900000 + i;
      buildings.push({ id: `m${i}`, h: r1(estimateHeight(kind, "yes", area, seed)), hs: "estimated", k: kind, o: round(ccw(outer)) });
      added++;
    }
    log(`Buildings: ${added} added from Microsoft footprints`);
  } catch (err) {
    log(`Footprints skipped: ${String(err)}`);
  }

  // (The illustrative ruins that fill the unmapped parts of At-Turaif are generated by the app: twin3d/infill.ts.)

  // Lines and other features
  for (const w of osm.ways.values()) {
    const t = w.tags;
    if (!t) continue;
    const raw = t.highway || t.waterway || t.barrier === "city_wall" || t.historic === "city_walls" ? osm.line(w) : null;
    if (!raw || raw.length < 4) continue;
    const line = round(simplifyLine(raw, 0.5));
    const { n } = nameOf(t);
    if (t.highway && t.area !== "yes" && t.tunnel !== "yes") {
      const bridge = t.bridge && t.bridge !== "no" ? { b: 1 as const } : {};
      if (ROAD_W[t.highway]) roads.push({ k: t.highway, w: ROAD_W[t.highway], p: line, ...(n ? { n } : {}), ...bridge });
      else if (PATH_W[t.highway]) paths.push({ k: t.highway, w: PATH_W[t.highway], p: line, ...bridge });
    } else if (t.waterway && WATERWAY_W[t.waterway] && t.tunnel !== "culvert") {
      waterways.push({ k: t.waterway, w: WATERWAY_W[t.waterway], p: line, ...(n ? { n } : {}) });
    } else if (t.barrier === "city_wall" || t.historic === "city_walls") {
      walls.push({ k: "city_wall", w: 1.6, p: line, ...(n ? { n } : {}) });
    }
  }
  for (const p of polygons) {
    const t = p.tags;
    if (t.building) continue;
    if (t.natural === "water" || t.water || t.landuse === "reservoir" || t.landuse === "basin") {
      for (const o of p.outers) water.push({ k: t.water ?? "water", p: round(ccw(simplifyRing(o, 0.5) ?? o)), holes: p.inners.map((i) => round(cw(i))) });
    } else if (t.amenity === "parking" && t.parking !== "underground" && t.parking !== "multi-storey") {
      for (const o of p.outers) if (Math.abs(signedArea(o)) > 150) parking.push({ p: round(ccw(simplifyRing(o, 0.5) ?? o)) });
    } else if (t.aeroway === "helipad") {
      const [x, y] = centroid(p.outers[0]);
      helipads.push({ x: r1(x), y: r1(y) });
    }
  }
  for (const node of osm.nodes.values()) {
    const t = node.tags;
    if (!t) continue;
    const [x, y] = proj.toLocal(node.lon, node.lat);
    if (!insideCore(x, y)) continue;
    if (t.man_made === "tower" || t.historic === "tower") towers.push({ x: r1(x), y: r1(y), h: parseMeters(t.height) ?? 9, r: 2.6 });
    if (t.aeroway === "helipad") helipads.push({ x: r1(x), y: r1(y) });
    const { n, ar } = nameOf(t);
    if (!n) continue;
    if (t.place && ["suburb", "neighbourhood", "quarter", "village", "town", "hamlet", "locality"].includes(t.place)) {
      pois.push({ n, ...(ar ? { ar } : {}), k: t.place, x: r1(x), y: r1(y), big: true });
    } else if (t.tourism || t.historic || t.amenity || t.leisure || t.shop) {
      const k = t.tourism || (t.historic ? "historic" : "") || t.amenity || t.leisure || t.shop || "place";
      pois.push({ n, ...(ar ? { ar } : {}), k, x: r1(x), y: r1(y) });
    }
  }
  // Named polygons (museums, galleries, palaces ...) become points of interest at their middle.
  for (const p of polygons) {
    const t = p.tags;
    const { n, ar } = nameOf(t);
    if (!n || !(t.tourism || t.historic || t.amenity || t.leisure)) continue;
    const [x, y] = centroid(p.outers[0]);
    if (!insideCore(x, y)) continue;
    const k = t.tourism || (t.historic ? "historic" : "") || t.amenity || t.leisure || "place";
    const big = areas.some((a) => a.n === n);
    pois.push({ n, ...(ar ? { ar } : {}), k, x: r1(x), y: r1(y), ...(big ? { big: true } : {}) });
  }
  // One label per name (the first wins).
  const seenNames = new Set<string>();
  const uniquePois = pois.filter((p) => (seenNames.has(p.n) ? false : (seenNames.add(p.n), true)));
  const hanifa = waterways.find((w) => /حنيفة|hanifa/i.test(w.n ?? ""));
  if (hanifa) {
    // Label the wadi at the point of its course nearest At-Turaif.
    let best = 0;
    for (let i = 0; i < hanifa.p.length; i += 2) if (Math.hypot(hanifa.p[i], hanifa.p[i + 1]) < Math.hypot(hanifa.p[best], hanifa.p[best + 1])) best = i;
    uniquePois.push({ n: "Wadi Hanifah", ar: "وادي حنيفة", k: "wadi", x: hanifa.p[best], y: hanifa.p[best + 1], big: true });
  }
  {
    const [x, y] = proj.toLocal(BUJAIRI.lon, BUJAIRI.lat);
    uniquePois.unshift({ n: "Bujairi Terrace", ar: "مطل البجيري", k: "attraction", x: r1(x), y: r1(y), big: true });
  }
  log(`Lines: ${roads.length} roads, ${paths.length} paths, ${waterways.length} waterways, ${walls.length} city walls; ${water.length} water areas, ${parking.length} car parks, ${uniquePois.length} places`);

  // ---- Date palms from the imagery: dark green canopy on a jittered 8 m grid.
  log("Palms: scanning the imagery for canopy");
  const bGrid = new Grid2D<SiteBuilding>(50);
  for (const b of buildings) {
    const bb = ringBounds(b.o);
    bGrid.insert(b, bb[0], bb[1], bb[2], bb[3]);
  }
  const green = (lon: number, lat: number): number => {
    const src = lon > DETAIL.w && lon < DETAIL.e && lat > DETAIL.s && lat < DETAIL.n ? pixels.detail! : pixels.core!;
    const px = Math.floor(((lon - src.box.w) / (src.box.e - src.box.w)) * src.w);
    const py = Math.floor(((src.box.n - lat) / (src.box.n - src.box.s)) * src.h);
    let r = 0;
    let g = 0;
    let b = 0;
    let c = 0;
    for (let dy = -1; dy <= 1; dy++)
      for (let dx = -1; dx <= 1; dx++) {
        const x = px + dx;
        const y = py + dy;
        if (x < 0 || y < 0 || x >= src.w || y >= src.h) continue;
        const i = (y * src.w + x) * 4;
        r += src.data[i];
        g += src.data[i + 1];
        b += src.data[i + 2];
        c++;
      }
    if (!c) return 0;
    r /= c;
    g /= c;
    b /= c;
    // Palm canopy is dark and greenish; sand has a high excess-green too, so brightness and hue gate it.
    const lum = 0.3 * r + 0.59 * g + 0.11 * b;
    if (lum > 125 || lum < 18 || g - r < -6 || g - b < 6) return 0;
    return 2 * g - r - b; // excess green
  };
  const palms: number[] = [];
  const step = 9;
  let idx = 0;
  for (let y = coreBox.y0 + step / 2; y < coreBox.y1; y += step) {
    if (Math.round(y / step) % 40 === 0) await new Promise((r) => setTimeout(r, 0)); // keep the page responsive
    for (let x = coreBox.x0 + step / 2; x < coreBox.x1; x += step) {
      idx++;
      const px = x + (hash01(idx * 3) - 0.5) * step * 0.8;
      const py = y + (hash01(idx * 3 + 1) - 0.5) * step * 0.8;
      const [lon, lat] = proj.toLonLat(px, py);
      const g = green(lon, lat);
      if (g < 8) continue;
      if (bGrid.query(px, py).some((b) => pointInRing(px, py, b.o))) continue;
      palms.push(r1(px), r1(py), Math.round((0.75 + hash01(idx * 3 + 2) * 0.5 + Math.min(0.25, (g - 8) / 120)) * 100) / 100);
    }
  }
  await save("palms.bin", new Float32Array(palms).buffer as ArrayBuffer);
  log(`Palms: ${(palms.length / 3).toLocaleString()}`);

  const pack: SitePack = {
    version: 1,
    generated: new Date().toISOString(),
    origin: { lat: ORIGIN.lat, lon: ORIGIN.lon, h_msl: r1(h0) },
    boxes,
    terrain: {
      core: { file: "terrain_core.bin", nx: cnx, ny: cny, box: "core", min: r1(cmin), max: r1(cmax) },
      wide: { file: "terrain_wide.bin", nx: wnx, ny: wny, box: "wide", min: r1(wmin), max: r1(wmax) },
    },
    imagery,
    palms: { file: "palms.bin", count: palms.length / 3 },
    buildings,
    roads,
    paths,
    waterways,
    water,
    walls,
    towers,
    parking,
    helipads,
    areas,
    pois: uniquePois,
    sources: [
      "© OpenStreetMap contributors (ODbL)",
      "Microsoft Global ML Building Footprints (ODbL)",
      "Esri World Imagery (Esri, Maxar, Earthstar Geographics)",
      "Terrain Tiles on AWS (SRTM and others)",
    ],
  };
  await save("site.json", JSON.stringify(pack));
  log(`Done in ${Math.round((performance.now() - t0) / 1000)} s. Reload the app.`);
}
