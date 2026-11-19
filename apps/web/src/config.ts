const env = import.meta.env;

export const API_URL = env.VITE_API_URL || "http://localhost:8000";
export const AGENTS_URL = env.VITE_AGENTS_URL || "http://localhost:8100";
export const WS_URL = `${API_URL.replace(/^http/, "ws")}/ws/live`;
export const CESIUM_ION_TOKEN = env.VITE_CESIUM_ION_TOKEN || "";
export const FOCUS_OSM_ID = env.VITE_FOCUS_OSM_ID ? Number(env.VITE_FOCUS_OSM_ID) : null;
export const FOCUS_MODEL_URL = env.VITE_FOCUS_MODEL_URL || "";

export const BUILDING_ID = "B01";
export const SITE = { lon: 46.5725, lat: 24.7336 }; // At-Turaif
export const HOUR_MS = 2500; // demo clock: 1 simulated hour every 2.5 s = 1 day per minute
