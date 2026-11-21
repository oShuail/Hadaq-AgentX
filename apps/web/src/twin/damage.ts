// Damage reports from the Damage and Treatment agent (services/agents): types, colours and helpers.
// One colour per damage FAMILY, not per type: on a map any two marks can sit side by side, and only three
// hues stay distinguishable for every pair, colour-blind readers included (checked with a palette validator
// on the dark UI and on the sand-coloured wall photos). The type itself is always written next to the mark.
import type { HadaqEvent } from "../types/contracts";

export type DamageFamily = "cracks" | "loss" | "water" | "other";

export const DAMAGE_FAMILIES: Record<DamageFamily, { label: string; labelAr: string; color: string; dashed: boolean }> = {
  cracks: { label: "Cracks and structure", labelAr: "تشققات وإنشائي", color: "#d95926", dashed: false },
  loss: { label: "Material loss and decay", labelAr: "فقد وتلف المواد", color: "#199e70", dashed: false },
  water: { label: "Water damage", labelAr: "تلف بسبب الماء", color: "#3987e5", dashed: false },
  other: { label: "Past repairs", labelAr: "ترميمات سابقة", color: "#a39d93", dashed: true },
};

export const FAMILY_ORDER: DamageFamily[] = ["cracks", "loss", "water", "other"];

/** Damage type (treatment table id) -> family. Unknown types fall back to "other". */
const FAMILY_OF: Record<string, DamageFamily> = {
  crack: "cracks",
  structural_deformation: "cracks",
  plaster_loss: "loss",
  holes_burrows: "loss",
  parapet_loss: "loss",
  timber_decay: "loss",
  base_erosion: "water",
  rain_gullies: "water",
  salt_efflorescence: "water",
  biological_growth: "water",
  incompatible_repair: "other",
};

export const familyOf = (damageType: string): DamageFamily => FAMILY_OF[damageType] ?? "other";

export type Priority = "low" | "medium" | "high";

export interface DamageItem {
  detection_id: string;
  zone_id: string;
  position_en: string | null;
  detector_class: string | null;
  bbox_xyxy: number[] | null;
  damage_type: string;
  name_en: string;
  name_ar: string;
  needs_review: boolean;
  severity: string | null;
  active: boolean;
  crack_width_mm: number | null;
  priority: Priority;
  step_en: string;
  step_ar: string;
  materials_en: string[];
  materials_ar: string[];
  tools_en: string[];
  tools_ar: string[];
  avoid_en: string[];
  source: string;
  source_label_en: string;
  fix_moisture_source_first: boolean;
  test_on_small_area_first: boolean;
  refer_to_engineer: boolean;
  evidence_ids: string[];
}

export interface DamageReport {
  photo_id: string;
  photo_provenance: string | null;
  detector_model: string | null;
  items: DamageItem[];
  first_steps_en: string[];
  materials_en: string[];
  tools_en: string[];
}

export interface DamageReportEvent {
  eventId: string;
  ts: string;
  priority: Priority;
  status: string;
  report: DamageReport;
}

/** The damage report inside a recommendation_ready event, or null for any other event. */
export function damageReportOf(e: HadaqEvent): DamageReportEvent | null {
  if (e.type !== "recommendation_ready" || e.payload.kind !== "damage_report") return null;
  const report = e.payload.report as DamageReport | undefined;
  if (!report || !Array.isArray(report.items)) return null;
  return {
    eventId: e.event_id,
    ts: e.ts,
    priority: (e.payload.priority as Priority) ?? "medium",
    status: String(e.payload.status ?? ""),
    report: {
      ...report,
      first_steps_en: report.first_steps_en ?? [],
      materials_en: report.materials_en ?? [],
      tools_en: report.tools_en ?? [],
    },
  };
}

const RANK: Record<Priority, number> = { low: 0, medium: 1, high: 2 };

/** Zone -> colour of the family of its most urgent finding (for the wall grid and the 3D wall). */
export function damageZoneColors(report: DamageReport | null): Record<string, string> {
  if (!report) return {};
  const best = new Map<string, DamageItem>();
  for (const item of report.items) {
    const current = best.get(item.zone_id);
    if (!current || RANK[item.priority] > RANK[current.priority]) best.set(item.zone_id, item);
  }
  return Object.fromEntries([...best].map(([zone, item]) => [zone, DAMAGE_FAMILIES[familyOf(item.damage_type)].color]));
}
