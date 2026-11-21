import type { RiskLevel, SimulationResult } from "../types/contracts";

/** What the wall zones are coloured by, on the map and on the building page. */
export type WallLayer = "moisture" | "risk" | "damage";

// Muted palette, readable on the dark UI.
export const RISK_COLORS: Record<RiskLevel, string> = {
  low: "#6B9E78",
  medium: "#C9A24D",
  high: "#C07A7A",
  very_high: "#8E4B4B",
};

const DRY = [217, 195, 160]; // sand
const WET = [94, 124, 140]; // slate

/** Moisture fraction 0..1 to a color between dry sand and wet slate. */
export function moistureColor(value: number): string {
  const t = Math.min(1, Math.max(0, (value - 0.15) / 0.75));
  const c = DRY.map((d, i) => Math.round(d + (WET[i] - d) * t));
  return `rgb(${c[0]}, ${c[1]}, ${c[2]})`;
}

/** A zone's highest simulated moisture in a run (0 to 1), or undefined when the run has no such zone. */
export function peakMoisture(sim: SimulationResult | null, zone: string): number | undefined {
  const values = sim?.zones[zone];
  return values && values.length ? Math.max(...values) : undefined;
}
