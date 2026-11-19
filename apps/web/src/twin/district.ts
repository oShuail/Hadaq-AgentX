// Our own 3D model of the At-Turaif district (owner: Omar).
// Built by tools/turaif_model/build_district.py from OpenStreetMap outlines: one GLB, one glTF node per building
// (named "w<way id>" or "r<relation id>"), plus a JSON sidecar with each building's name, height and outline.
import {
  Cartesian3,
  Cartographic,
  Color,
  Entity,
  Matrix4,
  Model,
  Transforms,
  type Viewer,
  sampleTerrainMostDetailed,
} from "cesium";

export interface DistrictBuilding {
  node: string;
  osm_type: string;
  osm_id: number;
  name: string;
  name_en: string;
  height_m: number;
  height_source: "osm" | "estimated";
  area_m2: number;
  centroid: { lon: number; lat: number };
  outline: [number, number][];
}

interface DistrictMeta {
  origin: { lon: number; lat: number };
  buildings: DistrictBuilding[];
}

export interface District {
  model: Model;
  byNode: Map<string, DistrictBuilding>;
  byOsmId: Map<number, DistrictBuilding>;
  ground: Map<string, number>; // terrain height under each building (node name -> m)
  bbox: { west: number; south: number; east: number; north: number };
}

/** Load the district GLB, or return null if it has not been generated yet (the app then falls back to OSM boxes). */
export async function loadDistrict(viewer: Viewer, glbUrl: string): Promise<District | null> {
  const res = await fetch(glbUrl.replace(/\.glb$/i, ".json"));
  if (!res.ok || !(res.headers.get("content-type") ?? "").includes("json")) return null;
  const meta = (await res.json()) as DistrictMeta;

  // One terrain query for the origin and every building, so each building stands on its own ground (At-Turaif slopes to the wadi).
  const points = [meta.origin, ...meta.buildings.map((b) => b.centroid)].map((p) => Cartographic.fromDegrees(p.lon, p.lat));
  let heights = points.map(() => 0);
  try {
    heights = (await sampleTerrainMostDetailed(viewer.terrainProvider, points)).map((c) => c.height ?? 0);
  } catch {
    // No terrain (no ion token): everything stays on the ellipsoid, still usable.
  }

  if (viewer.isDestroyed()) return null; // React dev mode mounts the map twice
  const origin = Cartesian3.fromDegrees(meta.origin.lon, meta.origin.lat, heights[0]);
  const model = await Model.fromGltfAsync({ url: glbUrl, modelMatrix: Transforms.eastNorthUpToFixedFrame(origin) });
  if (viewer.isDestroyed()) {
    model.destroy();
    return null;
  }
  viewer.scene.primitives.add(model);
  await new Promise<void>((resolve) => {
    if (model.ready) resolve();
    else model.readyEvent.addEventListener(() => resolve());
  });

  meta.buildings.forEach((b, i) => {
    const node = model.getNode(b.node);
    // glTF is Y-up, so lifting a building is a translation along Y in the model's own frame.
    if (node) node.matrix = Matrix4.fromTranslation(new Cartesian3(0, heights[i + 1] - heights[0], 0));
  });

  const lons = meta.buildings.flatMap((b) => b.outline.map((p) => p[0]));
  const lats = meta.buildings.flatMap((b) => b.outline.map((p) => p[1]));
  const pad = 0.0001; // about 10 m
  return {
    model,
    byNode: new Map(meta.buildings.map((b) => [b.node, b])),
    byOsmId: new Map(meta.buildings.map((b) => [b.osm_id, b])),
    ground: new Map(meta.buildings.map((b, i) => [b.node, heights[i + 1]])),
    bbox: { west: Math.min(...lons) - pad, south: Math.min(...lats) - pad, east: Math.max(...lons) + pad, north: Math.max(...lats) + pad },
  };
}

/** The district building under a scene.pick() result, if any. */
export function pickedDistrictBuilding(district: District | null, picked: unknown): DistrictBuilding | null {
  if (!district || !picked || typeof picked !== "object") return null;
  const p = picked as { primitive?: unknown; detail?: { node?: { node?: { name?: string }; name?: string } } };
  if (p.primitive !== district.model) return null;
  const name = p.detail?.node?.node?.name ?? p.detail?.node?.name;
  return name ? district.byNode.get(name) ?? null : null;
}

/** Hide (or show again) one building of the district, e.g. the one our own focus model replaces. */
export function setBuildingVisible(district: District, osmId: number, visible: boolean): void {
  const b = district.byOsmId.get(osmId);
  const node = b ? district.model.getNode(b.node) : undefined;
  if (node) node.show = visible;
}

/** Gold outline along the top of a selected building's walls. */
export function outlineBuilding(viewer: Viewer, district: District, b: DistrictBuilding, color: Color): Entity {
  const top = (district.ground.get(b.node) ?? 0) + b.height_m + 0.3;
  const ring = [...b.outline, b.outline[0]].flatMap(([lon, lat]) => [lon, lat, top]);
  return viewer.entities.add({
    polyline: { positions: Cartesian3.fromDegreesArrayHeights(ring), width: 4, material: color, depthFailMaterial: color.withAlpha(0.35) },
  });
}

/** Cesium OSM Buildings style condition that hides the grey boxes inside the district, where our model stands. */
export function outsideDistrictCondition(d: District): string {
  const lat = "${feature['cesium#latitude']}";
  const lon = "${feature['cesium#longitude']}";
  return `!(${lat} > ${d.bbox.south} && ${lat} < ${d.bbox.north} && ${lon} > ${d.bbox.west} && ${lon} < ${d.bbox.east})`;
}
