/** Move a lon/lat point `meters` along a compass bearing (small distances only). */
export function offsetLonLat(lon: number, lat: number, meters: number, bearingDeg: number): [number, number] {
  const rad = (bearingDeg * Math.PI) / 180;
  const dLat = (meters * Math.cos(rad)) / 111_320;
  const dLon = (meters * Math.sin(rad)) / (111_320 * Math.cos((lat * Math.PI) / 180));
  return [lon + dLon, lat + dLat];
}
