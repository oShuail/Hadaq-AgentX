import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import App from "./App";
import { BUILDING_ID } from "./config";
import ScenarioPanel from "./demo/ScenarioPanel";
import "./styles.css";

// http://localhost:5173/?panel=1 shows only the scenario buttons (open it on a phone during the demo).
const panelOnly = new URLSearchParams(window.location.search).has("panel");

createRoot(document.getElementById("root")!).render(
  <StrictMode>{panelOnly ? <ScenarioPanel buildingId={BUILDING_ID} /> : <App />}</StrictMode>,
);
