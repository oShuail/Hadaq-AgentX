// TypeScript mirror of packages/hadaq_schemas/hadaq_schemas/models.py. Change both together.

export type Provenance = "measured" | "derived" | "model_output" | "assumed" | "synthetic";
export type RiskLevel = "low" | "medium" | "high" | "very_high";

export interface SensorSpec {
  id: string;
  kind: string;
  unit: string;
  zone_id: string;
  device_id: string;
  source: "real" | "sim";
  label: string;
}

export interface WallSpec {
  id: string;
  building_id: string;
  name: string;
  azimuth_deg: number;
  height_m: number;
  rows: number;
  cols: number;
  base_line: { start: { lon: number; lat: number }; end: { lon: number; lat: number } };
}

export interface BuildingRegistry {
  site: { id: string; name: string; center: { lon: number; lat: number } };
  building: { id: string; name: string; osm_element_id: number | null; lon: number | null; lat: number | null };
  walls: WallSpec[];
  sensors: SensorSpec[];
}

export interface SensorReading {
  sensor_id: string;
  building_id: string;
  zone_id: string;
  kind: string;
  value: number;
  unit: string;
  ts: string;
  provenance: Provenance;
  device_id?: string | null;
  quality: "ok" | "suspect" | "fault";
  evidence_id?: string;
  /** Added by the API on the live stream: the sensor's health after this reading. */
  status?: SensorStatus;
  reasons?: string[];
}

/** ok, suspect (implausible or stuck), fault (device says so), stale (silent too long), no_data. */
export type SensorStatus = "ok" | "suspect" | "fault" | "stale" | "no_data";

/** GET /buildings/{id}/sensors (one entry per registered sensor). */
export interface SensorOverview {
  sensor_id: string;
  label: string | null;
  kind: string;
  unit: string;
  zone_id: string;
  device_id: string | null;
  status: SensorStatus;
  reasons: string[];
  latest: { value: number; ts: string; age_s: number; quality: string; provenance: Provenance; evidence_id: string } | null;
  window: {
    readings: number;
    first: number;
    last: number;
    min: number;
    max: number;
    change: number;
    rate_per_min: number;
    trend: "rising" | "falling" | "steady";
  } | null;
}

export interface HadaqEvent {
  event_id: string;
  type: string;
  building_id: string;
  ts: string;
  source: string;
  payload: Record<string, unknown>;
  provenance: Provenance;
}

export interface Photo {
  photo_id: string;
  camera_id: string;
  building_id: string;
  captured_at: string;
  provenance: Provenance;
  file_url: string | null;
  sequence: string | null;
  frame: number | null;
  status: "new" | "processed";
}

export interface Detection {
  detection_id: string;
  photo_id: string;
  camera_id: string;
  zone_id: string;
  cls: string;
  confidence: number;
  bbox_xyxy: [number, number, number, number];
  provenance: Provenance;
  change: "new" | "grown" | "unchanged" | "repaired" | null;
  growth_pct: number | null;
}

export interface ZoneSummary {
  peak_moisture: number;
  hours_above_threshold: number;
  erosion_index: number;
}

export interface SimulationResult {
  run_id: string;
  scenario: string;
  engine: string;
  provenance: Provenance;
  times: string[];
  zones: Record<string, number[]>;
  summary: Record<string, ZoneSummary>;
  threshold: number;
  assumptions: string[];
  weather_source: string;
}

export interface RiskScore {
  risk_id: string;
  zone_id: string;
  level: RiskLevel;
  magnitude: number;
  A: number;
  B: number;
  C: number;
  evidence_ids: string[];
}

export interface ChatResponse {
  answer: string;
  evidence_ids: string[];
  tool_calls: { name: string; args: Record<string, unknown> }[];
  needs_approval: boolean;
  unverified_numbers: string[];
}

export type LiveMessage =
  | { channel: "reading"; data: SensorReading }
  | { channel: "event"; data: HadaqEvent }
  | { channel: "photo"; data: Photo }
  | { channel: "detection"; data: { photo_id: string; camera_id: string; detections: Detection[] } }
  | { channel: "simulation"; data: { run_id: string; status: string; result?: SimulationResult; error?: string } }
  | { channel: "risk"; data: { building_id: string; scores: RiskScore[] } };
