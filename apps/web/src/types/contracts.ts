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
  | { channel: "risk"; data: { building_id: string; scores: RiskScore[] } }
  // v2 (inspections), sent by the core API (Spring)
  | { channel: "capture"; data: Capture }
  | { channel: "finding"; data: { capture_id: string; inspection_id: string; count: number; urgent: boolean } }
  | { channel: "review"; data: { finding_id: string; inspection_id: string; review: Review } }
  | { channel: "work_order"; data: WorkOrderDraft }
  | { channel: "inspection"; data: { inspection_id: string; status: InspectionStatus } };

// ---------------------------------------------------------------- v2: inspections
// Mirror of packages/hadaq_schemas/hadaq_schemas/inspection.py. Spec: contracts/README.md "v2".
// Wall coordinates: u = metres from the left end (C1 side), v = metres up from the base.

export const CORE_DAMAGE_TYPES = [
  "plaster_loss",
  "crack",
  "base_erosion",
  "rain_grooves",
  "salt_efflorescence",
  "voids_burrows",
] as const;
export type DamageType =
  | (typeof CORE_DAMAGE_TYPES)[number]
  | "structural_deformation"
  | "incompatible_repair"
  | "parapet_loss"
  | "timber_decay"
  | "biological_growth";

export const DAMAGE_NAMES_AR: Record<DamageType, string> = {
  plaster_loss: "فقد اللياسة",
  crack: "التشققات",
  base_erosion: "تآكل القاعدة",
  rain_grooves: "أخاديد الأمطار",
  salt_efflorescence: "تزهر الأملاح",
  voids_burrows: "الفجوات والجحور",
  structural_deformation: "تشوهات إنشائية",
  incompatible_repair: "ترميم غير متوافق",
  parapet_loss: "فقد الشرفات والزخارف",
  timber_decay: "تلف العناصر الخشبية",
  biological_growth: "النمو الحيوي",
};

export type Severity = 1 | 2 | 3;
export const SEVERITY_NAMES_AR: Record<Severity, string> = { 1: "خفيف", 2: "متوسط", 3: "شديد" };
export type Escalation = "urgent" | "high" | "medium" | "low";
export const ESCALATION_NAMES_AR: Record<Escalation, string> = { urgent: "عاجل", high: "مرتفع", medium: "متوسط", low: "منخفض" };
export const ESCALATION_ORDER: Record<Escalation, number> = { urgent: 0, high: 1, medium: 2, low: 3 };
export type CaptureStatus = "queued" | "uploaded" | "processing" | "analyzed" | "failed";
export type ReviewStatus = "pending" | "accepted" | "corrected" | "rejected";
export type WorkOrderStatus = "draft_needs_field_check" | "approved" | "rejected";
export type InspectionStatus = "open" | "approved";
export type Role = "surveyor" | "conservator" | "site_manager";

export interface WallInfo {
  id: string;
  building_id: string;
  name: string;
  azimuth_deg: number;
  width_m: number;
  height_m: number;
  rows: number;
  cols: number;
  base_line: { start: { lon: number; lat: number }; end: { lon: number; lat: number } };
  main_facade: boolean;
  visitor_route: boolean;
  original_fabric: "original" | "restored" | "mixed" | "unknown";
  provenance: Provenance;
}

export interface BuildingInfo {
  id: string;
  name_ar: string;
  name_en: string;
  lon: number;
  lat: number;
  has_model: boolean;
  walls: WallInfo[];
}

export interface Registry {
  site_id: string;
  buildings: BuildingInfo[];
}

export interface CaptureQuality {
  tilt_ok: boolean | null;
  exposure: "ok" | "dark" | "bright" | null;
  blur_score: number | null;
  steady: boolean | null;
  scale_ref: boolean;
  warnings: string[];
}

export interface CaptureMeta {
  client_uuid: string;
  survey_id: string | null;
  building_id: string;
  wall_id: string;
  segment: string;
  captured_at: string;
  surveyor: string;
  device: string | null;
  source: "camera" | "import";
  gps: { lat: number; lon: number; accuracy_m: number | null } | null;
  heading_deg: number | null;
  tilt_deg: number | null;
  quality: CaptureQuality;
  /** top-left, top-right, bottom-right, bottom-left of the wall in image pixels (left = C1 end). */
  corners_px: [number, number][] | null;
  image_width_px: number | null;
  image_height_px: number | null;
  faces_hidden: number | null;
  provenance: Provenance;
}

export interface Capture extends CaptureMeta {
  capture_id: string;
  status: CaptureStatus;
  inspection_id: string | null;
  file_url: string | null;
  rectified_url: string | null;
  px_per_m: number | null;
  scale: "absolute" | "relative" | null;
  faces_hidden_server: number | null;
  findings_count: number;
  urgent: boolean;
  error: string | null;
  created_at: string;
}

export interface Lookalike {
  pair_id: string;
  options: string[];
  evidence_needed: string[];
  note_ar: string | null;
}

export interface Review {
  status: ReviewStatus;
  by: string | null;
  at: string | null;
  corrected_type: DamageType | null;
  corrected_severity: Severity | null;
  note: string | null;
}

export interface Priority {
  score: number;
  criteria: { safety: number; active: number; water_before_rain: number; severity_x_area: number; value: number; location: number };
  escalation: Escalation;
  reason_ar: string;
  reason_en: string | null;
  method: string;
}

export interface Finding {
  finding_id: string;
  capture_id: string;
  inspection_id: string | null;
  building_id: string;
  wall_id: string;
  zone_id: string;
  damage_type: DamageType;
  severity: Severity;
  confidence: number;
  suspected: boolean;
  box_px: [number, number, number, number] | null;
  /** [u0, v0, u1, v1] in wall metres. */
  box_wall_m: [number, number, number, number];
  polygon_wall_m: [number, number][] | null;
  length_cm: number | null;
  width_mm: number | null;
  area_m2: number | null;
  change: "new" | "grown" | "unchanged" | "repaired" | null;
  previous_finding_id: string | null;
  lookalike: Lookalike | null;
  priority: Priority | null;
  review: Review;
  model: string;
  provenance: Provenance;
  evidence_ids: string[];
}

export interface Citation {
  source: string;
  section: string | null;
  status: "approved_library" | "team_proposal";
}

export interface WorkOrderDraft {
  work_order_id: string;
  inspection_id: string | null;
  finding_ids: string[];
  location: { building_id: string; wall_id: string; zone_ids: string[]; box_wall_m: [number, number, number, number] | null };
  treatment_id: string;
  steps_ar: string[];
  steps_en: string[];
  materials_ar: string[];
  materials_en: string[];
  tools_ar: string[];
  tools_en: string[];
  fix_water_source_first: boolean;
  citation: Citation;
  escalation: Escalation;
  status: WorkOrderStatus;
  created_at: string;
}

export interface Inspection {
  inspection_id: string;
  survey_id: string | null;
  building_id: string;
  wall_id: string;
  started_at: string;
  captures: Capture[];
  findings: Finding[];
  work_orders: WorkOrderDraft[];
  status: InspectionStatus;
  approved_by: string | null;
  approved_at: string | null;
}

export interface ReviewRequest {
  status: "accepted" | "corrected" | "rejected";
  corrected_type?: DamageType | null;
  corrected_severity?: Severity | null;
  note?: string | null;
}

export interface SitePriorityRow {
  building_id: string;
  name_ar: string;
  name_en: string;
  worst_escalation: Escalation | null;
  open_findings: Partial<Record<Escalation, number>>;
  top_reason_ar: string | null;
  last_survey_at: string | null;
}

/** u, v in wall metres -> [row, col], 1-based (R1 = base, C1 = left end). */
export function zoneFromWallM(u: number, v: number, widthM: number, heightM: number, rows = 4, cols = 8): [number, number] {
  const col = Math.min(cols, Math.max(1, Math.floor((u / widthM) * cols) + 1));
  const row = Math.min(rows, Math.max(1, Math.floor((v / heightM) * rows) + 1));
  return [row, col];
}
