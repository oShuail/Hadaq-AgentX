// Site clock helpers. Diriyah is on Riyadh time (UTC+3, no daylight saving), whatever the viewer's own zone.
const OFFSET_MS = 3 * 3600_000;
const DAY_MS = 86400_000;

/** Minutes since midnight in Riyadh. */
export function minutesOfDay(d: Date): number {
  return Math.floor((((d.getTime() + OFFSET_MS) % DAY_MS) + DAY_MS) % DAY_MS / 60_000);
}

/** Same Riyadh day, at `minutes` past midnight. */
export function atMinutes(d: Date, minutes: number): Date {
  const local = d.getTime() + OFFSET_MS;
  const midnight = local - (((local % DAY_MS) + DAY_MS) % DAY_MS);
  return new Date(midnight + minutes * 60_000 - OFFSET_MS);
}

/** "YYYY-MM-DD" of the Riyadh day (for <input type="date">). */
export function isoDay(d: Date): string {
  return new Date(d.getTime() + OFFSET_MS).toISOString().slice(0, 10);
}

/** Move to another Riyadh day, keeping the time of day. */
export function onDay(d: Date, day: string): Date {
  const [y, m, dd] = day.split("-").map(Number);
  if (!y || !m || !dd) return d;
  const midnight = Date.UTC(y, m - 1, dd) - OFFSET_MS;
  return new Date(midnight + minutesOfDay(d) * 60_000);
}

export function hhmm(d: Date): string {
  const m = minutesOfDay(d);
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}

/** Today in Riyadh at 16:45: the warm late-afternoon light the twin looks best in. */
export function goldenHourToday(): Date {
  return atMinutes(new Date(), 16 * 60 + 45);
}
