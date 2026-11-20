// Buildings that have their own twin page (owner: Omar). Each opens a full page (/building/<id>) with its
// 3D view, wall condition, live sensors and cameras. Add a building here to give it a page.
import { FOCUS_MODEL_URL } from "./config";
import { FOCUS_SCAN, type EmbeddedScan } from "./twin/scans";

export interface TwinDef {
  id: string; // building id used by the API (B01) or a placeholder until it is registered
  name: string;
  ar?: string;
  /** How the 3D view on its page is made: our own GLB (with wall zones on it) or a Sketchfab scan embed. */
  view: { kind: "model"; url: string } | { kind: "scan"; scan: EmbeddedScan };
  /** True when contracts/ has a registry for it, so the API has sensors, zones, risk and camera data. */
  registered: boolean;
  /** Which map building opens this page: "focus" = our placed model, or the OSM building name to match. */
  onMap: "focus" | RegExp;
  mapLabel?: string; // the name the map shows, when it differs
  note: string; // shown on the page, so nobody mistakes a placeholder for the real thing
}

export const TWINS: TwinDef[] = [
  {
    id: "B01",
    name: "Qasr Salwa",
    ar: "قصر سلوى",
    view: { kind: "model", url: FOCUS_MODEL_URL },
    registered: true,
    onMap: "focus",
    note: "3D model is AI-generated (not a survey), placed at a placeholder spot in At-Turaif. Sensors are simulated except the brick node.",
  },
  {
    id: "B02",
    name: "AL Turaif House",
    view: { kind: "scan", scan: FOCUS_SCAN },
    registered: false,
    onMap: /palace of nasr/i,
    mapLabel: "palace of Nasr",
    note: "Real 3D scan by ATS (Sketchfab embed). Wall zones, sensors and cameras are not set up for this house yet.",
  },
];

export const twinById = (id: string | null | undefined) => TWINS.find((t) => t.id === id) ?? null;
export const FOCUS_TWIN = TWINS.find((t) => t.onMap === "focus")!;
export const twinTitle = (t: TwinDef) => (t.ar ? `${t.name} (${t.ar})` : t.name);
