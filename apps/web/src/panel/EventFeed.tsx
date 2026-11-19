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

export default function EventFeed({ events }: { events: HadaqEvent[] }) {
  const shown = events.filter((e) => e.type !== "new_photo").slice(0, 6);
  return (
    <div className="events">
      <h3>Activity</h3>
      {shown.length === 0 && <p className="muted small">Nothing yet.</p>}
      <ul>
        {shown.map((e) => (
          <li key={e.event_id} className={e.type}>
            <span className="time">{new Date(e.ts).toLocaleTimeString()}</span> {LABELS[e.type] ?? e.type}
            {e.type === "agent_note" && typeof e.payload.answer === "string" && <div className="note">{e.payload.answer}</div>}
            {e.type === "recommendation_ready" && typeof e.payload.action === "string" && <div className="note">{e.payload.action}</div>}
          </li>
        ))}
      </ul>
    </div>
  );
}
