import { useEffect, useMemo, useState } from "react";

import BuildingPage from "./building/BuildingPage";
import Brand from "./Brand";

import AgentChat from "./chat/AgentChat";
import { goldenHourToday } from "./clock";
import ScenarioPanel from "./demo/ScenarioPanel";
import SiteMap, { type FlyTarget, type Selection } from "./map/SiteMap";
import BuildingPanel from "./panel/BuildingPanel";
import EventFeed from "./panel/EventFeed";
import ExplorePanel from "./panel/ExplorePanel";
import TimeBar from "./TimeBar";
import { BUILDING_ID } from "./config";
import { useLive } from "./state/useLive";
import { attentionItems } from "./twin/attention";
import { twinById } from "./twins";
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

// Two pages: the map (/) and a building's own twin page (/building/<id>). Plain history API, no router library.
type Route = { page: "map" } | { page: "building"; id: string };
const parseRoute = (path: string): Route => {
  const m = path.match(/^\/building\/([^/]+)\/?$/);
  return m && twinById(decodeURIComponent(m[1])) ? { page: "building", id: decodeURIComponent(m[1]) } : { page: "map" };
};

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
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.pathname));
  const [tab, setTab] = useState<Tab>("explore");
  const [panelOpen, setPanelOpen] = useState(true);
  const [time, setTime] = useState<Date>(goldenHourToday);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(10);
  const [layers, setLayers] = useState<Record<LayerId, boolean>>(
    () => Object.fromEntries(LAYERS.map((l) => [l.id, true])) as Record<LayerId, boolean>,
  );
  const [stats, setStats] = useState<TwinStats | null>(null);

  useEffect(() => {
    const onPop = () => setRoute(parseRoute(window.location.pathname));
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);
  const navigate = (path: string) => {
    if (window.location.pathname !== path) window.history.pushState({ from: "app" }, "", path);
    setRoute(parseRoute(path));
  };
  const openPage = (twinId: string) => navigate(`/building/${encodeURIComponent(twinId)}`);

  // Clicking a building that has a twin page (Qasr Salwa, AL Turaif House) opens that page.
  const select = (s: Selection | null) => {
    setSelection(s);
    if (!s) return;
    setTab("twin");
    setPanelOpen(true);
    if (s.kind === "building" && s.twinId) openPage(s.twinId);
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

  const pageTwin = route.page === "building" ? twinById(route.id) : null;

  return (
    <>
      {pageTwin && (
        <BuildingPage
          twin={pageTwin}
          live={live}
          walls={pageTwin.registered && wall ? [wall] : []}
          zoneColors={pageTwin.registered ? zoneColors : {}}
          attention={pageTwin.registered ? attention : []}
          layer={layer}
          setLayer={setLayer}
          hour={safeHour}
          onBack={() => (window.history.state?.from === "app" ? window.history.back() : navigate("/"))}
        />
      )}
      {/* The map stays loaded (and paused) while a building page is open, so going back is instant. */}
      <div className="app" style={pageTwin ? { display: "none" } : undefined}>
        <SiteMap
          paused={!!pageTwin}
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
          <Brand title="At-Turaif digital twin" subtitle="Mud-wall monitoring model from open data and live sensors" />
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
                <BuildingPanel selection={selection} live={live} hour={safeHour} onFlyToFocus={() => go("focus")} onOpenPage={openPage} />
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

        {coordinate && (
          <div className="toast" onClick={() => setCoordinate(null)} title="Click to close">
            {coordinate}
          </div>
        )}
      </div>
    </>
  );
}
