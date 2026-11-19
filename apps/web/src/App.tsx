import { useEffect, useMemo, useState } from "react";

import AgentChat from "./chat/AgentChat";
import { goldenHourToday } from "./clock";
import ScenarioPanel from "./demo/ScenarioPanel";
import SiteMap, { type FlyTarget, type Selection } from "./map/SiteMap";
import BuildingPanel from "./panel/BuildingPanel";
import EventFeed from "./panel/EventFeed";
import ExplorePanel from "./panel/ExplorePanel";
import ScanViewer from "./panel/ScanViewer";
import TimeBar from "./TimeBar";
import { BUILDING_ID } from "./config";
import { useLive } from "./state/useLive";
import { attentionItems } from "./twin/attention";
import { FOCUS_SCAN } from "./twin/scans";
import { LAYERS, type LayerId, type TwinStats } from "./twin3d/Twin";
import type { WallSpec } from "./types/contracts";
import registry from "../../../contracts/building_b01.json";
import { RISK_COLORS, moistureColor } from "./twin/colors";

type Tab = "explore" | "twin" | "agent" | "activity";

const TABS: { id: Tab; label: string }[] = [
  { id: "explore", label: "Explore" },
  { id: "twin", label: "Building twin" },
  { id: "agent", label: "Agent" },
  { id: "activity", label: "Activity" },
];

const TAB_TITLES: Record<Tab, string> = { explore: "Explore", twin: "Building twin", agent: "Ask the agent", activity: "Activity" };

export default function App() {
  const live = useLive(BUILDING_ID);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [fly, setFly] = useState<{ target: FlyTarget; nonce: number }>({ target: "turaif", nonce: 0 });
  const [view, setView] = useState<FlyTarget | null>("turaif");
  const [zonesVisible, setZonesVisible] = useState(false);
  const [hour, setHour] = useState(0);
  const [simPlaying, setSimPlaying] = useState(false);
  const [layer, setLayer] = useState<"moisture" | "risk">("moisture");
  const [coordinate, setCoordinate] = useState<string | null>(null);
  const [scanOpen, setScanOpen] = useState(false);
  const [tab, setTab] = useState<Tab>("explore");
  const [panelOpen, setPanelOpen] = useState(true);
  const [time, setTime] = useState<Date>(goldenHourToday);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(10);
  const [layers, setLayers] = useState<Record<LayerId, boolean>>(
    () => Object.fromEntries(LAYERS.map((l) => [l.id, true])) as Record<LayerId, boolean>,
  );
  const [stats, setStats] = useState<TwinStats | null>(null);

  // Clicking the focus building opens its real 3D scan straight away; Esc or Close goes back to the map.
  const select = (s: Selection | null) => {
    setSelection(s);
    if (!s) return;
    setTab("twin");
    setPanelOpen(true);
    if (s.kind === "building" && s.focusLocked) setScanOpen(true);
  };

  // The wall comes from the API; with the API off ("Offline") the map still uses the registry file directly.
  const wall: WallSpec | null = live.building?.walls[0] ?? (registry.walls[0] as WallSpec);
  const sim = live.simulation;
  const safeHour = sim ? Math.min(hour, sim.times.length - 1) : 0;

  // A new simulation (from the agent or the panel) starts playing on the wall automatically.
  useEffect(() => {
    if (!sim) return;
    setHour(0);
    setZonesVisible(true);
    setSimPlaying(true);
  }, [sim?.run_id]); // eslint-disable-line react-hooks/exhaustive-deps

  // While a simulation plays, the sun follows the simulated hour.
  useEffect(() => {
    const iso = sim?.times[safeHour];
    if (iso) setTime(new Date(iso));
  }, [sim, safeHour]);

  const zoneColors = useMemo(() => {
    if (layer === "risk") return Object.fromEntries(live.risk.map((r) => [r.zone_id, RISK_COLORS[r.level]]));
    if (!sim) return {};
    return Object.fromEntries(Object.entries(sim.zones).map(([zone, values]) => [zone, moistureColor(values[safeHour] ?? 0)]));
  }, [layer, live.risk, sim, safeHour]);

  // Boxes around the wall areas that need attention: high risk, new or grown defects, moisture alerts.
  const attention = useMemo(() => attentionItems(live.risk, live.detections, live.events), [live.risk, live.detections, live.events]);

  const go = (target: FlyTarget) => {
    setView(target);
    setFly((f) => ({ target, nonce: f.nonce + 1 }));
    if (target === "focus") setZonesVisible(true);
  };

  return (
    <div className="app">
      <SiteMap
        buildingId={BUILDING_ID}
        wall={wall}
        zoneColors={zoneColors}
        attention={attention}
        zonesVisible={zonesVisible}
        selectedZone={selection?.kind === "zone" ? selection.zoneId : null}
        fly={fly}
        time={time}
        layers={layers}
        onSelect={select}
        onStats={setStats}
        onCoordinate={(lon, lat, h) => setCoordinate(`lon ${lon.toFixed(6)}, lat ${lat.toFixed(6)}, height ${h.toFixed(1)} m`)}
      />

      <header className="topbar">
        <div className="brand">
          <div className="mark">
            <span className="ar">حَدَق</span>
            <span className="en">HADAQ</span>
          </div>
          <div className="titles">
            <h1>At-Turaif digital twin</h1>
            <p>Mud-wall monitoring model from open data and live sensors</p>
            <p className="note">
              agentX prototype · not affiliated with Diriyah Company · <span dir="rtl">نموذج تجريبي</span>
            </p>
          </div>
        </div>
        <nav className="toptabs">
          {TABS.map((t) => (
            <button
              key={t.id}
              className={tab === t.id && panelOpen ? "on" : ""}
              onClick={() => {
                setTab(t.id);
                setPanelOpen(true);
              }}
            >
              {t.label}
            </button>
          ))}
        </nav>
        <div className="top-right">
          <span className={`conn ${live.connected ? "up" : ""}`}>
            <i />
            {live.connected ? "Live" : "Offline"}
          </span>
          <span className="badge sim">Simulated data</span>
          <button className="icon" onClick={() => setPanelOpen((o) => !o)} aria-label={panelOpen ? "Hide panel" : "Show panel"}>
            <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
              <path d="M3 5h14M3 10h14M3 15h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          </button>
        </div>
      </header>

      {panelOpen && (
        <aside className="float-panel">
          <div className="float-head">
            <h2>{TAB_TITLES[tab]}</h2>
            <span className="ornament" aria-hidden="true">
              <svg viewBox="0 0 64 16" width="64" height="16">
                <path d="M8 2l4 6-4 6-4-6zM32 1v14M25 4l14 8M25 12l14-8M56 2l4 6-4 6-4-6z" stroke="currentColor" strokeWidth="1.4" fill="none" />
              </svg>
            </span>
          </div>
          <div className="float-body">
            {tab === "explore" && (
              <ExplorePanel
                view={view}
                onView={go}
                layers={layers}
                setLayer={(id, on) => setLayers((l) => ({ ...l, [id]: on }))}
                stats={stats}
              />
            )}
            {tab === "twin" && (
              <BuildingPanel selection={selection} live={live} hour={safeHour} onOpenTwin={() => go("focus")} onOpenScan={() => setScanOpen(true)} />
            )}
            {tab === "agent" && <AgentChat buildingId={live.building?.building.id ?? BUILDING_ID} />}
            {tab === "activity" && (
              <div className="activity">
                <h3>Demo controls</h3>
                <ScenarioPanel buildingId={BUILDING_ID} compact />
                <EventFeed events={live.events} />
              </div>
            )}
          </div>
        </aside>
      )}

      <TimeBar
        time={time}
        setTime={setTime}
        playing={playing}
        setPlaying={(p) => {
          setPlaying(p);
          if (p) setSimPlaying(false);
        }}
        speed={speed}
        setSpeed={setSpeed}
        simulation={sim}
        running={live.simulationRunning}
        hour={safeHour}
        setHour={setHour}
        simPlaying={simPlaying}
        setSimPlaying={(p) => {
          setSimPlaying(p);
          if (p) setPlaying(false);
        }}
        layer={layer}
        setLayer={setLayer}
      />

      {scanOpen && <ScanViewer scan={FOCUS_SCAN} onClose={() => setScanOpen(false)} />}

      {coordinate && (
        <div className="toast" onClick={() => setCoordinate(null)} title="Click to close">
          {coordinate}
        </div>
      )}
    </div>
  );
}
