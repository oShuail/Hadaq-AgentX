// The site data pack in public/site/ (made by the bake page, read by the 3D twin). Owner: Omar.
// Coordinates are local metres around `origin`: x = east, y = north. Heights are metres relative to the
// ground at the origin. Flat point lists are [x0, y0, x1, y1, ...], rounded to 0.1 m.

export interface LonLatBox {
  s: number;
  n: number;
  w: number;
  e: number;
}

/** A lon/lat box plus the same box in local metres. */
export interface SiteBox extends LonLatBox {
  x0: number;
  x1: number;
  y0: number;
  y1: number;
}

export interface TerrainGrid {
  file: string; // Float32 little-endian, row 0 = north edge, column 0 = west edge
  nx: number;
  ny: number;
  box: "core" | "wide";
  min: number;
  max: number;
}

export interface ImageryTile {
  file: string; // JPEG, resampled so that it is linear in lon/lat over its box (top = north)
  w: number;
  h: number;
  box: "detail" | "core" | "wide";
  zoom: number;
}

export type BuildingKind = "najdi" | "ruin" | "modern" | "landmark";

export interface SiteBuilding {
  id: string; // "w123" (OSM way), "r456" (OSM relation), "m789" (Microsoft footprint), "i12" (ruin infill)
  n?: string; // name (English or default)
  ar?: string; // Arabic name
  h: number; // height above ground, m
  hs: "osm" | "levels" | "estimated";
  k: BuildingKind;
  o: number[]; // outer ring, counter-clockwise, not closed
  holes?: number[][];
}

export interface SiteLine {
  k: string; // highway / waterway class
  w: number; // width, m
  n?: string;
  b?: 1; // bridge: the deck runs straight between its ends
  p: number[];
}

export interface SitePolygon {
  k?: string;
  n?: string;
  ar?: string;
  p: number[];
  holes?: number[][];
}

export interface SitePoi {
  n: string;
  ar?: string;
  k: string; // what it is, e.g. "museum", "historic", "restaurant"
  x: number;
  y: number;
  big?: boolean; // area name (large label) instead of a point of interest
}

export interface SitePack {
  version: 1;
  generated: string;
  origin: { lat: number; lon: number; h_msl: number };
  boxes: { detail: SiteBox; core: SiteBox; wide: SiteBox };
  terrain: { core: TerrainGrid; wide: TerrainGrid };
  imagery: { detail: ImageryTile; core: ImageryTile; wide: ImageryTile };
  palms: { file: string; count: number }; // Float32 [x, y, size] per palm
  buildings: SiteBuilding[];
  roads: SiteLine[];
  paths: SiteLine[];
  waterways: SiteLine[];
  water: SitePolygon[];
  walls: SiteLine[]; // historic city walls
  towers: { x: number; y: number; h: number; r: number }[];
  parking: SitePolygon[];
  helipads: { x: number; y: number }[];
  areas: SitePolygon[]; // named heritage / visitor districts (k = "heritage" | "attraction")
  pois: SitePoi[];
  sources: string[];
}
