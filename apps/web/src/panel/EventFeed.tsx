import type { HadaqEvent } from "../types/contracts";

const LABELS: Record<string, string> = {
  storm_started: "Storm started",
  storm_ended: "Storm ended",
  new_crack: "New crack (scenario)",
  sensor_fault: "Sensor fault (scenario)",
  demo_reset: "Demo reset",
  moisture_anomaly: "Moisture anomaly",
  new_photo: "New photo",
  detection_ready: "Vision finished",
  simulation_done: "Simulation finished",
  recommendation_ready: "Recommendation, waiting for expert approval",
  agent_note: "Agent investigated",
};

// Damage report written by the Damage and Treatment agent (payload.kind === "damage_report").
type ReportItem = { zone_id: string; name_en: string; severity: string | null; active: boolean; priority: string; step_en: string };
type DamageReport = { items: ReportItem[]; first_steps_en: string[]; materials_en: string[]; tools_en: string[] };

function damageReport(e: HadaqEvent): DamageReport | null {
  if (e.type !== "recommendation_ready" || e.payload.kind !== "damage_report") return null;
  const report = e.payload.report as DamageReport | undefined;
  return report && Array.isArray(report.items) ? report : null;
}

// "Vision finished" with nothing new or grown is noise: the camera sends a frame every few seconds.
function worthShowing(e: HadaqEvent): boolean {
  if (e.type === "new_photo") return false;
  return !(e.type === "detection_ready" && Array.isArray(e.payload.changed) && e.payload.changed.length === 0);
}

export default function EventFeed({ events }: { events: HadaqEvent[] }) {
  const shown = events.filter(worthShowing).slice(0, 6);
  return (
    <div className="events">
      <h3>Activity</h3>
      {shown.length === 0 && <p className="muted small">Nothing yet.</p>}
      <ul>
        {shown.map((e) => {
          const report = damageReport(e);
          return (
            <li key={e.event_id} className={e.type}>
              <span className="time">{new Date(e.ts).toLocaleTimeString()}</span>{" "}
              {report ? "Damage report, waiting for expert approval" : LABELS[e.type] ?? e.type}
              {e.type === "agent_note" && typeof e.payload.answer === "string" && <div className="note" dir="auto">{e.payload.answer}</div>}
              {e.type === "recommendation_ready" && !report && typeof e.payload.action === "string" && <div className="note">{e.payload.action}</div>}
              {report && (
                <div className="report">
                  <ul>
                    {report.items.map((item, i) => (
                      <li key={i}>
                        <strong>{item.zone_id.split("-").pop()}</strong> {item.name_en}
                        {item.severity ? ` (${item.severity}${item.active ? ", growing" : ""})` : ""}: {item.step_en}
                      </li>
                    ))}
                  </ul>
                  {report.first_steps_en.length > 0 && <div className="first">Before any repair: {report.first_steps_en.join("; ")}.</div>}
                  <details>
                    <summary>Materials and tools</summary>
                    <div>Materials: {report.materials_en.join(", ")}</div>
                    <div>Tools: {report.tools_en.join(", ")}</div>
                  </details>
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
