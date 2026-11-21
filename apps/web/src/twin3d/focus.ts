// The focus building B01 (owner: Omar): our own 3D model at its spot in At-Turaif, the 4 x 8 wall-zone grid
// on its main facade, and the attention boxes around zones that need a look.
//
// The model GLB follows the convention in the old Cesium version (see docs): origin = middle of the main
// wall's base on the ground, +Y up, +X straight out of the facade, +Z along the wall towards base_line.start.
// Its outline in model metres sits next to it as <model>.footprint.json.
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { Line2 } from "three/addons/lines/Line2.js";
import { LineGeometry } from "three/addons/lines/LineGeometry.js";
import { LineMaterial } from "three/addons/lines/LineMaterial.js";
import { CSS2DObject } from "three/addons/renderers/CSS2DRenderer.js";

import { ATTENTION_COLORS, type AttentionItem } from "../twin/WallZones";
import type { WallSpec } from "../types/contracts";
import type { Site } from "./siteData";

export interface FocusPlacement {
  midX: number; // local east
  midY: number; // local north
  az: number; // facade azimuth, radians (compass)
  ground: number;
  out: [number, number]; // unit vector out of the facade (east, north)
  left: [number, number]; // unit vector towards base_line.start
  wallLength: number;
  footprint: number[] | null; // local ring (x, north)
}

export async function focusPlacement(site: Site, wall: WallSpec, modelUrl: string): Promise<FocusPlacement> {
  const [sx, sy] = site.proj.toLocal(wall.base_line.start.lon, wall.base_line.start.lat);
  const [ex, ey] = site.proj.toLocal(wall.base_line.end.lon, wall.base_line.end.lat);
  const midX = (sx + ex) / 2;
  const midY = (sy + ey) / 2;
  const az = (wall.azimuth_deg * Math.PI) / 180;
  const out: [number, number] = [Math.sin(az), Math.cos(az)];
  const left: [number, number] = [Math.cos(az), -Math.sin(az)];
  let footprint: number[] | null = null;
  if (modelUrl) {
    try {
      const res = await fetch(modelUrl.replace(/\.glb$/i, ".footprint.json"));
      if (res.ok && (res.headers.get("content-type") ?? "").includes("json")) {
        const { local_m } = (await res.json()) as { local_m: [number, number][] };
        footprint = local_m.flatMap(([x, z]) => [midX + x * out[0] + z * left[0], midY + x * out[1] + z * left[1]]);
      }
    } catch {
      footprint = null;
    }
  }
  return { midX, midY, az, ground: site.ground(midX, midY), out, left, wallLength: Math.hypot(ex - sx, ey - sy), footprint };
}

export async function loadFocusModel(url: string, place: FocusPlacement): Promise<THREE.Group> {
  const gltf = await new GLTFLoader().loadAsync(url);
  const root = new THREE.Group();
  root.name = "focus-model";
  root.add(gltf.scene);
  root.position.set(place.midX, place.ground, -place.midY);
  root.rotation.y = Math.PI / 2 - place.az; // model +X -> out of the facade
  root.traverse((o) => {
    const mesh = o as THREE.Mesh;
    if (!mesh.isMesh) return;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.userData.focus = true;
    for (const m of Array.isArray(mesh.material) ? mesh.material : [mesh.material]) {
      const std = m as THREE.MeshStandardMaterial;
      if (std.isMeshStandardMaterial) {
        std.metalness = 0; // mud, not metal (the AI model ships metallicFactor 1)
        std.roughness = Math.max(std.roughness, 0.85);
      }
    }
  });
  return root;
}

// ------------------------------------------------------------------------------------------------
// Wall zones and attention boxes

const ZONE_OFFSET = 0.4; // panels float 40 cm in front of the wall
const BOX_OFFSET = 0.6;

/** A reading card pinned to a wall zone with a leader line (the HUD's "in-world" callouts). */
export interface Callout {
  zoneId: string;
  title: string; // e.g. "R1C3"
  rows: { label: string; value: string; unit: string; tag: "REAL" | "SIM" | ""; fault?: boolean }[];
  tone?: "alert" | "";
  side: "left" | "right";
  dx: number; // screen px from the zone to the card
  dy: number;
}

export class WallOverlay {
  group = new THREE.Group();
  zones = new Map<string, THREE.Mesh>();
  callouts = new THREE.Group();
  private attention = new THREE.Group();
  private outlines = new Map<string, THREE.LineSegments>();
  private lineMaterials: LineMaterial[] = [];

  constructor(private wall: WallSpec, private buildingId: string, private place: FocusPlacement) {
    this.group.name = "wall-zones";
    this.attention.name = "attention";
    const { rows, cols, height_m } = wall;
    const rowH = height_m / rows;
    const colW = place.wallLength / cols;
    const [ox, oy] = place.out;
    const [lx, ly] = place.left;
    for (let r = 1; r <= rows; r++)
      for (let c = 1; c <= cols; c++) {
        const id = `${buildingId}-${wall.id}-R${r}C${c}`;
        // Column 1 is at base_line.start (the left end, facing the facade).
        const along = place.wallLength / 2 - (c - 0.5) * colW;
        const x = place.midX + lx * along + ox * ZONE_OFFSET;
        const y = place.midY + ly * along + oy * ZONE_OFFSET;
        const geom = new THREE.PlaneGeometry(colW - 0.08, rowH - 0.08);
        const mesh = new THREE.Mesh(
          geom,
          new THREE.MeshBasicMaterial({ color: "#D9C3A0", transparent: true, opacity: 0.55, side: THREE.DoubleSide, depthWrite: false }),
        );
        mesh.position.set(x, place.ground + (r - 0.5) * rowH, -y);
        mesh.rotation.y = Math.PI - place.az; // plane normal (+Z) -> out of the facade
        mesh.name = `zone:${id}`;
        mesh.userData.zoneId = id;
        mesh.renderOrder = 3;
        const edges = new THREE.LineSegments(
          new THREE.EdgesGeometry(geom),
          new THREE.LineBasicMaterial({ color: "#ffffff", transparent: true, opacity: 0.35 }),
        );
        mesh.add(edges);
        this.outlines.set(id, edges);
        this.zones.set(id, mesh);
        this.group.add(mesh);
      }
  }

  get attentionGroup() {
    return this.attention;
  }

  setVisible(v: boolean) {
    this.group.visible = v;
  }

  setColors(colors: Record<string, string>) {
    for (const [id, mesh] of this.zones) {
      const css = colors[id];
      const m = mesh.material as THREE.MeshBasicMaterial;
      m.color.set(css ?? "#D9C3A0");
      m.opacity = css ? 0.68 : 0.55;
    }
  }

  highlight(zoneId: string | null) {
    for (const [id, edges] of this.outlines) {
      const m = edges.material as THREE.LineBasicMaterial;
      m.color.set(id === zoneId ? "#E0B15A" : "#ffffff");
      m.opacity = id === zoneId ? 1 : 0.35;
    }
  }

  setResolution(w: number, h: number) {
    for (const m of this.lineMaterials) m.resolution.set(w, h);
  }

  /** Replace the pinned reading cards. Each sits on its zone's centre, its card offset by (dx, dy) screen px. */
  setCallouts(items: Callout[], onPick: (zoneId: string) => void) {
    // Readings arrive several times a second: update the cards in place, rebuild one only when its layout changes,
    // so a card never disappears under the pointer.
    this.onPickCallout = onPick;
    const wanted = new Set(items.map((i) => i.zoneId));
    for (const [zoneId, entry] of this.calloutCache) {
      if (!wanted.has(zoneId)) this.dropCallout(zoneId, entry.obj);
    }
    for (const item of items) {
      const zone = this.zones.get(item.zoneId);
      if (!zone) continue;
      const sig = JSON.stringify([item.side, item.dx, item.dy, item.tone, item.title, item.rows.map((r) => [r.label, r.tag])]);
      let entry = this.calloutCache.get(item.zoneId);
      if (entry && entry.sig !== sig) {
        this.dropCallout(item.zoneId, entry.obj);
        entry = undefined;
      }
      if (!entry) entry = this.buildCallout(item, zone, sig);
      item.rows.forEach((r, i) => {
        const el = entry.rows[i];
        el.row.className = `row${r.fault ? " fault" : ""}`;
        if (el.value.textContent !== r.value) el.value.textContent = r.value;
        if (el.unit.textContent !== r.unit) el.unit.textContent = r.unit;
      });
    }
  }

  private onPickCallout: (zoneId: string) => void = () => {};
  private calloutCache = new Map<string, { obj: CSS2DObject; sig: string; rows: { row: HTMLElement; value: HTMLElement; unit: HTMLElement }[] }>();

  private dropCallout(zoneId: string, obj: CSS2DObject) {
    this.callouts.remove(obj);
    obj.element.remove();
    this.calloutCache.delete(zoneId);
  }

  private buildCallout(item: Callout, zone: THREE.Mesh, sig: string) {
    const [ox, oy] = this.place.out;
    const root = document.createElement("div");
    root.className = `twin-callout ${item.side}${item.tone ? ` ${item.tone}` : ""}`;
    const dot = document.createElement("i");
    dot.className = "dot";
    const leader = document.createElement("span");
    leader.className = "leader";
    const sx = item.side === "left" ? -item.dx : item.dx;
    leader.style.width = `${Math.hypot(sx, item.dy)}px`;
    leader.style.transform = `rotate(${Math.atan2(item.dy, sx)}rad)`;
    const card = document.createElement("button");
    card.type = "button";
    card.className = "card";
    card.style.top = `${item.dy}px`;
    card.style[item.side === "left" ? "right" : "left"] = `${item.dx}px`;
    card.title = "Open this zone";
    const head = document.createElement("span");
    head.className = "head";
    head.textContent = item.title;
    card.appendChild(head);
    const rows = item.rows.map((r) => {
      const row = document.createElement("span");
      const label = document.createElement("span");
      label.className = "label";
      label.textContent = r.label;
      const value = document.createElement("b");
      const unit = document.createElement("small");
      row.append(label, value, unit);
      if (r.tag) {
        const tag = document.createElement("em");
        tag.className = `tag ${r.tag === "REAL" ? "real" : ""}`;
        tag.textContent = r.tag;
        row.appendChild(tag);
      }
      card.appendChild(row);
      return { row, value, unit };
    });
    card.addEventListener("click", (e) => {
      e.stopPropagation();
      this.onPickCallout(item.zoneId);
    });
    root.append(leader, dot, card);
    const obj = new CSS2DObject(root);
    obj.position.set(zone.position.x + ox * 0.2, zone.position.y, zone.position.z - oy * 0.2);
    obj.center.set(0, 0);
    this.callouts.add(obj);
    const entry = { obj, sig, rows };
    this.calloutCache.set(item.zoneId, entry);
    return entry;
  }

  setAttention(items: AttentionItem[]) {
    for (const child of [...this.attention.children]) {
      this.attention.remove(child);
      if (child instanceof CSS2DObject) child.element.remove();
    }
    this.lineMaterials = [];
    const { rows, cols, height_m } = this.wall;
    const rowH = height_m / rows;
    const colW = this.place.wallLength / cols;
    const prefix = `${this.buildingId}-${this.wall.id}-`;
    const [ox, oy] = this.place.out;
    const [lx, ly] = this.place.left;
    for (const item of items) {
      const m = item.zoneId.replace(prefix, "").match(/^R(\d+)C(\d+)$/);
      if (!m) continue;
      const r = Number(m[1]);
      const c = Number(m[2]);
      const a0 = this.place.wallLength / 2 - (c - 1) * colW - 0.1;
      const a1 = this.place.wallLength / 2 - c * colW + 0.1;
      const lo = this.place.ground + (r - 1) * rowH + 0.08;
      const hi = this.place.ground + r * rowH - 0.08;
      const pt = (a: number, h: number) => {
        const x = this.place.midX + lx * a + ox * BOX_OFFSET;
        const y = this.place.midY + ly * a + oy * BOX_OFFSET;
        return [x, h, -y];
      };
      const color = ATTENTION_COLORS[item.level];
      const geom = new LineGeometry();
      geom.setPositions([...pt(a0, lo), ...pt(a1, lo), ...pt(a1, hi), ...pt(a0, hi), ...pt(a0, lo)]);
      const mat = new LineMaterial({ color, linewidth: item.level === "critical" ? 5 : 4, worldUnits: false, depthTest: false, transparent: true });
      this.lineMaterials.push(mat);
      const line = new Line2(geom, mat);
      line.renderOrder = 10;
      this.attention.add(line);
      const el = document.createElement("div");
      el.className = `twin-attention ${item.level}`;
      el.textContent = `${item.zoneId.replace(prefix, "")} · ${item.reasons.join(" · ")}`;
      const label = new CSS2DObject(el);
      const [x, , z] = pt((a0 + a1) / 2, hi + 0.4);
      label.position.set(x, hi + 0.4, z);
      label.center.set(0.5, 1);
      this.attention.add(label);
    }
  }
}
