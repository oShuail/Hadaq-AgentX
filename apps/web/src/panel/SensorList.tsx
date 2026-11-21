// Live sensor rows (owner: Saleh): label, a sparkline of the recent values, the latest value, its health when it
// is not "ok" (stale, suspect, fault: hover for the reason) and the REAL / SIM tag. Used by the Building twin panel
// and the building page.
import { type LiveState, sensorHealth } from "../state/useLive";
import type { SensorSpec } from "../types/contracts";

interface Props {
  sensors: SensorSpec[];
  live: LiveState;
  showZone?: boolean;
}

const STATUS_LABEL: Record<string, string> = { stale: "stale", suspect: "check", fault: "fault" };

export default function SensorList({ sensors, live, showZone = false }: Props) {
  return (
    <ul className="sensors">
      {sensors.map((s) => {
        const r = live.readings[s.id];
        const real = r?.provenance === "measured";
        const h = sensorHealth(live, s.id);
        const bad = h.status === "fault" || h.status === "suspect" || h.status === "stale";
        return (
          <li key={s.id} className={bad ? `bad ${h.status}` : ""} title={h.reasons.join("; ") || undefined}>
            <span className="label">
              {s.label}
              {showZone && <span className="muted small"> {s.zone_id.split("-").slice(-1)[0]}</span>}
            </span>
            <Sparkline values={live.trends[s.id] ?? []} />
            <span className="value">
              {r ? `${r.value} ${r.unit}` : "no data"}
              {bad && <span className={`health ${h.status}`}>{STATUS_LABEL[h.status]}</span>}
            </span>
            <span className={`tag ${real ? "real" : ""}`}>{r ? (real ? "REAL" : "SIM") : ""}</span>
          </li>
        );
      })}
    </ul>
  );
}

function Sparkline({ values }: { values: number[] }) {
  const w = 56;
  const h = 16;
  if (values.length < 2) return <svg className="spark" width={w} height={h} aria-hidden="true" />;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;
  const pts = values.map((v, i) => `${((i / (values.length - 1)) * (w - 2) + 1).toFixed(1)},${(h - 2 - ((v - lo) / span) * (h - 4)).toFixed(1)}`);
  const rising = values[values.length - 1] - values[0] > span * 0.25;
  return (
    <svg className={`spark ${rising ? "up" : ""}`} width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden="true">
      <polyline points={pts.join(" ")} fill="none" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
