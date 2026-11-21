// Reading cards pinned to the facade (owner: Omar). Groups the sensors by wall zone and lays the cards out on
// both sides of the building so they do not cover the wall: zones in columns 1 to 4 put their card to the
// left, the rest to the right, stacked from the top row down. While the panel is open (it lists every sensor and
// covers the right side) only the zones that need attention get a card, all on the left, at most three.
import type { Callout } from "../twin3d/focus";
import type { AttentionItem } from "../twin/WallZones";
import type { SensorReading, SensorSpec } from "../types/contracts";

const shortLabel = (label: string) => label.replace(/\s*\(.*?\)\s*/g, " ").replace(/\s+/g, " ").trim();

const fmt = (v: number, kind: string) =>
  kind === "tilt" || kind === "crack_width" ? v.toFixed(3) : Math.abs(v) >= 100 ? v.toFixed(0) : Number(v.toFixed(1)).toString();

const BAD = ["fault", "suspect", "stale"];
const MAX_WHEN_PANEL_OPEN = 3;

export function sensorCallouts(
  sensors: SensorSpec[],
  readings: Record<string, SensorReading>,
  attention: AttentionItem[],
  cols = 8,
  leftOnly = false, // the panel is open: only zones that need attention, all on the left
  statusOf?: (sensorId: string) => string, // sensor health (ok, suspect, fault, stale, no_data)
): Callout[] {
  const byZone = new Map<string, SensorSpec[]>();
  for (const s of sensors) {
    if (!s.zone_id) continue;
    byZone.set(s.zone_id, [...(byZone.get(s.zone_id) ?? []), s]);
  }

  let zones = [...byZone.keys()].map((zoneId) => {
    const m = zoneId.match(/R(\d+)C(\d+)$/);
    const att = attention.find((a) => a.zoneId === zoneId);
    const rows = byZone.get(zoneId)!.map((s) => {
      const r = readings[s.id];
      const real = r ? r.provenance === "measured" : s.source === "real";
      return {
        label: shortLabel(s.label),
        value: r ? fmt(r.value, s.kind) : "",
        unit: r ? r.unit : "no data",
        tag: (real ? "REAL" : "SIM") as "REAL" | "SIM",
        fault: statusOf ? BAD.includes(statusOf(s.id)) : r?.quality === "fault",
      };
    });
    const alert = !!att && att.level !== "watch";
    const urgency = (rows.some((r) => r.fault) ? 2 : 0) + (alert ? 1 : 0);
    return { zoneId, r: m ? Number(m[1]) : 1, c: m ? Number(m[2]) : 1, att, alert, rows, urgency };
  });
  if (leftOnly) {
    zones = zones
      .filter((z) => z.urgency > 0)
      .sort((a, b) => b.urgency - a.urgency || a.r - b.r)
      .slice(0, MAX_WHEN_PANEL_OPEN);
  }

  const out: Callout[] = [];
  for (const side of ["left", "right"] as const) {
    const mine = zones
      .filter((z) => (leftOnly ? side === "left" : side === "left" ? z.c <= cols / 2 : z.c > cols / 2))
      .sort((a, b) => b.r - a.r || (side === "left" ? a.c - b.c : b.c - a.c));
    // Stack the cards from the top down by their real height so they never overlap.
    let y = -165;
    const minC = Math.min(...mine.map((z) => z.c));
    for (const z of mine) {
      const h = 30 + z.rows.length * 23;
      const dy = y + h / 2;
      y += h + 10;
      out.push({
        zoneId: z.zoneId,
        title: `${z.zoneId.replace(/^.*-(R\d+C\d+)$/, "$1")}${z.att ? ` · ${z.att.reasons[0]}` : ""}`,
        rows: z.rows,
        tone: z.alert ? "alert" : "",
        side,
        dx: leftOnly ? 130 + (z.c - minC) * 40 : 150,
        dy,
      });
    }
  }
  return out;
}
