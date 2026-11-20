// Date palms, instanced (owner: Omar). Placed at bake time wherever the imagery shows palm canopy.
// The area is split into chunks so the GPU skips what is off screen, and far chunks use a cheaper palm.
import * as THREE from "three";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";

import { hash01 } from "../site/poly";
import type { Site } from "./siteData";

function colorize(g: THREE.BufferGeometry, base: THREE.Color, tip: THREE.Color, axis: "y" | "r", sway: number) {
  const pos = g.getAttribute("position");
  const col = new Float32Array(pos.count * 3);
  const swayA = new Float32Array(pos.count);
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    const y = pos.getY(i);
    const z = pos.getZ(i);
    const t = axis === "y" ? Math.min(1, Math.max(0, y / 8)) : Math.min(1, Math.hypot(x, z) / 3.6);
    const c = base.clone().lerp(tip, t);
    col.set([c.r, c.g, c.b], i * 3);
    swayA[i] = sway * t;
  }
  g.setAttribute("color", new THREE.BufferAttribute(col, 3));
  g.setAttribute("sway", new THREE.BufferAttribute(swayA, 1));
  return g;
}

/** One frond: a narrow strip that rises then droops, built along +x. */
function frond(segments: number, length: number, width: number): THREE.BufferGeometry {
  const pos: number[] = [];
  const idx: number[] = [];
  for (let s = 0; s <= segments; s++) {
    const t = s / segments;
    const x = t * length;
    const y = Math.sin(t * Math.PI * 0.55) * 1.1 - t * t * 2.1;
    const w = width * Math.sin(Math.PI * Math.min(1, t * 1.15 + 0.08)) * (1 - t * 0.35);
    pos.push(x, y, -w, x, y + 0.05, w);
  }
  for (let s = 0; s < segments; s++) {
    const a = s * 2;
    idx.push(a, a + 2, a + 1, a + 1, a + 2, a + 3);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

function palmGeometry(detail: "high" | "low"): THREE.BufferGeometry {
  const trunkH = 8;
  const trunk = new THREE.CylinderGeometry(0.2, 0.3, trunkH, detail === "high" ? 5 : 3, 1, true);
  trunk.translate(0, trunkH / 2, 0);
  trunk.deleteAttribute("uv");
  colorize(trunk, new THREE.Color("#5a4632"), new THREE.Color("#8a6f50"), "y", 0);
  const fronds: THREE.BufferGeometry[] = [];
  const count = detail === "high" ? 9 : 5;
  for (let i = 0; i < count; i++) {
    const f = frond(detail === "high" ? 3 : 1, detail === "high" ? 3.8 : 3.5, detail === "high" ? 0.7 : 1.0);
    f.rotateZ(((i % 2 ? 12 : 26) * Math.PI) / 180);
    f.rotateY((i / count) * Math.PI * 2 + (i % 2) * 0.3);
    f.translate(0, trunkH - 0.1, 0);
    colorize(f, new THREE.Color("#45552a"), new THREE.Color("#7f8c48"), "r", 1);
    fronds.push(f);
  }
  const merged = mergeGeometries([trunk.toNonIndexed(), ...fronds.map((f) => f.toNonIndexed())]);
  merged.computeBoundingSphere();
  return merged;
}

export interface PalmLayer {
  group: THREE.Group;
  count: number;
  update: (camera: THREE.Camera) => void;
  uniforms: { uTime: { value: number } };
}

export function buildPalms(site: Site, keepOut: (x: number, y: number) => boolean): PalmLayer {
  const uniforms = { uTime: { value: 0 } };
  const material = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.9, metalness: 0, side: THREE.DoubleSide });
  material.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms);
    shader.vertexShader = shader.vertexShader
      .replace("#include <common>", "#include <common>\nattribute float sway; uniform float uTime;")
      .replace(
        "#include <begin_vertex>",
        `#include <begin_vertex>
         {
           vec4 o = instanceMatrix * vec4(0.0, 0.0, 0.0, 1.0);
           float ph = o.x * 0.11 + o.z * 0.07;
           transformed.x += sway * 0.18 * sin(uTime * 1.3 + ph);
           transformed.z += sway * 0.12 * cos(uTime * 1.1 + ph * 1.3);
         }`,
      );
  };
  const hi = palmGeometry("high");
  const lo = palmGeometry("low");
  const group = new THREE.Group();
  group.name = "palms";

  // Bucket palms into 400 m chunks.
  const CHUNK = 400;
  const buckets = new Map<string, number[]>();
  const p = site.palms;
  let count = 0;
  for (let i = 0; i < p.length; i += 3) {
    if (keepOut(p[i], p[i + 1])) continue;
    const key = `${Math.floor(p[i] / CHUNK)},${Math.floor(p[i + 1] / CHUNK)}`;
    let list = buckets.get(key);
    if (!list) buckets.set(key, (list = []));
    list.push(i);
    count++;
  }

  const chunks: { center: THREE.Vector3; hi: THREE.InstancedMesh; lo: THREE.InstancedMesh }[] = [];
  const m = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const s = new THREE.Vector3();
  const t = new THREE.Vector3();
  const tint = new THREE.Color();
  const up = new THREE.Vector3(0, 1, 0);
  for (const [key, list] of buckets) {
    const meshHi = new THREE.InstancedMesh(hi, material, list.length);
    const meshLo = new THREE.InstancedMesh(lo, material, list.length);
    list.forEach((i, k) => {
      const x = p[i];
      const y = p[i + 1];
      const size = p[i + 2];
      const r = hash01(i + 17);
      t.set(x, site.ground(x, y) - 0.3, -y);
      q.setFromAxisAngle(up, r * Math.PI * 2);
      const lean = new THREE.Quaternion().setFromEuler(new THREE.Euler((hash01(i + 5) - 0.5) * 0.12, 0, (hash01(i + 9) - 0.5) * 0.12));
      q.multiply(lean);
      s.setScalar(size * (0.85 + r * 0.3));
      m.compose(t, q, s);
      meshHi.setMatrixAt(k, m);
      meshLo.setMatrixAt(k, m);
      tint.setRGB(0.88 + r * 0.24, 0.9 + hash01(i + 3) * 0.2, 0.85 + hash01(i + 4) * 0.15);
      meshHi.setColorAt(k, tint);
      meshLo.setColorAt(k, tint);
    });
    for (const mesh of [meshHi, meshLo]) {
      mesh.computeBoundingSphere();
      mesh.receiveShadow = true;
      mesh.name = `palms-${key}`;
      group.add(mesh);
    }
    meshHi.castShadow = true;
    chunks.push({ center: meshHi.boundingSphere!.center.clone(), hi: meshHi, lo: meshLo });
  }

  const camPos = new THREE.Vector3();
  const update = (camera: THREE.Camera) => {
    camera.getWorldPosition(camPos);
    for (const c of chunks) {
      const near = c.center.distanceTo(camPos) < 750;
      c.hi.visible = near;
      c.lo.visible = !near;
    }
  };
  return { group, count, update, uniforms };
}
