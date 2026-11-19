// Wall zone overlay (owner: Omar): 4 x 8 colored panels drawn just in front of the facade.
import {
  Cartesian3,
  Cartographic,
  Color,
  ColorMaterialProperty,
  ConstantProperty,
  Entity,
  HorizontalOrigin,
  VerticalOrigin,
  Viewer,
  sampleTerrainMostDetailed,
} from "cesium";

import type { WallSpec } from "../types/contracts";
import { offsetLonLat } from "./geo";

const OFFSET_M = 0.4; // panels float 40 cm in front of the wall so they do not flicker against it

export async function createWallZones(viewer: Viewer, wall: WallSpec, buildingId: string): Promise<Map<string, Entity>> {
  const { start, end } = wall.base_line;
  const ground = await sampleTerrainMostDetailed(viewer.terrainProvider, [
    Cartographic.fromDegrees(start.lon, start.lat),
    Cartographic.fromDegrees(end.lon, end.lat),
  ]).then((points) => Math.min(...points.map((p) => p.height ?? 0)));

  const [sLon, sLat] = offsetLonLat(start.lon, start.lat, OFFSET_M, wall.azimuth_deg);
  const [eLon, eLat] = offsetLonLat(end.lon, end.lat, OFFSET_M, wall.azimuth_deg);
  const rowHeight = wall.height_m / wall.rows;
  const zones = new Map<string, Entity>();

  for (let r = 1; r <= wall.rows; r++) {
    for (let c = 1; c <= wall.cols; c++) {
      const t0 = (c - 1) / wall.cols;
      const t1 = c / wall.cols;
      const lo = ground + (r - 1) * rowHeight + 0.03;
      const hi = ground + r * rowHeight - 0.03;
      const id = `${buildingId}-${wall.id}-R${r}C${c}`;
      const entity = viewer.entities.add({
        id,
        name: id,
        wall: {
          positions: Cartesian3.fromDegreesArray([
            sLon + (eLon - sLon) * t0, sLat + (eLat - sLat) * t0,
            sLon + (eLon - sLon) * t1, sLat + (eLat - sLat) * t1,
          ]),
          minimumHeights: [lo, lo],
          maximumHeights: [hi, hi],
          material: Color.fromCssColorString("#D9C3A0").withAlpha(0.5),
          outline: true,
          outlineColor: Color.WHITE.withAlpha(0.35),
        },
      });
      zones.set(id, entity);
    }
  }
  return zones;
}

export function colorZones(zones: Map<string, Entity>, colors: Record<string, string>): void {
  for (const [id, entity] of zones) {
    const css = colors[id];
    if (css && entity.wall) entity.wall.material = new ColorMaterialProperty(Color.fromCssColorString(css).withAlpha(0.65));
  }
}

export function showZones(zones: Map<string, Entity>, visible: boolean): void {
  for (const entity of zones.values()) entity.show = visible;
}

export function highlightZone(zones: Map<string, Entity>, zoneId: string | null): void {
  for (const [id, entity] of zones) {
    if (!entity.wall) continue;
    entity.wall.outlineColor = new ConstantProperty(id === zoneId ? Color.fromCssColorString("#E0B15A") : Color.WHITE.withAlpha(0.35));
  }
}

// ---------------------------------------------------------------------------------------------
// Attention boxes: a colored frame around each wall zone that needs attention, with a label that
// says why (high ABC risk, a new or grown crack, a moisture anomaly). Unlike the 4 x 8 grid they are
// always shown, so they read as marks on the building itself (OSM block or our scanned 3D model).

export type AttentionLevel = "watch" | "high" | "critical";

export interface AttentionItem {
  zoneId: string; // B01-W01-R1C3
  level: AttentionLevel;
  reasons: string[]; // short phrases, e.g. "risk high", "crack grown +32%"
}

export const ATTENTION_COLORS: Record<AttentionLevel, string> = {
  watch: "#C9A24D",
  high: "#C07A7A",
  critical: "#8E4B4B",
};

const BOX_OFFSET_M = 0.6; // a little further out than the grid panels, so the frame is never hidden

export interface AttentionLayer {
  update: (items: AttentionItem[]) => void;
  destroy: () => void;
}

export async function createAttentionLayer(viewer: Viewer, wall: WallSpec, buildingId: string): Promise<AttentionLayer> {
  const { start, end } = wall.base_line;
  const ground = await sampleTerrainMostDetailed(viewer.terrainProvider, [
    Cartographic.fromDegrees(start.lon, start.lat),
    Cartographic.fromDegrees(end.lon, end.lat),
  ]).then((points) => Math.min(...points.map((p) => p.height ?? 0)));

  const [sLon, sLat] = offsetLonLat(start.lon, start.lat, BOX_OFFSET_M, wall.azimuth_deg);
  const [eLon, eLat] = offsetLonLat(end.lon, end.lat, BOX_OFFSET_M, wall.azimuth_deg);
  const rowHeight = wall.height_m / wall.rows;
  const prefix = `${buildingId}-${wall.id}-`;
  let entities: Entity[] = [];

  const clear = () => {
    entities.forEach((e) => viewer.entities.remove(e));
    entities = [];
  };

  const update = (items: AttentionItem[]) => {
    clear();
    for (const item of items) {
      const match = item.zoneId.replace(prefix, "").match(/^R(\d+)C(\d+)$/);
      if (!match) continue;
      const r = Number(match[1]);
      const c = Number(match[2]);
      const t0 = (c - 1) / wall.cols + 0.01;
      const t1 = c / wall.cols - 0.01;
      const lo = ground + (r - 1) * rowHeight + 0.08;
      const hi = ground + r * rowHeight - 0.08;
      const lon0 = sLon + (eLon - sLon) * t0;
      const lat0 = sLat + (eLat - sLat) * t0;
      const lon1 = sLon + (eLon - sLon) * t1;
      const lat1 = sLat + (eLat - sLat) * t1;
      const color = Color.fromCssColorString(ATTENTION_COLORS[item.level]);

      entities.push(
        viewer.entities.add({
          id: `attention-${item.zoneId}`,
          name: item.zoneId,
          polyline: {
            positions: Cartesian3.fromDegreesArrayHeights([
              lon0, lat0, lo, lon1, lat1, lo, lon1, lat1, hi, lon0, lat0, hi, lon0, lat0, lo,
            ]),
            width: item.level === "critical" ? 5 : 4,
            material: color,
          },
        }),
        viewer.entities.add({
          id: `attention-label-${item.zoneId}`,
          position: Cartesian3.fromDegrees((lon0 + lon1) / 2, (lat0 + lat1) / 2, hi + 0.35),
          label: {
            text: `${item.zoneId.replace(prefix, "")} · ${item.reasons.join(" · ")}`,
            font: "600 13px sans-serif",
            fillColor: Color.WHITE,
            showBackground: true,
            backgroundColor: color.withAlpha(0.9),
            horizontalOrigin: HorizontalOrigin.CENTER,
            verticalOrigin: VerticalOrigin.BOTTOM,
            disableDepthTestDistance: Number.POSITIVE_INFINITY, // stays readable in front of the 3D model
          },
        }),
      );
    }
  };

  return { update, destroy: clear };
}
