import type { RiskLevel } from "../types/contracts";

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
