// Reading cards pinned to the facade (owner: Omar). Groups the sensors by wall zone and lays the cards out on
// both sides of the building so they do not cover the wall: zones in columns 1 to 4 put their card to the
// left, the rest to the right, stacked from the top row down (all left while the panel is open).
import type { Callout } from "../twin3d/focus";
import type { AttentionItem } from "../twin/WallZones";
import type { SensorReading, SensorSpec } from "../types/contracts";

const shortLabel = (label: string) => label.replace(/\s*\(.*?\)\s*/g, " ").replace(/\s+/g, " ").trim();

const fmt = (v: number) => (Math.abs(v) >= 100 ? v.toFixed(0) : Number(v.toFixed(1)).toString());

export function sensorCallouts(
  sensors: SensorSpec[],
  readings: Record<string, SensorReading>,
  attention: AttentionItem[],
  cols = 8,
  leftOnly = false, // the panel covers the right side of the screen: put every card on the left
): Callout[] {
  const byZone = new Map<string, SensorSpec[]>();
  for (const s of sensors) {
    if (!s.zone_id) continue;
    byZone.set(s.zone_id, [...(byZone.get(s.zone_id) ?? []), s]);
  }

  const zones = [...byZone.keys()].map((zoneId) => {
    const m = zoneId.match(/R(\d+)C(\d+)$/);
    return { zoneId, r: m ? Number(m[1]) : 1, c: m ? Number(m[2]) : 1 };
  });

  const out: Callout[] = [];
  for (const side of ["left", "right"] as const) {
    const mine = zones
      .filter((z) => (leftOnly ? side === "left" : side === "left" ? z.c <= cols / 2 : z.c > cols / 2))
      .sort((a, b) => b.r - a.r || (side === "left" ? a.c - b.c : b.c - a.c));
    // Stack the cards from the top down by their real height so they never overlap.
    let y = leftOnly ? -170 : -140;
    const minC = Math.min(...mine.map((z) => z.c));
    mine.forEach((z) => {
      const att = attention.find((a) => a.zoneId === z.zoneId);
      const rows = byZone.get(z.zoneId)!.map((s) => {
        const r = readings[s.id];
        const real = r ? r.provenance === "measured" : s.source === "real";
        return {
          label: shortLabel(s.label),
          value: r ? fmt(r.value) : "",
          unit: r ? r.unit : "no data",
          tag: (real ? "REAL" : "SIM") as "REAL" | "SIM",
          fault: r?.quality === "fault",
        };
      });
      const h = 30 + rows.length * 23;
      const dy = y + h / 2;
      y += h + 10;
      out.push({
        zoneId: z.zoneId,
        title: `${z.zoneId.replace(/^.*-(R\d+C\d+)$/, "$1")}${att ? ` · ${att.reasons[0]}` : ""}`,
        rows,
        tone: att && att.level !== "watch" ? "alert" : "",
        side,
        dx: leftOnly ? 130 + (z.c - minC) * 40 : 150,
        dy,
      });
    });
  }
  return out;
}
