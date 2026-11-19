// Place our own 3D model (phone scan / Blender) of the focus building at its real coordinates (owner: Omar).
import { Cartesian3, Cartographic, HeadingPitchRoll, Math as CesiumMath, Model, Transforms, Viewer, sampleTerrainMostDetailed } from "cesium";

export async function placeFocusModel(viewer: Viewer, url: string, lon: number, lat: number, headingDeg = 0): Promise<Model> {
  const [ground] = await sampleTerrainMostDetailed(viewer.terrainProvider, [Cartographic.fromDegrees(lon, lat)]);
  const position = Cartesian3.fromDegrees(lon, lat, ground.height ?? 0);
  const modelMatrix = Transforms.headingPitchRollToFixedFrame(position, new HeadingPitchRoll(CesiumMath.toRadians(headingDeg), 0, 0));
  const model = await Model.fromGltfAsync({ url, modelMatrix });
  viewer.scene.primitives.add(model);
  return model;
}
