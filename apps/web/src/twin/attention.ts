// Which wall areas need attention, and why (owner: Omar). Pure function: easy to tune and test.
import type { Detection, HadaqEvent, RiskScore } from "../types/contracts";
import type { AttentionItem, AttentionLevel } from "./WallZones";

const RANK: Record<AttentionLevel, number> = { watch: 1, high: 2, critical: 3 };

/**
 * A zone gets a box when any of these is true:
 * - ABC risk is high (box "high") or very high (box "critical")
 * - the camera saw a new or grown defect there (at least "high"; growth over 25% makes it "critical")
 * - a moisture anomaly fired on a sensor in that zone since the last demo reset ("watch")
 * `events` must be newest first, as the API returns them.
 */
export function attentionItems(risk: RiskScore[], detections: Detection[], events: HadaqEvent[]): AttentionItem[] {
  const items = new Map<string, AttentionItem>();
  const raise = (zoneId: string, level: AttentionLevel, reason: string) => {
    const item = items.get(zoneId) ?? { zoneId, level, reasons: [] };
    if (RANK[level] > RANK[item.level]) item.level = level;
    if (!item.reasons.includes(reason)) item.reasons.push(reason);
    items.set(zoneId, item);
  };

  for (const r of risk) {
    if (r.level === "very_high") raise(r.zone_id, "critical", "risk very high");
    else if (r.level === "high") raise(r.zone_id, "high", "risk high");
  }

  for (const d of detections) {
    if (d.change !== "new" && d.change !== "grown") continue;
    const what = d.cls.replace("_", " ");
    const growth = d.growth_pct != null ? ` +${Math.round(d.growth_pct)}%` : "";
    const level: AttentionLevel = d.change === "grown" && (d.growth_pct ?? 0) > 25 ? "critical" : "high";
    raise(d.zone_id, level, d.change === "new" ? `new ${what}` : `${what} grown${growth}`);
  }

  for (const e of events) {
    if (e.type === "demo_reset") break;
    const zone = e.payload?.zone_id;
    if (e.type === "moisture_anomaly" && typeof zone === "string") raise(zone, "watch", "moisture rising");
  }

  // Most urgent first, at most 6 boxes so the wall stays readable on video.
  return [...items.values()].sort((a, b) => RANK[b.level] - RANK[a.level]).slice(0, 6);
}
