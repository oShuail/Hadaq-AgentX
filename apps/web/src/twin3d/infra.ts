// Roads, paths, water, car parks, city walls and towers, all draped on the terrain (owner: Omar).
import * as THREE from "three";

import { hash01, pointInRing, ringBounds } from "../site/poly";
import type { SitePolygon } from "../site/types";
import type { Site } from "./siteData";

type Ground = (x: number, y: number) => number;

/** Points of a polyline every `step` metres or closer (keeps the original corners). */
function densify(p: number[], step: number): number[] {
  const out: number[] = [p[0], p[1]];
  for (let i = 2; i < p.length; i += 2) {
    const x0 = p[i - 2];
    const y0 = p[i - 1];
    const x1 = p[i];
    const y1 = p[i + 1];
    const n = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0) / step));
    for (let k = 1; k <= n; k++) out.push(x0 + ((x1 - x0) * k) / n, y0 + ((y1 - y0) * k) / n);
  }
  return out;
}

class Ribbon {
  pos: number[] = [];
  uv: number[] = [];
  idx: number[] = [];
  /** A flat strip `width` wide along the line, `lift` metres above the ground (or along a bridge deck). */
  add(line: number[], width: number, lift: number, ground: Ground, bridge = false) {
    const p = densify(line, 6);
    const n = p.length / 2;
    if (n < 2) return;
    const base = this.pos.length / 3;
    let along = 0;
    // A bridge deck runs straight between its ends instead of following the wadi floor.
    const h0 = ground(p[0], p[1]);
    const h1 = ground(p[p.length - 2], p[p.length - 1]);
    let total = 0;
    for (let i = 1; i < n; i++) total += Math.hypot(p[i * 2] - p[i * 2 - 2], p[i * 2 + 1] - p[i * 2 - 1]);
    for (let i = 0; i < n; i++) {
      const x = p[i * 2];
      const y = p[i * 2 + 1];
      const xa = p[Math.max(0, i - 1) * 2];
      const ya = p[Math.max(0, i - 1) * 2 + 1];
      const xb = p[Math.min(n - 1, i + 1) * 2];
      const yb = p[Math.min(n - 1, i + 1) * 2 + 1];
      let dx = xb - xa;
      let dy = yb - ya;
      const len = Math.hypot(dx, dy) || 1;
      dx /= len;
      dy /= len;
      if (i > 0) along += Math.hypot(x - p[i * 2 - 2], y - p[i * 2 - 1]);
      const nx = -dy * (width / 2);
      const ny = dx * (width / 2);
      const deck = bridge ? Math.max(h0 + (h1 - h0) * (along / (total || 1)), 0) : 0;
      for (const side of [1, -1]) {
        const sx = x + nx * side;
        const sy = y + ny * side;
        const h = bridge ? Math.max(deck, ground(sx, sy)) : ground(sx, sy);
        this.pos.push(sx, h + lift, -sy);
        this.uv.push(side > 0 ? 0 : 1, along / width);
      }
      if (i > 0) {
        const a = base + (i - 1) * 2;
        this.idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2);
      }
    }
  }
  mesh(material: THREE.Material, name: string): THREE.Mesh | null {
    if (!this.pos.length) return null;
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(this.pos, 3));
    g.setAttribute("uv", new THREE.Float32BufferAttribute(this.uv, 2));
    g.setIndex(this.idx);
    g.computeVertexNormals();
    // Strips can be wound either way: point every normal up so lighting is even.
    const nrm = g.getAttribute("normal");
    for (let i = 0; i < nrm.count; i++) if (nrm.getY(i) < 0) nrm.setXYZ(i, -nrm.getX(i), -nrm.getY(i), -nrm.getZ(i));
    const mesh = new THREE.Mesh(g, material);
    mesh.name = name;
    mesh.receiveShadow = true;
    return mesh;
  }
}

const overlay = (color: string, order: number, extra: Partial<THREE.MeshStandardMaterialParameters> = {}) =>
  new THREE.MeshStandardMaterial({
    color,
    roughness: 0.95,
    metalness: 0,
    side: THREE.DoubleSide,
    polygonOffset: true,
    polygonOffsetFactor: -2 - order,
    polygonOffsetUnits: -4 - order * 2,
    ...extra,
  });

/** Flat polygon draped on the terrain: grid points inside the polygon plus its outline, triangulated. */
function drapedPolygon(poly: SitePolygon, lift: number, ground: Ground, flat?: number): THREE.BufferGeometry | null {
  const contour: THREE.Vector2[] = [];
  const d = densify([...poly.p, poly.p[0], poly.p[1]], 8);
  for (let i = 0; i < d.length - 2; i += 2) contour.push(new THREE.Vector2(d[i], d[i + 1]));
  const holes = (poly.holes ?? []).map((h) => {
    const out: THREE.Vector2[] = [];
    for (let i = 0; i < h.length; i += 2) out.push(new THREE.Vector2(h[i], h[i + 1]));
    return out;
  });
  if (contour.length < 3) return null;
  const faces = THREE.ShapeUtils.triangulateShape(contour, holes);
  const all = [...contour, ...holes.flat()];
  const pos: number[] = [];
  for (const f of faces) {
    const tri = f.map((k) => all[k]);
    const cross = (tri[1].x - tri[0].x) * (tri[2].y - tri[0].y) - (tri[1].y - tri[0].y) * (tri[2].x - tri[0].x);
    const order = cross >= 0 ? [0, 1, 2] : [0, 2, 1];
    for (const o of order) {
      const v = tri[o];
      pos.push(v.x, (flat ?? ground(v.x, v.y)) + lift, -v.y);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  g.computeVertexNormals();
  return g;
}

function mergeInto(geoms: THREE.BufferGeometry[]): THREE.BufferGeometry | null {
  if (!geoms.length) return null;
  let n = 0;
  for (const g of geoms) n += g.getAttribute("position").count;
  const pos = new Float32Array(n * 3);
  const nrm = new Float32Array(n * 3);
  let o = 0;
  for (const g of geoms) {
    pos.set(g.getAttribute("position").array as Float32Array, o * 3);
    nrm.set(g.getAttribute("normal").array as Float32Array, o * 3);
    o += g.getAttribute("position").count;
  }
  const out = new THREE.BufferGeometry();
  out.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  out.setAttribute("normal", new THREE.BufferAttribute(nrm, 3));
  return out;
}

export interface InfraLayer {
  roads: THREE.Group;
  water: THREE.Group;
  parking: THREE.Group;
  walls: THREE.Group;
  helipads: THREE.Group;
  streetLights: Float32Array; // scene x, y, z
  pathLights: Float32Array;
  carMesh: THREE.InstancedMesh | null;
  setCarOccupancy: (fraction: number) => void;
  counts: { roads: number; paths: number; parkingBays: number; walls: number; towers: number; helipads: number; water: number };
  pathsForWalkers: number[][]; // footways inside heritage areas (local x, north)
}

const MAJOR = new Set(["motorway", "trunk", "primary", "secondary", "motorway_link", "trunk_link", "primary_link"]);

export function buildInfra(site: Site, inHeritage: (x: number, y: number) => boolean, blocked: (x: number, y: number) => boolean): InfraLayer {
  const { pack, ground } = site;

  // ---- Roads and paths
  const roads = new THREE.Group();
  roads.name = "roads";
  const asphalt = new Ribbon();
  const minor = new Ribbon();
  const dashes = new Ribbon();
  const paving = new Ribbon();
  const trail = new Ribbon();
  const streetLights: number[] = [];
  for (const r of pack.roads) {
    const bridge = r.b === 1;
    (MAJOR.has(r.k) ? asphalt : minor).add(r.p, r.w, bridge ? 0.6 : 0.25, ground, bridge);
    if (MAJOR.has(r.k) || r.k === "tertiary") {
      // Dashed centre line: short strips every 9 m.
      const d = densify(r.p, 3);
      for (let i = 0; i + 3 < d.length; i += 6) {
        if (Math.floor(i / 6) % 3 !== 0) continue;
        dashes.add([d[i], d[i + 1], d[i + 2], d[i + 3]], 0.18, bridge ? 0.7 : 0.33, ground, bridge);
      }
    }
    // Street lights every 40 m on through roads.
    if (r.k !== "service") {
      const d = densify(r.p, 40);
      for (let i = 0; i < d.length; i += 2) {
        const off = r.w / 2 + 0.5;
        const k = Math.min(i + 2, d.length - 2);
        const j = Math.max(0, k - 2);
        const dx = d[k] - d[j];
        const dy = d[k + 1] - d[j + 1];
        const len = Math.hypot(dx, dy) || 1;
        const side = (i / 2) % 2 ? 1 : -1;
        const x = d[i] - (dy / len) * off * side;
        const y = d[i + 1] + (dx / len) * off * side;
        streetLights.push(x, ground(x, y) + 7, -y);
      }
    }
  }
  const pathsForWalkers: number[][] = [];
  const pathLights: number[] = [];
  for (const p of pack.paths) {
    const heritage = inHeritage(p.p[0], p.p[1]) || inHeritage(p.p[p.p.length - 2], p.p[p.p.length - 1]);
    (p.k === "track" || p.k === "path" ? trail : paving).add(p.p, p.w, p.b ? 0.6 : 0.3, ground, p.b === 1);
    if (heritage && p.k !== "track") {
      pathsForWalkers.push(p.p);
      const d = densify(p.p, 16);
      for (let i = 0; i < d.length; i += 2) pathLights.push(d[i], ground(d[i], d[i + 1]) + 2.6, -d[i + 1]);
    }
  }
  const meshes = [
    asphalt.mesh(overlay("#2c2824", 1), "roads-major"),
    minor.mesh(overlay("#38322c", 1), "roads-minor"),
    paving.mesh(overlay("#b59a78", 0), "paths"),
    trail.mesh(overlay("#9c8466", 0, { transparent: true, opacity: 0.7 }), "tracks"),
    dashes.mesh(overlay("#d8cdb8", 3), "road-markings"),
  ];
  for (const m of meshes) if (m) roads.add(m);

  // ---- Water: waterways as strips, lakes as flat surfaces
  const water = new THREE.Group();
  water.name = "water";
  const waterMat = overlay("#3d5866", 4, { roughness: 0.25, metalness: 0.1, transparent: true, opacity: 0.92 });
  const streams = new Ribbon();
  for (const w of pack.waterways) streams.add(w.p, w.w, 0.35, ground);
  const sm = streams.mesh(waterMat, "waterways");
  if (sm) water.add(sm);
  const lakes = pack.water
    .map((w) => {
      const hs: number[] = [];
      for (let i = 0; i < w.p.length; i += 2) hs.push(ground(w.p[i], w.p[i + 1]));
      hs.sort((a, b) => a - b);
      return drapedPolygon(w, 0.4, ground, hs[Math.floor(hs.length * 0.3)]);
    })
    .filter((g): g is THREE.BufferGeometry => !!g);
  const lakeGeom = mergeInto(lakes);
  if (lakeGeom) water.add(Object.assign(new THREE.Mesh(lakeGeom, waterMat), { name: "lakes" }));

  // ---- Car parks with cars
  const parking = new THREE.Group();
  parking.name = "parking";
  const lots = pack.parking.map((p) => drapedPolygon(p, 0.28, ground)).filter((g): g is THREE.BufferGeometry => !!g);
  const lotGeom = mergeInto(lots);
  if (lotGeom) parking.add(Object.assign(new THREE.Mesh(lotGeom, overlay("#4a433b", 2)), { name: "car-parks", receiveShadow: true }));
  const bays: { x: number; y: number; rot: number; seed: number }[] = [];
  pack.parking.forEach((p, li) => {
    const [x0, y0, x1, y1] = ringBounds(p.p);
    // Orient the rows along the longest edge of the lot.
    let best = 0;
    let rot = 0;
    for (let i = 0; i < p.p.length; i += 2) {
      const j = (i + 2) % p.p.length;
      const l = Math.hypot(p.p[j] - p.p[i], p.p[j + 1] - p.p[i + 1]);
      if (l > best) {
        best = l;
        rot = Math.atan2(p.p[j + 1] - p.p[i + 1], p.p[j] - p.p[i]);
      }
    }
    const cos = Math.cos(rot);
    const sin = Math.sin(rot);
    const cx = (x0 + x1) / 2;
    const cy = (y0 + y1) / 2;
    const r = Math.hypot(x1 - x0, y1 - y0) / 2;
    for (let v = -r; v < r; v += 6.5)
      for (let u = -r; u < r; u += 2.7) {
        const x = cx + u * cos - v * sin;
        const y = cy + u * sin + v * cos;
        if (!pointInRing(x, y, p.p) || blocked(x, y)) continue;
        // Stay 2 m inside the edge.
        if (!pointInRing(x + 2, y, p.p) || !pointInRing(x - 2, y, p.p) || !pointInRing(x, y + 2, p.p) || !pointInRing(x, y - 2, p.p)) continue;
        bays.push({ x, y, rot, seed: li * 1000 + bays.length });
      }
  });
  let carMesh: THREE.InstancedMesh | null = null;
  if (bays.length) {
    const body = new THREE.BoxGeometry(4.4, 1.35, 1.85);
    body.translate(0, 0.75, 0);
    const cabin = new THREE.BoxGeometry(2.3, 0.6, 1.65);
    cabin.translate(-0.2, 1.7, 0);
    const car = new THREE.BufferGeometry();
    const merged = [body, cabin].map((g) => g.toNonIndexed());
    const pos = new Float32Array(merged.reduce((a, g) => a + g.getAttribute("position").array.length, 0));
    const nrm = new Float32Array(pos.length);
    let o = 0;
    for (const g of merged) {
      pos.set(g.getAttribute("position").array as Float32Array, o);
      nrm.set(g.getAttribute("normal").array as Float32Array, o);
      o += g.getAttribute("position").array.length;
    }
    car.setAttribute("position", new THREE.BufferAttribute(pos, 3));
    car.setAttribute("normal", new THREE.BufferAttribute(nrm, 3));
    carMesh = new THREE.InstancedMesh(car, new THREE.MeshStandardMaterial({ roughness: 0.4, metalness: 0.3 }), bays.length);
    carMesh.name = "cars";
    const m = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const c = new THREE.Color();
    const colors = ["#f2f2f0", "#f2f2f0", "#1d1d1f", "#b8b9bb", "#8a8d91", "#e8e4dc", "#5a1f1f", "#24344d", "#c9b48f"];
    bays.forEach((b, i) => {
      q.setFromAxisAngle(new THREE.Vector3(0, 1, 0), b.rot + Math.PI / 2 + (hash01(b.seed) - 0.5) * 0.06);
      m.compose(new THREE.Vector3(b.x, ground(b.x, b.y) + 0.2, -b.y), q, new THREE.Vector3(1, 1, 1));
      carMesh!.setMatrixAt(i, m);
      carMesh!.setColorAt(i, c.setStyle(colors[Math.floor(hash01(b.seed + 1) * colors.length)]));
    });
    carMesh.castShadow = true;
    carMesh.receiveShadow = true;
    carMesh.computeBoundingSphere();
    parking.add(carMesh);
  }
  const order = bays.map((b) => hash01(b.seed + 7));
  const setCarOccupancy = (fraction: number) => {
    if (!carMesh) return;
    // Hide cars by scaling them to zero, so the same instances come back in the same bays.
    const m = new THREE.Matrix4();
    const zero = new THREE.Matrix4().makeScale(0, 0, 0);
    const q = new THREE.Quaternion();
    bays.forEach((b, i) => {
      if (order[i] < fraction) {
        q.setFromAxisAngle(new THREE.Vector3(0, 1, 0), b.rot + Math.PI / 2 + (hash01(b.seed) - 0.5) * 0.06);
        m.compose(new THREE.Vector3(b.x, ground(b.x, b.y) + 0.2, -b.y), q, new THREE.Vector3(1, 1, 1));
        carMesh!.setMatrixAt(i, m);
      } else carMesh!.setMatrixAt(i, zero);
    });
    carMesh.instanceMatrix.needsUpdate = true;
  };

  // ---- Historic city walls and towers
  const walls = new THREE.Group();
  walls.name = "walls";
  const wallGeoms: THREE.BufferGeometry[] = [];
  const mud = new THREE.MeshStandardMaterial({ color: "#9d7550", roughness: 0.97, metalness: 0 });
  for (const w of pack.walls) {
    const d = densify(w.p, 4);
    for (let i = 2; i < d.length; i += 2) {
      const x0 = d[i - 2];
      const y0 = d[i - 1];
      const x1 = d[i];
      const y1 = d[i + 1];
      const len = Math.hypot(x1 - x0, y1 - y0);
      if (len < 0.1) continue;
      const h = 5.5;
      const seg = new THREE.BoxGeometry(len + 0.3, h + 3, w.w);
      const g = ground((x0 + x1) / 2, (y0 + y1) / 2);
      seg.rotateY(Math.atan2(y1 - y0, x1 - x0));
      seg.translate((x0 + x1) / 2, g + h / 2 - 1.5, -(y0 + y1) / 2);
      wallGeoms.push(seg.toNonIndexed());
      // Crenellation every other segment.
      const tooth = new THREE.ConeGeometry(0.55, 0.9, 4);
      tooth.rotateY(Math.PI / 4 + Math.atan2(y1 - y0, x1 - x0));
      tooth.translate((x0 + x1) / 2, g + h + 0.45, -(y0 + y1) / 2);
      tooth.deleteAttribute("uv");
      wallGeoms.push(tooth.toNonIndexed());
    }
  }
  for (const t of pack.towers) {
    const g = ground(t.x, t.y);
    const body = new THREE.CylinderGeometry(t.r * 0.82, t.r, t.h + 2, 10, 1);
    body.translate(t.x, g + t.h / 2 - 1, -t.y);
    wallGeoms.push(body.toNonIndexed());
    for (let k = 0; k < 8; k++) {
      const a = (k / 8) * Math.PI * 2;
      const tooth = new THREE.ConeGeometry(0.45, 0.8, 4);
      tooth.translate(t.x + Math.cos(a) * t.r * 0.75, g + t.h + 0.4, -t.y + Math.sin(a) * t.r * 0.75);
      tooth.deleteAttribute("uv");
      wallGeoms.push(tooth.toNonIndexed());
    }
  }
  for (const g of wallGeoms) if (g.getAttribute("uv")) g.deleteAttribute("uv");
  const wallGeom = mergeInto(wallGeoms);
  if (wallGeom) {
    const mesh = new THREE.Mesh(wallGeom, mud);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.name = "city-walls";
    walls.add(mesh);
  }

  // ---- Helipads: a pale disc with an H
  const helipads = new THREE.Group();
  helipads.name = "helipads";
  for (const h of pack.helipads) {
    const g = ground(h.x, h.y);
    const disc = new THREE.Mesh(new THREE.CircleGeometry(11, 32), overlay("#cfc6b6", 3));
    disc.rotation.x = -Math.PI / 2;
    disc.position.set(h.x, g + 0.35, -h.y);
    const ring = new THREE.Mesh(new THREE.RingGeometry(9.2, 9.8, 40), overlay("#e6b24a", 4));
    ring.rotation.x = -Math.PI / 2;
    ring.position.set(h.x, g + 0.4, -h.y);
    helipads.add(disc, ring);
    for (const [w, d, x] of [[1.1, 7, -2.2], [1.1, 7, 2.2], [3.4, 1.1, 0]] as const) {
      const bar = new THREE.Mesh(new THREE.PlaneGeometry(w, d), overlay("#f3efe6", 5));
      bar.rotation.x = -Math.PI / 2;
      bar.position.set(h.x + x, g + 0.45, -h.y);
      helipads.add(bar);
    }
  }

  return {
    roads,
    water,
    parking,
    walls,
    helipads,
    streetLights: new Float32Array(streetLights),
    pathLights: new Float32Array(pathLights),
    carMesh,
    setCarOccupancy,
    counts: {
      roads: pack.roads.length,
      paths: pack.paths.length,
      parkingBays: bays.length,
      walls: pack.walls.length,
      towers: pack.towers.length,
      helipads: pack.helipads.length,
      water: pack.water.length + pack.waterways.length,
    },
    pathsForWalkers,
  };
}
