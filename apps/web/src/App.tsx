import { useEffect, useMemo, useState } from "react";

import ScenarioPanel from "./demo/ScenarioPanel";
import SiteMap, { type FlyTarget, type Selection } from "./map/SiteMap";
import BuildingPanel from "./panel/BuildingPanel";
import TimeBar from "./TimeBar";
import { BUILDING_ID } from "./config";
import { useLive } from "./state/useLive";
import { attentionItems } from "./twin/attention";
import { RISK_COLORS, moistureColor } from "./twin/colors";

export default function App() {
  const live = useLive(BUILDING_ID);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [fly, setFly] = useState<{ target: FlyTarget; nonce: number }>({ target: "overview", nonce: 0 });
  const [zonesVisible, setZonesVisible] = useState(false);
  const [hour, setHour] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [layer, setLayer] = useState<"moisture" | "risk">("moisture");
  const [coordinate, setCoordinate] = useState<string | null>(null);

  const wall = live.building?.walls[0] ?? null;
  const sim = live.simulation;
  const safeHour = sim ? Math.min(hour, sim.times.length - 1) : 0;

  // A new simulation (from the agent or the panel) starts playing on the wall automatically.
  useEffect(() => {
    if (!sim) return;
    setHour(0);
    setZonesVisible(true);
    setPlaying(true);
  }, [sim?.run_id]); // eslint-disable-line react-hooks/exhaustive-deps

  const zoneColors = useMemo(() => {
    if (layer === "risk") return Object.fromEntries(live.risk.map((r) => [r.zone_id, RISK_COLORS[r.level]]));
    if (!sim) return {};
    return Object.fromEntries(Object.entries(sim.zones).map(([zone, values]) => [zone, moistureColor(values[safeHour] ?? 0)]));
  }, [layer, live.risk, sim, safeHour]);

  // Boxes around the wall areas that need attention: high risk, new or grown defects, moisture alerts.
  const attention = useMemo(() => attentionItems(live.risk, live.detections, live.events), [live.risk, live.detections, live.events]);

  const go = (target: FlyTarget) => setFly((f) => ({ target, nonce: f.nonce + 1 }));
  const openTwin = () => {
    setZonesVisible(true);
    go("focus");
  };

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="ar">حَدَق</span>
          <strong>Hadaq</strong>
          <span className="tagline">A digital eye that detects the finest details</span>
        </div>
        <nav className="presets">
          <button onClick={() => go("overview")}>Overview</button>
          <button onClick={() => go("turaif")}>At-Turaif</button>
          <button onClick={openTwin}>Focus building</button>
        </nav>
        <span className={`conn ${live.connected ? "up" : ""}`}>{live.connected ? "Live" : "Offline"}</span>
        <span className="badge sim">Simulated data</span>
      </header>

      <main className="main">
        <SiteMap
          buildingId={BUILDING_ID}
          wall={wall}
          zoneColors={zoneColors}
          attention={attention}
          zonesVisible={zonesVisible}
          selectedZone={selection?.kind === "zone" ? selection.zoneId : null}
          fly={fly}
          clockIso={sim?.times[safeHour] ?? null}
          onSelect={setSelection}
          onCoordinate={(lon, lat, h) => setCoordinate(`lon ${lon.toFixed(6)}, lat ${lat.toFixed(6)}, height ${h.toFixed(1)} m`)}
        />
        <BuildingPanel selection={selection} live={live} hour={safeHour} onOpenTwin={openTwin} />
      </main>

      <TimeBar
        simulation={sim}
        running={live.simulationRunning}
        hour={safeHour}
        setHour={setHour}
        playing={playing}
        setPlaying={setPlaying}
        layer={layer}
        setLayer={setLayer}
      >
        <ScenarioPanel buildingId={BUILDING_ID} compact />
      </TimeBar>

      {coordinate && (
        <div className="toast" onClick={() => setCoordinate(null)} title="Click to close">
          {coordinate}
        </div>
      )}
    </div>
  );
}
