// Lon/lat <-> local metres around an origin (owner: Omar). A local tangent plane is accurate to a few
// centimetres over the 25 km the twin covers, and it keeps all scene coordinates small.

export interface Projection {
  lat0: number;
  lon0: number;
  toLocal: (lon: number, lat: number) => [number, number]; // [east, north] in metres
  toLonLat: (x: number, y: number) => [number, number];
}

export function makeProjection(lat0: number, lon0: number): Projection {
  const phi = (lat0 * Math.PI) / 180;
  // WGS84 metres per degree at this latitude.
  const mLat = 111132.92 - 559.82 * Math.cos(2 * phi) + 1.175 * Math.cos(4 * phi);
  const mLon = 111412.84 * Math.cos(phi) - 93.5 * Math.cos(3 * phi);
  return {
    lat0,
    lon0,
    toLocal: (lon, lat) => [(lon - lon0) * mLon, (lat - lat0) * mLat],
    toLonLat: (x, y) => [lon0 + x / mLon, lat0 + y / mLat],
  };
}
