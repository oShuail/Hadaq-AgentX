// Simulated visitors walking the heritage paths (owner: Omar). Not real people counts: a mood layer
// whose crowd size follows typical opening hours.
import * as THREE from "three";

import { hash01 } from "../site/poly";

interface Walker {
  path: number;
  s: number; // distance along the path, m
  dir: 1 | -1;
  speed: number;
  side: number; // metres left/right of the centre line
}

interface Track {
  p: number[];
  cum: number[];
  length: number;
}

export interface WalkerLayer {
  mesh: THREE.InstancedMesh;
  max: number;
  update: (dt: number, crowd: number) => void;
}

/** Visitor numbers by local hour: quiet mornings, busy evenings, closed after midnight (an assumption). */
export function crowdAt(hour: number): number {
  if (hour < 1) return 0.35;
  if (hour < 8) return 0;
  if (hour < 16) return 0.15 + ((hour - 8) / 8) * 0.25;
  if (hour < 22) return 0.55 + Math.sin(((hour - 16) / 6) * Math.PI) * 0.45;
  return 0.55 - (hour - 22) * 0.1;
}

export function buildWalkers(paths: number[][], ground: (x: number, y: number) => number, max = 900): WalkerLayer | null {
  const tracks: Track[] = paths
    .map((p) => {
      const cum = [0];
      for (let i = 2; i < p.length; i += 2) cum.push(cum[cum.length - 1] + Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]));
      return { p, cum, length: cum[cum.length - 1] };
    })
    .filter((t) => t.length > 15);
  if (!tracks.length) return null;
  const totalLength = tracks.reduce((a, t) => a + t.length, 0);
  const geometry = new THREE.CapsuleGeometry(0.24, 1.15, 2, 6);
  geometry.translate(0, 0.82, 0);
  const mesh = new THREE.InstancedMesh(geometry, new THREE.MeshStandardMaterial({ roughness: 0.8 }), max);
  mesh.name = "visitors";
  mesh.castShadow = true;
  mesh.frustumCulled = false;
  const palette = ["#f4f1ea", "#f4f1ea", "#f4f1ea", "#151515", "#151515", "#2b3b52", "#7a5232", "#c9b28c", "#8b2e2e"];
  const c = new THREE.Color();
  const walkers: Walker[] = [];
  for (let i = 0; i < max; i++) {
    // More walkers on longer paths.
    let pick = hash01(i * 5) * totalLength;
    let path = 0;
    while (path < tracks.length - 1 && pick > tracks[path].length) pick -= tracks[path++].length;
    walkers.push({
      path,
      s: hash01(i * 5 + 1) * tracks[path].length,
      dir: hash01(i * 5 + 2) > 0.5 ? 1 : -1,
      speed: 1.0 + hash01(i * 5 + 3) * 0.5,
      side: (hash01(i * 5 + 4) - 0.5) * 2.2,
    });
    mesh.setColorAt(i, c.setStyle(palette[Math.floor(hash01(i * 11) * palette.length)]));
  }
  const m = new THREE.Matrix4();
  const pos = new THREE.Vector3();
  const q = new THREE.Quaternion();
  const one = new THREE.Vector3(1, 1, 1);
  const up = new THREE.Vector3(0, 1, 0);

  const update = (dt: number, crowd: number) => {
    const n = Math.round(max * Math.max(0, Math.min(1, crowd)));
    mesh.count = n;
    for (let i = 0; i < n; i++) {
      const w = walkers[i];
      const t = tracks[w.path];
      w.s += w.dir * w.speed * dt;
      if (w.s > t.length) {
        w.s = t.length;
        w.dir = -1;
      } else if (w.s < 0) {
        w.s = 0;
        w.dir = 1;
      }
      let k = 1;
      while (k < t.cum.length - 1 && t.cum[k] < w.s) k++;
      const seg = t.cum[k] - t.cum[k - 1] || 1;
      const f = (w.s - t.cum[k - 1]) / seg;
      const x0 = t.p[(k - 1) * 2];
      const y0 = t.p[(k - 1) * 2 + 1];
      const dx = t.p[k * 2] - x0;
      const dy = t.p[k * 2 + 1] - y0;
      const len = Math.hypot(dx, dy) || 1;
      const x = x0 + dx * f - (dy / len) * w.side;
      const y = y0 + dy * f + (dx / len) * w.side;
      pos.set(x, ground(x, y) + 0.25, -y);
      q.setFromAxisAngle(up, Math.atan2(dx * w.dir, dy * w.dir));
      m.compose(pos, q, one);
      mesh.setMatrixAt(i, m);
    }
    mesh.instanceMatrix.needsUpdate = true;
  };
  return { mesh, max, update };
}
