import { useEffect, useMemo, useState } from "react";

import BuildingPage from "./building/BuildingPage";
import InspectionLinks from "./inspection/InspectionLinks";
import InspectionPage from "./inspection/InspectionPage";

import AgentChat from "./chat/AgentChat";
import { goldenHourToday } from "./clock";
import ScenarioPanel from "./demo/ScenarioPanel";
import SensorDemoPanel from "./demo/SensorDemoPanel";
import { useSensorDemo } from "./demo/useSensorDemo";
import SiteMap, { type FlyTarget, type Selection } from "./map/SiteMap";
import BuildingPanel from "./panel/BuildingPanel";
import EventFeed from "./panel/EventFeed";
import BrandTower, { type Tab } from "./hud/BrandTower";
import { sensorCallouts } from "./hud/callouts";
import LayersMenu from "./hud/LayersMenu";
import ViewsTower from "./hud/ViewsTower";
import PriorityPanel from "./hud/PriorityPanel";
import { useSitePriority } from "./state/useSitePriority";
import TimeBar from "./TimeBar";
import { BUILDING_ID } from "./config";
import { sensorHealth, useLive } from "./state/useLive";
import { attentionItems } from "./twin/attention";
import { twinById } from "./twins";
import { LAYERS, type LayerId, type TwinStats, type ViewId } from "./twin3d/Twin";
import type { SensorSpec, WallSpec } from "./types/contracts";
import registry from "../../../contracts/building_b01.json";
import { RISK_COLORS, type WallLayer, moistureColor, peakMoisture } from "./twin/colors";
import { damageZoneColors } from "./twin/damage";

// Where the wall face sits in the fixed camera's photo (registry camera entry), for the zone grid on the damage map.
const CAMERA_WALL_BOX: number[] | null = (registry as { cameras?: { wall_bbox_px?: number[] }[] }).cameras?.[0]?.wall_bbox_px ?? null;

// Explore = the map alone (views bottom left, layers top right); the other tabs open the panel bottom right.
const TAB_TITLES: Record<Tab, string> = {
  explore: "Explore",
  twin: "Building twin",
  agent: "Ask the agent",
  activity: "Activity",
  priority: "Priority · الأولويات",
};

// Three pages: the map (/), a building's own twin page (/building/<id>) and the conservator's review of one field
// inspection (/inspection/<id>). Plain history API, no router library.
type Route = { page: "map" } | { page: "building"; id: string } | { page: "inspection"; id: string };
const parseRoute = (path: string): Route => {
  const inspection = path.match(/^\/inspection\/([^/]+)\/?$/);
  if (inspection) return { page: "inspection", id: decodeURIComponent(inspection[1]) };
  const m = path.match(/^\/building\/([^/]+)\/?$/);
  return m && twinById(decodeURIComponent(m[1])) ? { page: "building", id: decodeURIComponent(m[1]) } : { page: "map" };
};

export default function App() {
  const live = useLive(BUILDING_ID);
  const demo = useSensorDemo(BUILDING_ID, live.readings);
  // the site manager's ranking from the core API (T3): the Priority tab and the tint of the twin buildings on the map
  const priority = useSitePriority();
  const [selection, setSelection] = useState<Selection | null>(null);
  const [fly, setFly] = useState<{ target: FlyTarget; nonce: number }>({ target: "turaif", nonce: 0 });
  const [view, setView] = useState<ViewId | null>("turaif");
  const [layer, setLayer] = useState<WallLayer>("damage");
  const [coordinate, setCoordinate] = useState<string | null>(null);
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.pathname));
  const [tab, setTab] = useState<Tab>("explore");
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
  const openInspection = (inspectionId: string) => navigate(`/inspection/${encodeURIComponent(inspectionId)}`);
  const back = () => (window.history.state?.from === "app" ? window.history.back() : navigate("/"));

  // Qasr Salwa (or one of its wall zones) is selected: its wall grid, risk labels and reading cards show on the map.
  const focusSelected = selection?.kind === "zone" || (selection?.kind === "building" && selection.isFocus);

  // Clicking a building that has a twin page opens that page. Qasr Salwa takes two clicks: the first selects it and
  // shows its readings on the map, the second (while it is selected) opens its page.
  const select = (s: Selection | null) => {
    const again = s?.kind === "building" && s.isFocus && focusSelected;
    setSelection(s);
    if (!s) return;
    setTab("twin");
    if (s.kind === "building" && s.twinId && (!s.isFocus || again)) openPage(s.twinId);
  };

  // The wall comes from the API; with the API off ("Offline") the map still uses the registry file directly.
  const wall: WallSpec | null = live.building?.walls[0] ?? (registry.walls[0] as WallSpec);
  const sim = live.simulation;
  // The simulation runs in the background: there is no player on the map, and the Moisture layer shows each zone's
  // peak in the latest run.

  const zoneColors = useMemo(() => {
    if (layer === "damage") return damageZoneColors(live.damageReport?.report ?? null);
    if (layer === "risk") return Object.fromEntries(live.risk.map((r) => [r.zone_id, RISK_COLORS[r.level]]));
    if (!sim) return {};
    return Object.fromEntries(Object.keys(sim.zones).map((zone) => [zone, moistureColor(peakMoisture(sim, zone) ?? 0)]));
  }, [layer, live.risk, live.damageReport, sim]);

  // Boxes around the wall areas that need attention: high risk, new or grown defects, moisture alerts.
  const attention = useMemo(() => attentionItems(live.risk, live.detections, live.events), [live.risk, live.detections, live.events]);

  // Sensor readings pinned to the facade next to their zones, only once Qasr Salwa is selected.
  const callouts = useMemo(
    () =>
      focusSelected
        ? sensorCallouts(live.building?.sensors ?? (registry.sensors as SensorSpec[]), live.readings, attention, wall?.cols ?? 8, tab !== "explore", (id) =>
            sensorHealth(live, id).status,
          )
        : [],
    [live, attention, wall?.cols, tab, focusSelected],
  );

  const go = (target: ViewId) => {
    setView(target);
    setFly((f) => ({ target, nonce: f.nonce + 1 }));
  };

  // a building from the Priority list: the focus building has its own view, the other twins are flown to by footprint
  const flyToBuilding = (buildingId: string) => {
    const twin = twinById(buildingId);
    if (!twin || twin.onMap === "focus") return go("focus");
    setView(null);
    setFly((f) => ({ target: `twin:${buildingId}`, nonce: f.nonce + 1 }));
  };

  const pageTwin = route.page === "building" ? twinById(route.id) : null;
  const pageOpen = !!pageTwin || route.page === "inspection";

  return (
    <>
      {pageTwin && (
        <BuildingPage
          twin={pageTwin}
          live={live}
          fallbackSensors={registry.sensors as SensorSpec[]}
          walls={pageTwin.registered && wall ? [wall] : []}
          zoneColors={pageTwin.registered ? zoneColors : {}}
          attention={pageTwin.registered ? attention : []}
          layer={layer}
          setLayer={setLayer}
          cameraWallBox={CAMERA_WALL_BOX}
          onBack={back}
          onOpenInspection={openInspection}
        />
      )}
      {route.page === "inspection" && <InspectionPage inspectionId={route.id} onBack={back} />}
      {/* The map stays loaded (and paused) while a building page is open, so going back is instant. */}
      <div className={`app hud${tab !== "explore" ? " with-panel" : ""}`} style={pageOpen ? { display: "none" } : undefined}>
        <SiteMap
          paused={pageOpen}
          buildingId={BUILDING_ID}
          wall={wall}
          zoneColors={zoneColors}
          attention={focusSelected ? attention : []}
          callouts={callouts}
          zonesVisible={focusSelected}
          selectedZone={selection?.kind === "zone" ? selection.zoneId : null}
          fly={fly}
          escalations={priority.escalations}
          time={time}
          layers={layers}
          onSelect={select}
          onStats={setStats}
          onCoordinate={(lon, lat, h) => setCoordinate(`lon ${lon.toFixed(6)}, lat ${lat.toFixed(6)}, height ${h.toFixed(1)} m`)}
        />

        <BrandTower tab={tab} onTab={(t) => setTab((cur) => (cur === t && t !== "explore" ? "explore" : t))} />

        <LayersMenu
          connected={live.connected}
          layers={layers}
          setLayer={(id, on) => setLayers((l) => ({ ...l, [id]: on }))}
          stats={stats}
        />

        <ViewsTower view={view} onView={go} stats={stats} />

        {tab !== "explore" && (
          <aside className="tower tower-panel" aria-label={TAB_TITLES[tab]}>
            <div className="tower-panel-head">
              <h2>{TAB_TITLES[tab]}</h2>
              <button className="x" aria-label="Close the panel" onClick={() => setTab("explore")}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </div>
            <div className="tower-panel-body">
              {tab === "twin" && (
                <BuildingPanel selection={selection} live={live} onFlyToFocus={() => go("focus")} onOpenPage={openPage} />
              )}
              {tab === "agent" && <AgentChat buildingId={live.building?.building.id ?? BUILDING_ID} />}
              {tab === "priority" && <PriorityPanel priority={priority} onFly={flyToBuilding} />}
              {tab === "activity" && (
                <div className="activity">
                  <h3>Field inspections</h3>
                  <InspectionLinks buildingId={BUILDING_ID} onOpen={openInspection} />
                  <SensorDemoPanel demo={demo} />
                  {live.connected && !demo.active && <>
                    <h3>Live controls</h3>
                    <ScenarioPanel buildingId={BUILDING_ID} compact />
                  </>}
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
          setPlaying={setPlaying}
          speed={speed}
          setSpeed={setSpeed}
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
