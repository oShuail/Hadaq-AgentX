// Place names over the 3D scene (owner: Omar). Large names for districts, small dotted labels for places.
// Labels that would overlap are hidden, nearest and most important first, so the map stays readable.
import * as THREE from "three";
import { CSS2DObject } from "three/addons/renderers/CSS2DRenderer.js";

export type LabelKind = "area" | "poi" | "focus";

export interface LabelSpec {
  text: string;
  sub?: string;
  kind: LabelKind;
  position: THREE.Vector3;
  priority: number;
  onClick?: () => void;
}

interface Placed {
  spec: LabelSpec;
  obj: CSS2DObject;
  w: number;
  h: number;
}

export class LabelLayer {
  group = new THREE.Group();
  private items: Placed[] = [];
  private v = new THREE.Vector3();
  enabled = true;

  constructor() {
    this.group.name = "labels";
  }

  add(spec: LabelSpec): CSS2DObject {
    const el = document.createElement("div");
    el.className = `twin-label ${spec.kind}`;
    if (spec.kind === "poi") el.innerHTML = `<i></i><span></span>`;
    else el.innerHTML = `<span></span>${spec.sub ? "<small></small>" : ""}`;
    el.querySelector("span")!.textContent = spec.text;
    if (spec.sub) el.querySelector("small")!.textContent = spec.sub;
    if (spec.onClick) {
      el.classList.add("clickable");
      el.addEventListener("pointerdown", (e) => e.stopPropagation());
      el.addEventListener("click", (e) => {
        e.stopPropagation();
        spec.onClick?.();
      });
    }
    const obj = new CSS2DObject(el);
    obj.position.copy(spec.position);
    obj.center.set(spec.kind === "poi" ? 0 : 0.5, spec.kind === "poi" ? 0.5 : 1);
    this.group.add(obj);
    const chars = spec.text.length;
    const w = spec.kind === "area" ? chars * 9.5 + 26 : spec.kind === "focus" ? Math.max(chars, (spec.sub ?? "").length) * 7.5 + 30 : chars * 6.8 + 26;
    const h = spec.kind === "area" ? 30 : spec.kind === "focus" ? 40 : 22;
    this.items.push({ spec, obj, w, h });
    return obj;
  }

  /** Decide which labels show this frame. Call a few times a second, not every frame. */
  update(camera: THREE.PerspectiveCamera, width: number, height: number) {
    const cam = camera.position;
    const candidates: { p: Placed; sx: number; sy: number; d: number }[] = [];
    for (const p of this.items) {
      p.obj.visible = false;
      if (!this.enabled && p.spec.kind !== "focus") continue;
      const d = p.obj.position.distanceTo(cam);
      const max = p.spec.kind === "area" ? 12000 : p.spec.kind === "focus" ? 20000 : 1700;
      if (d > max) continue;
      this.v.copy(p.obj.position).project(camera);
      if (this.v.z > 1 || this.v.x < -1.1 || this.v.x > 1.1 || this.v.y < -1.1 || this.v.y > 1.1) continue;
      candidates.push({ p, sx: ((this.v.x + 1) / 2) * width, sy: ((1 - this.v.y) / 2) * height, d });
    }
    candidates.sort((a, b) => b.p.spec.priority - a.p.spec.priority || a.d - b.d);
    const rects: [number, number, number, number][] = [];
    let small = 0;
    for (const c of candidates) {
      const { w, h, spec } = c.p;
      if (spec.kind === "poi" && small >= 40) continue;
      const x0 = spec.kind === "poi" ? c.sx - 6 : c.sx - w / 2;
      const y0 = spec.kind === "poi" ? c.sy - h / 2 : c.sy - h;
      const r: [number, number, number, number] = [x0 - 4, y0 - 3, x0 + w + 4, y0 + h + 3];
      if (spec.kind !== "focus" && rects.some((o) => r[0] < o[2] && r[2] > o[0] && r[1] < o[3] && r[3] > o[1])) continue;
      rects.push(r);
      c.p.obj.visible = true;
      if (spec.kind === "poi") small++;
    }
  }
}
