// Buildings as low-detail extruded footprints (owner: Omar): Najdi mud buildings with a parapet and a row of
// triangular crenellations, ruins, and plain modern blocks. All merged into a few meshes; each vertex carries
// its building index (`bid`) so hover and click know which building they hit and the shader can tint it.
import * as THREE from "three";

import { centroid, hash01, perimeter, signedArea } from "../site/poly";
import type { SiteBuilding } from "../site/types";
import type { Site } from "./siteData";

const PALETTE = {
  najdi: ["#a37a54", "#9a714d", "#a9835b", "#93704f", "#a07752"],
  ruin: ["#8e7056", "#86694f", "#957a5f"],
  modern: ["#ddd5c9", "#e3dacb", "#d2c6b3", "#c9b89e", "#d8d0c6", "#cbbfae"],
  landmark: ["#d9cdb8"],
};

class Builder {
  pos: number[] = [];
  nrm: number[] = [];
  col: number[] = [];
  bid: number[] = [];
  tri(a: number[], b: number[], c: number[], n: number[], ca: number[], cb: number[], cc: number[], id: number) {
    this.pos.push(...a, ...b, ...c);
    this.nrm.push(...n, ...n, ...n);
    this.col.push(...ca, ...cb, ...cc);
    this.bid.push(id, id, id);
  }
  geometry(): THREE.BufferGeometry {
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(this.pos, 3));
    g.setAttribute("normal", new THREE.Float32BufferAttribute(this.nrm, 3));
    g.setAttribute("color", new THREE.Float32BufferAttribute(this.col, 3));
    g.setAttribute("bid", new THREE.Float32BufferAttribute(this.bid, 1));
    g.computeBoundingSphere();
    g.computeBoundingBox();
    return g;
  }
}

const shade = (c: THREE.Color, k: number) => [c.r * k, c.g * k, c.b * k];

export interface BuildingInfo {
  index: number;
  b: SiteBuilding;
  base: number; // lowest ground under it
  top: number; // roof height (scene y)
  cx: number; // centroid, local x
  cy: number; // centroid, local north
}

export interface BuildingLayer {
  group: THREE.Group;
  meshes: THREE.Mesh[];
  infos: BuildingInfo[];
  lanterns: Float32Array; // x, y, z (scene coordinates) on Najdi parapets
  uniforms: { uHover: { value: number }; uSelected: { value: number }; uTime: { value: number }; uNight: { value: number } };
  counts: { total: number; najdi: number; ruin: number; modern: number };
}

export function buildBuildings(site: Site, skip: (b: SiteBuilding) => boolean): BuildingLayer {
  const builders = { najdi: new Builder(), modern: new Builder() };
  const infos: BuildingInfo[] = [];
  const lanterns: number[] = [];
  const counts = { total: 0, najdi: 0, ruin: 0, modern: 0 };
  const color = new THREE.Color();

  site.pack.buildings.forEach((b) => {
    if (skip(b)) return;
    const index = infos.length;
    const ring = b.o;
    const n = ring.length / 2;
    if (n < 3) return;
    const [cx, cy] = centroid(ring);
    let gmin = Infinity;
    let gmax = -Infinity;
    for (let i = 0; i < ring.length; i += 2) {
      const g = site.ground(ring[i], ring[i + 1]);
      gmin = Math.min(gmin, g);
      gmax = Math.max(gmax, g);
    }
    const gc = site.ground(cx, cy);
    // Very large outlines are compounds mapped as one building (a palace with its yards): keep them low.
    const area = Math.abs(signedArea(ring));
    const h = area > 12000 ? 4.5 : area > 5000 && b.hs === "estimated" ? Math.min(b.h, 9) : b.h;
    const top = Math.max(gc + h, gmax + Math.min(2.5, h));
    const base = gmin - 1.5;
    infos.push({ index, b, base: gmin, top, cx, cy });
    counts.total++;
    counts[b.k === "landmark" ? "modern" : b.k]++;

    // A huge outline is a walled compound mapped as one building: draw its enclosure wall, not a solid slab.
    const compound = area > 12000;
    const mud = b.k === "najdi" || b.k === "ruin" || compound;
    const builder = mud ? builders.najdi : builders.modern;
    const seed = hash01(index * 7 + 3);
    const pal = PALETTE[compound ? "najdi" : b.k];
    color.setStyle(pal[Math.floor(seed * pal.length)]);
    const jitter = 0.94 + hash01(index * 13 + 1) * 0.1;
    const parapet = compound ? top - base : b.k === "najdi" ? 0.6 : b.k === "modern" && h > 5 ? 0.35 : 0;
    const roofY = top - parapet;
    const wallLow = shade(color, 0.62 * jitter);
    const wallHigh = shade(color, 1.0 * jitter);
    const roofCol = shade(color, (mud ? 1.07 : 1.03) * jitter);
    const innerCol = shade(color, 0.8 * jitter);

    const rings = [ring, ...(b.holes ?? [])];
    for (const r of rings) {
      const m = r.length / 2;
      for (let i = 0; i < m; i++) {
        const x0 = r[i * 2];
        const y0 = r[i * 2 + 1];
        const x1 = r[((i + 1) % m) * 2];
        const y1 = r[((i + 1) % m) * 2 + 1];
        const dx = x1 - x0;
        const dy = y1 - y0;
        const len = Math.hypot(dx, dy);
        if (len < 0.05) continue;
        const nrm = [dy / len, 0, dx / len]; // outward (scene z = -north)
        const A = [x0, base, -y0];
        const B = [x1, base, -y1];
        const C = [x1, top, -y1];
        const D = [x0, top, -y0];
        builder.tri(A, B, C, nrm, wallLow, wallLow, wallHigh, index);
        builder.tri(A, C, D, nrm, wallLow, wallHigh, wallHigh, index);
        if (parapet > 0) {
          // Inner face of the parapet, seen from above.
          const inN = [-nrm[0], 0, -nrm[2]];
          const a2 = [x0, roofY, -y0];
          const b2 = [x1, roofY, -y1];
          builder.tri(b2, a2, D, inN, innerCol, innerCol, wallHigh, index);
          builder.tri(b2, D, C, inN, innerCol, wallHigh, wallHigh, index);
        }
        if ((b.k === "najdi" || compound) && r === ring) {
          // Triangular crenellations along the top edge, both faces.
          const step = 1.7;
          const teeth = Math.floor(len / step);
          const off = (len - teeth * step) / 2 + step / 2;
          for (let t = 0; t < teeth; t++) {
            const s = (off + t * step) / len;
            const half = 0.5 / len;
            const ax = x0 + dx * (s - half);
            const ay = y0 + dy * (s - half);
            const bx = x0 + dx * (s + half);
            const by = y0 + dy * (s + half);
            const px = x0 + dx * s;
            const py = y0 + dy * s;
            const P = [px, top + 0.75, -py];
            const L = [ax, top, -ay];
            const R = [bx, top, -by];
            builder.tri(L, R, P, nrm, wallHigh, wallHigh, wallHigh, index);
            builder.tri(R, L, P, [-nrm[0], 0, -nrm[2]], wallHigh, wallHigh, wallHigh, index);
          }
          // Warm lights along the parapet, every few metres, on bigger buildings.
          if (perimeter(ring) > 24 && !compound) {
            const every = 5.5;
            for (let d = every / 2; d < len; d += every) {
              const s = d / len;
              lanterns.push(x0 + dx * s + nrm[0] * 0.25, top - 0.25, -(y0 + dy * s) + nrm[2] * 0.25);
            }
          }
        }
      }
    }

    if (compound) return; // open to the sky

    // Roof (with courtyards cut out).
    const contour = [];
    for (let i = 0; i < ring.length; i += 2) contour.push(new THREE.Vector2(ring[i], ring[i + 1]));
    const holes = (b.holes ?? []).map((h) => {
      const out = [];
      for (let i = 0; i < h.length; i += 2) out.push(new THREE.Vector2(h[i], h[i + 1]));
      return out;
    });
    const all = [...contour, ...holes.flat()];
    const faces = THREE.ShapeUtils.triangulateShape(contour, holes);
    const up = [0, 1, 0];
    for (const [a, c, d] of faces) {
      const pa = all[a];
      const pb = all[c];
      const pc = all[d];
      // Counter-clockwise seen from above in (x, north) = clockwise in scene (x, z): fix the winding to face up.
      const cross = (pb.x - pa.x) * (pc.y - pa.y) - (pb.y - pa.y) * (pc.x - pa.x);
      const A = [pa.x, roofY, -pa.y];
      const B = [pb.x, roofY, -pb.y];
      const C = [pc.x, roofY, -pc.y];
      if (cross >= 0) builder.tri(A, B, C, up, roofCol, roofCol, roofCol, index);
      else builder.tri(A, C, B, up, roofCol, roofCol, roofCol, index);
    }
  });

  const uniforms = { uHover: { value: -1 }, uSelected: { value: -1 }, uTime: { value: 0 }, uNight: { value: 0 } };
  const group = new THREE.Group();
  group.name = "buildings";
  const meshes: THREE.Mesh[] = [];
  for (const [kind, builder] of Object.entries(builders)) {
    if (!builder.pos.length) continue;
    const mesh = new THREE.Mesh(builder.geometry(), buildingMaterial(uniforms, kind === "najdi"));
    mesh.name = `buildings-${kind}`;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    meshes.push(mesh);
    group.add(mesh);
  }
  return { group, meshes, infos, lanterns: new Float32Array(lanterns), uniforms, counts };
}

function buildingMaterial(uniforms: BuildingLayer["uniforms"], mud: boolean): THREE.MeshStandardMaterial {
  const m = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: mud ? 0.97 : 0.88, metalness: 0 });
  m.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms);
    shader.vertexShader = shader.vertexShader
      .replace(
        "#include <common>",
        `#include <common>
         attribute float bid; varying float vBid; varying vec3 vWorld; varying float vUp;`,
      )
      .replace(
        "#include <begin_vertex>",
        `#include <begin_vertex>
         vBid = bid; vWorld = (modelMatrix * vec4(position, 1.0)).xyz; vUp = normal.y;`,
      );
    shader.fragmentShader = shader.fragmentShader
      .replace(
        "#include <common>",
        `#include <common>
         uniform float uHover; uniform float uSelected; uniform float uNight; varying float vBid; varying vec3 vWorld; varying float vUp;
         float h3(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }
         float vnoise(vec3 p) {
           vec3 i = floor(p); vec3 f = fract(p); f = f * f * (3.0 - 2.0 * f);
           return mix(mix(mix(h3(i), h3(i + vec3(1,0,0)), f.x), mix(h3(i + vec3(0,1,0)), h3(i + vec3(1,1,0)), f.x), f.y),
                      mix(mix(h3(i + vec3(0,0,1)), h3(i + vec3(1,0,1)), f.x), mix(h3(i + vec3(0,1,1)), h3(i + vec3(1,1,1)), f.x), f.y), f.z);
         }`,
      )
      .replace(
        "#include <color_fragment>",
        `#include <color_fragment>
         {
           float n = vnoise(vWorld * ${mud ? "1.3" : "0.7"}) * 0.6 + vnoise(vWorld * 5.0) * 0.4;
           diffuseColor.rgb *= ${mud ? "0.86 + 0.2 * n" : "0.95 + 0.08 * n"};
           if (abs(vBid - uSelected) < 0.5) diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.93, 0.62, 0.25), 0.45);
           else if (abs(vBid - uHover) < 0.5) diffuseColor.rgb *= 1.28;
         }`,
      )
      .replace(
        "#include <emissivemap_fragment>",
        `#include <emissivemap_fragment>
         ${mud
           ? // Heritage walls are floodlit at night: warm light washing up from the ground.
             "totalEmissiveRadiance += diffuseColor.rgb * vec3(1.0, 0.62, 0.36) * uNight * (0.35 + 0.45 * clamp(1.0 - fract(vWorld.y / 9.0), 0.0, 1.0));"
           : // Lit windows on modern buildings: a few warm cells per floor.
             `{
               float floorBand = step(0.35, fract(vWorld.y / 3.3)) * step(fract(vWorld.y / 3.3), 0.75);
               float cell = h3(floor(vec3(vWorld.x / 2.2, vWorld.y / 3.3, vWorld.z / 2.2)));
               float lit = floorBand * step(0.62, cell) * step(abs(vUp), 0.5);
               totalEmissiveRadiance += vec3(1.0, 0.72, 0.42) * lit * uNight * 0.9;
             }`}`,
      );
  };
  return m;
}
