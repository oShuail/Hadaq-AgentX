export const DEMO_DURATION = 120;

export type DemoPhase = "storm" | "drying" | "fault" | "crack" | "recovery" | "complete";

export function phaseAt(seconds: number): DemoPhase {
  if (seconds >= DEMO_DURATION) return "complete";
  if (seconds >= 105) return "recovery";
  if (seconds >= 75) return "crack";
  if (seconds >= 60) return "fault";
  if (seconds >= 35) return "drying";
  return "storm";
}
