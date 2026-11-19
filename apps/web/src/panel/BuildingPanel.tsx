// Side panel for the selected building or wall zone (shell: Turky; sensors: Saleh; camera: Abdurhmn; risk: Basma; chat: Omar).
import { useState } from "react";

import AgentChat from "../chat/AgentChat";
import type { Selection } from "../map/SiteMap";
import type { LiveState } from "../state/useLive";
import { RISK_COLORS } from "../twin/colors";
import type { RiskLevel } from "../types/contracts";
import CameraFeed from "./CameraFeed";
import EventFeed from "./EventFeed";

interface Props {
  selection: Selection | null;
  live: LiveState;
  hour: number;
  onOpenTwin: () => void;
  onOpenScan: () => void;
}

const LEVEL_ORDER: RiskLevel[] = ["low", "medium", "high", "very_high"];

function Badge({ level }: { level: RiskLevel | null }) {
  if (!level) return <span className="badge muted">No risk yet</span>;
  return (
    <span className="badge" style={{ borderColor: RISK_COLORS[level], color: RISK_COLORS[level] }}>
      Risk: {level.replace("_", " ")}
    </span>
  );
}

export default function BuildingPanel({ selection, live, hour, onOpenTwin, onOpenScan }: Props) {
  const [tab, setTab] = useState<"live" | "agent">("live");

  if (!selection) {
    return (
      <aside className="panel">
        <h2>Hadaq</h2>
        <p className="muted">Click a building on the map. The focus building (B01) has a live twin.</p>
        <EventFeed events={live.events} />
      </aside>
    );
  }

  if (selection.kind === "building" && !selection.isFocus) {
    return (
      <aside className="panel">
        <h2>{selection.name}</h2>
        <p className="muted">No twin yet. OSM id {selection.osmId ?? "unknown"}.</p>
        <p className="muted small">Scaling to more buildings = scan it, register it, attach sensors. Same pipeline.</p>
      </aside>
    );
  }

  const worst = live.risk.reduce<RiskLevel | null>(
    (acc, r) => (acc === null || LEVEL_ORDER.indexOf(r.level) > LEVEL_ORDER.indexOf(acc) ? r.level : acc),
    null,
  );
  const sensors = live.building?.sensors ?? [];
  const zoneId = selection.kind === "zone" ? selection.zoneId : null;
  const zoneRisk = zoneId ? live.risk.find((r) => r.zone_id === zoneId) : undefined;
  const zoneMoisture = zoneId && live.simulation ? live.simulation.zones[zoneId]?.[hour] : undefined;

  return (
    <aside className="panel">
      <div className="panel-head">
        <h2>{zoneId ?? `Building ${live.building?.building.id ?? "B01"}`}</h2>
        <Badge level={zoneId ? zoneRisk?.level ?? null : worst} />
      </div>
      {selection.kind === "building" && selection.osmId !== null && (
        <p className="muted small">OSM id {selection.osmId}{" "}(set VITE_FOCUS_OSM_ID to this to lock the focus building)</p>
      )}
      {zoneId && (
        <div className="zone-facts">
          <div>Simulated moisture now: {zoneMoisture !== undefined ? zoneMoisture.toFixed(2) : "run a simulation"}</div>
          {zoneRisk && (
            <div>
              ABC: A {zoneRisk.A} + B {zoneRisk.B} + C {zoneRisk.C} = {zoneRisk.magnitude} <span className="evidence">{zoneRisk.risk_id}</span>
            </div>
          )}
        </div>
      )}

      <div className="tabs">
        <button className={tab === "live" ? "active" : ""} onClick={() => setTab("live")}>Live</button>
        <button className={tab === "agent" ? "active" : ""} onClick={() => setTab("agent")}>Ask the agent</button>
      </div>

      {tab === "live" ? (
        <>
          <h3>Live sensors</h3>
          <ul className="sensors">
            {sensors
              .filter((s) => !zoneId || s.zone_id === zoneId)
              .map((s) => {
                const r = live.readings[s.id];
                const real = r?.provenance === "measured";
                return (
                  <li key={s.id} className={r?.quality === "fault" ? "fault" : ""}>
                    <span>{s.label}</span>
                    <span className="value">{r ? `${r.value} ${r.unit}` : "no data"}</span>
                    <span className={`tag ${real ? "real" : ""}`}>{r ? (real ? "REAL" : "SIM") : ""}</span>
                  </li>
                );
              })}
          </ul>
          <CameraFeed photo={live.photo} detections={live.detections} />
          <EventFeed events={live.events} />
          <button className="primary" onClick={onOpenTwin}>Open building twin</button>
          <button onClick={onOpenScan}>Real 3D scan</button>
        </>
      ) : (
        <AgentChat buildingId={live.building?.building.id ?? "B01"} />
      )}
    </aside>
  );
}
