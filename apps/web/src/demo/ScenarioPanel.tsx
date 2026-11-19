// Scenario panel (owner: Saleh). Buttons publish the same events a real trigger would.
// Open on a phone during the demo: http://<laptop-ip>:5173/?panel=1
import { useState } from "react";

import { api } from "../api/client";

const ACTIONS: { type: string; label: string; payload?: Record<string, unknown> }[] = [
  { type: "storm_started", label: "Start storm", payload: { peak_rain_mm_h: 12, wind_ms: 8, wind_dir_deg: 270 } },
  { type: "storm_ended", label: "Stop storm" },
  { type: "new_crack", label: "New crack" },
  { type: "sensor_fault", label: "Sensor fault" },
  { type: "demo_reset", label: "Reset demo" },
];

export default function ScenarioPanel({ buildingId, compact = false }: { buildingId: string; compact?: boolean }) {
  const [status, setStatus] = useState("");

  const fire = async (type: string, payload?: Record<string, unknown>) => {
    try {
      await api.postEvent({ type, building_id: buildingId, payload: payload ?? {} });
      setStatus(`${type} sent`);
    } catch (err) {
      setStatus(`Failed: ${String(err)}`);
    }
  };

  const simulate = async () => {
    try {
      const { run_id } = await api.runSimulation(buildingId);
      setStatus(`Simulation ${run_id} running`);
    } catch (err) {
      setStatus(`Failed: ${String(err)}`);
    }
  };

  return (
    <div className={compact ? "scenario compact" : "scenario"}>
      {!compact && <h2>Hadaq demo controls</h2>}
      {ACTIONS.map((a) => (
        <button key={a.type} className={a.type === "storm_started" ? "warn" : ""} onClick={() => fire(a.type, a.payload)}>
          {a.label}
        </button>
      ))}
      <button onClick={simulate}>Run simulation</button>
      {!compact && <p className="muted small">{status}</p>}
    </div>
  );
}
