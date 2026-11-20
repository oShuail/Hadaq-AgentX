const env = import.meta.env;

export const API_URL = env.VITE_API_URL || "http://localhost:8000";
export const AGENTS_URL = env.VITE_AGENTS_URL || "http://localhost:8100";
export const WS_URL = `${API_URL.replace(/^http/, "ws")}/ws/live`;
// Our own 3D model of the focus building (GLB in public/models/, with <name>.footprint.json next to it).
export const FOCUS_MODEL_URL = env.VITE_FOCUS_MODEL_URL ?? "/models/b01_v3.glb";

export const BUILDING_ID = "B01";
export const SITE = { lon: 46.5725, lat: 24.7336 }; // At-Turaif, origin of the 3D twin (public/site/site.json)
export const HOUR_MS = 2500; // demo clock: 1 simulated hour every 2.5 s = 1 day per minute
