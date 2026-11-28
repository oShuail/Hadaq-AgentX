// Helpers for the inspection and review page (O4): numbering, effective values, the urgent action text.
import type { DamageType, Escalation, Finding, Inspection, Registry, Severity, WallInfo } from "../types/contracts";
import { DAMAGE_NAMES_AR, ESCALATION_ORDER } from "../types/contracts";

/** English names of the glossary types (Arabic names come from the contract: DAMAGE_NAMES_AR). */
export const DAMAGE_NAMES_EN: Record<DamageType, string> = {
  plaster_loss: "Plaster loss",
  crack: "Crack",
  base_erosion: "Base erosion",
  rain_grooves: "Rain grooves",
  salt_efflorescence: "Salt efflorescence",
  voids_burrows: "Voids and burrows",
  structural_deformation: "Structural deformation",
  incompatible_repair: "Incompatible repair",
  parapet_loss: "Parapet and decoration loss",
  timber_decay: "Timber decay",
  biological_growth: "Biological growth",
};

export const ROLE_NAMES_AR: Record<string, string> = { conservator: "مهندس الترميم", surveyor: "المساح الميداني", site_manager: "مدير الموقع" };
export const roleAr = (user: string | null) => (user ? ROLE_NAMES_AR[user] ?? user : "المختص");

export const REVIEW_NAMES_AR = { pending: "بانتظار المراجعة", accepted: "مقبول", corrected: "مصحح", rejected: "مرفوض" } as const;

// DGDA Q&A, Q14 (proposed risk levels): the action for "عاجل", copied as written.
export const URGENT_ACTION_AR = "إبلاغ فوري، وعزل المنطقة عن الزوار، وتدعيم مؤقت، ومعاينة خلال 24–48 ساعة";
export const URGENT_ACTION_EN = "Notify at once, close the area to visitors, temporary shoring, site inspection within 24 to 48 hours";
// DGDA Q14: the agent classifies and escalates; it does not decide.
export const CLOSING_AR = "القرار النهائي للمختص";

export const effectiveType = (f: Finding): DamageType => f.review.corrected_type ?? f.damage_type;
export const effectiveSeverity = (f: Finding): Severity => f.review.corrected_severity ?? f.severity;
export const shortZone = (zoneId: string) => zoneId.split("-").pop() ?? zoneId;
export const nameAr = (t: DamageType) => DAMAGE_NAMES_AR[t] ?? t;

export interface Numbered {
  n: number; // D1, D2, ... in priority order (the same number on the photo, in the list and in the printed report)
  finding: Finding;
}

const rank = (f: Finding) => (f.priority ? ESCALATION_ORDER[f.priority.escalation] : 9);

/** Urgent first, then by score; the number stays with the finding while the conservator reviews. */
export function numbered(findings: Finding[]): Numbered[] {
  return [...findings]
    .sort((a, b) => rank(a) - rank(b) || (b.priority?.score ?? 0) - (a.priority?.score ?? 0) || a.finding_id.localeCompare(b.finding_id))
    .map((finding, i) => ({ n: i + 1, finding }));
}

/** Urgent findings the conservator has not rejected. */
export const urgentOpen = (findings: Finding[]) =>
  findings.filter((f) => f.priority?.escalation === "urgent" && f.review.status !== "rejected");

export const pendingCount = (inspection: Inspection) => inspection.findings.filter((f) => f.review.status === "pending").length;

export function wallOf(registry: Registry | null, buildingId: string, wallId: string): WallInfo | null {
  const building = registry?.buildings.find((b) => b.id === buildingId);
  return building?.walls.find((w) => w.id === wallId) ?? null;
}

export function buildingName(registry: Registry | null, buildingId: string): { ar: string; en: string } {
  const building = registry?.buildings.find((b) => b.id === buildingId);
  return { ar: building?.name_ar ?? buildingId, en: building?.name_en ?? buildingId };
}

/** The capture whose photo the page shows: the newest analysed one with a rectified image, else the newest. */
export function mainCapture(inspection: Inspection) {
  const byTime = [...inspection.captures].sort((a, b) => b.captured_at.localeCompare(a.captured_at));
  return byTime.find((c) => c.rectified_url && c.status === "analyzed") ?? byTime[0] ?? null;
}

export const worstEscalation = (findings: Finding[]): Escalation | null =>
  findings
    .filter((f) => f.priority && f.review.status !== "rejected")
    .map((f) => f.priority!.escalation)
    .sort((a, b) => ESCALATION_ORDER[a] - ESCALATION_ORDER[b])[0] ?? null;
