import { Cartographic, type Cesium3DTileset, type Viewer, sampleTerrainMostDetailed } from "cesium";

import { GOOGLE_3D } from "../config";

import type { WallSpec } from "../types/contracts";

/** Move a lon/lat point `meters` along a compass bearing (small distances only). */
export function offsetLonLat(lon: number, lat: number, meters: number, bearingDeg: number): [number, number] {
  const rad = (bearingDeg * Math.PI) / 180;
  const dLat = (meters * Math.cos(rad)) / 111_320;
  const dLon = (meters * Math.sin(rad)) / (111_320 * Math.cos((lat * Math.PI) / 180));
  return [lon + dLon, lat + dLat];
}

// Per viewer: React dev mode creates two viewers, and a lookup started on the destroyed one must not be reused.
const groundCaches = new WeakMap<Viewer, Map<string, Promise<number>>>();

/** Terrain height under a point, shared by the focus model, the zone grid and the attention boxes so they always agree.
 *  Falls back to 0 when there is no terrain (no ion token / offline). */
export function groundAt(viewer: Viewer, lon: number, lat: number): Promise<number> {
  const key = `${lon.toFixed(7)},${lat.toFixed(7)}`;
  let groundCache = groundCaches.get(viewer);
  if (!groundCache) groundCaches.set(viewer, (groundCache = new Map()));
  let height = groundCache.get(key);
  if (!height) {
    height = surfaceHeight(viewer, lon, lat).catch((err) => {
      console.warn("No ground height here; using 0", err);
      groundCaches.get(viewer)?.delete(key);
      return 0;
    });
    groundCache.set(key, height);
  }
  return height;
}

// With Google 3D on, the ground people see is Google's mesh, not Cesium's terrain (they differ by metres),
// so heights come from the mesh itself. The map resolves this once Google loads (or with null if it fails).
let resolveSurface: (tiles: Cesium3DTileset | null) => void = () => {};
const surfaceTiles = new Promise<Cesium3DTileset | null>((resolve) => (resolveSurface = resolve));
export function setSurfaceTiles(tiles: Cesium3DTileset | null): void {
  resolveSurface(tiles);
}

async function surfaceHeight(viewer: Viewer, lon: number, lat: number): Promise<number> {
  const tiles = GOOGLE_3D ? await surfaceTiles : null;
  if (tiles && !viewer.isDestroyed() && viewer.scene.sampleHeightSupported) {
    const [onMesh] = await viewer.scene.sampleHeightMostDetailed([Cartographic.fromDegrees(lon, lat)]);
    if (onMesh?.height !== undefined) return onMesh.height;
  }
  const [onTerrain] = await sampleTerrainMostDetailed(viewer.terrainProvider, [Cartographic.fromDegrees(lon, lat)]);
  return onTerrain.height ?? 0;
}

/** Ground under the middle of a wall's base: the height the focus model, zones and boxes all stand on. */
export function wallGround(viewer: Viewer, wall: WallSpec): Promise<number> {
  const { start, end } = wall.base_line;
  return groundAt(viewer, (start.lon + end.lon) / 2, (start.lat + end.lat) / 2);
}
