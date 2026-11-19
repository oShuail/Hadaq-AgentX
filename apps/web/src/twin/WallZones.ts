// Wall zone overlay (owner: Omar): 4 x 8 colored panels drawn just in front of the facade.
import { Cartesian3, Cartographic, Color, ColorMaterialProperty, ConstantProperty, Entity, Viewer, sampleTerrainMostDetailed } from "cesium";

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
