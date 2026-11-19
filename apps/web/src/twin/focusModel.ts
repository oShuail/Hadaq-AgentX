// Place our own 3D model (phone scan / Blender / downloaded) of the focus building at its real coordinates (owner: Omar).
// How the GLB must be set up (checked against Cesium's axis handling for glTF 2.0):
//   origin  = middle of the main wall's base, on the ground
//   +Y      = up
//   +X      = straight out of the facade (headingDeg = the wall's azimuth_deg turns it to face the right way)
//   +Z      = along the wall towards base_line.start (column C1, the LEFT end when you face the facade)
// Blender's glTF exporter with "+Y Up" maps Blender (x, y, z) to glTF (x, z, -y): so in Blender, face the facade towards +X.
import {
  Cartesian3,
  ClippingPolygon,
  ClippingPolygonCollection,
  type Cesium3DTileset,
  HeadingPitchRoll,
  Math as CesiumMath,
  Model,
  Transforms,
  Viewer,
} from "cesium";

import type { WallSpec } from "../types/contracts";
import { groundAt } from "./geo";

export async function placeFocusModel(viewer: Viewer, url: string, lon: number, lat: number, headingDeg = 0): Promise<Model> {
  const groundHeight = await groundAt(viewer, lon, lat); // same height the zone grid uses
  const position = Cartesian3.fromDegrees(lon, lat, groundHeight);
  const modelMatrix = Transforms.headingPitchRollToFixedFrame(position, new HeadingPitchRoll(CesiumMath.toRadians(headingDeg), 0, 0));
  const model = await Model.fromGltfAsync({ url, modelMatrix });
  if (viewer.isDestroyed()) {
    model.destroy();
    throw new Error("viewer was destroyed while the model loaded");
  }
  viewer.scene.primitives.add(model);
  return model;
}

/** Cut Google's mesh away under our model, following the model's own footprint (made by the model prep step,
 *  e.g. /models/b01.footprint.json next to /models/b01.glb), so the hole hides under the model's walls. */
export async function clipUnderFootprint(tiles: Cesium3DTileset, modelUrl: string, wall: WallSpec): Promise<void> {
  const res = await fetch(modelUrl.replace(/\.glb$/i, ".footprint.json"));
  if (!res.ok || !(res.headers.get("content-type") ?? "").includes("json")) return;
  const { local_m } = (await res.json()) as { local_m: [number, number][] };
  const { start, end } = wall.base_line;
  const lon0 = (start.lon + end.lon) / 2;
  const lat0 = (start.lat + end.lat) / 2;
  const az = CesiumMath.toRadians(wall.azimuth_deg);
  const left = az + Math.PI / 2; // model +Z points to base_line.start
  const ring = local_m.map(([x, z]) => {
    const east = x * Math.sin(az) + z * Math.sin(left);
    const north = x * Math.cos(az) + z * Math.cos(left);
    const lat = lat0 + CesiumMath.toDegrees(north / 6378137);
    const lon = lon0 + CesiumMath.toDegrees(east / (6378137 * Math.cos(CesiumMath.toRadians(lat0))));
    return Cartesian3.fromDegrees(lon, lat);
  });
  tiles.clippingPolygons = new ClippingPolygonCollection({ polygons: [new ClippingPolygon({ positions: ring })] });
}
