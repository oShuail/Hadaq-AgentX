// Attention boxes (owner: Omar): which wall zones get a coloured frame on the 3D model and why.
// The frames themselves are drawn by twin3d/focus.ts; twin/attention.ts decides which zones get one.

export type AttentionLevel = "watch" | "high" | "critical";

export interface AttentionItem {
  zoneId: string; // B01-W01-R1C3
  level: AttentionLevel;
  reasons: string[]; // short phrases, e.g. "risk high", "crack grown +32%"
}

export const ATTENTION_COLORS: Record<AttentionLevel, string> = {
  watch: "#C9A24D",
  high: "#C07A7A",
  critical: "#A34A4A",
};
