import { AGENTS_URL, API_URL } from "../config";
import type {
  BuildingRegistry,
  ChatResponse,
  Detection,
  HadaqEvent,
  LiveMessage,
  Photo,
  RiskScore,
  SensorOverview,
  SensorReading,
  SimulationResult,
} from "../types/contracts";

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${response.status} ${url}`);
  return (await response.json()) as T;
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error(`${response.status} ${url}`);
  return (await response.json()) as T;
}

export const api = {
  sensorRunStatus: (id: string) => getJson<{ available: boolean; running: boolean; elapsed: number; duration: number }>(`${API_URL}/buildings/${id}/demo/sensor-run`),
  startSensorRun: (id: string) => postJson<{ available: boolean; running: boolean; elapsed: number; duration: number }>(`${API_URL}/buildings/${id}/demo/sensor-run`, {}),
  stopSensorRun: (id: string) => postJson<{ available: boolean; running: boolean; elapsed: number; duration: number }>(`${API_URL}/buildings/${id}/demo/sensor-run/stop`, {}),
  building: (id: string) => getJson<BuildingRegistry>(`${API_URL}/buildings/${id}`),
  latestReadings: (id: string) => getJson<SensorReading[]>(`${API_URL}/buildings/${id}/readings/latest`),
  readings: (id: string, limit = 400) => getJson<SensorReading[]>(`${API_URL}/buildings/${id}/readings?limit=${limit}`),
  sensors: (id: string, window = 60) => getJson<SensorOverview[]>(`${API_URL}/buildings/${id}/sensors?window=${window}`),
  latestDetections: (id: string) => getJson<Detection[]>(`${API_URL}/buildings/${id}/detections/latest`),
  risk: (id: string) => getJson<RiskScore[]>(`${API_URL}/buildings/${id}/risk`),
  events: (id: string, limit = 20) => getJson<HadaqEvent[]>(`${API_URL}/buildings/${id}/events?limit=${limit}`),
  latestPhotos: (id: string, limit = 1) => getJson<Photo[]>(`${API_URL}/buildings/${id}/photos?limit=${limit}`),
  latestSimulation: async (id: string) => {
    try {
      return (await getJson<{ result: SimulationResult }>(`${API_URL}/buildings/${id}/simulations/latest`)).result;
    } catch {
      return null;
    }
  },
  evidence: (evidenceId: string) => getJson<Record<string, unknown>>(`${API_URL}/evidence/${evidenceId}`),
  postEvent: (event: Partial<HadaqEvent> & { type: string; building_id: string }) =>
    postJson<HadaqEvent>(`${API_URL}/events`, { source: "scenario_panel", provenance: "synthetic", payload: {}, ...event }),
  runSimulation: (buildingId: string, scenario = "storm_replay") =>
    postJson<{ run_id: string }>(`${API_URL}/simulations`, {
      building_id: buildingId,
      scenario,
      weather: { mode: "synthetic_storm", hours: 72 },
      requested_by: "user",
    }),
  chat: (message: string, buildingId: string, sessionId = "web") =>
    postJson<ChatResponse>(`${AGENTS_URL}/agents/chat`, { message, building_id: buildingId, session_id: sessionId }),
};

/** Live updates from the API. Reconnects automatically. Returns a function that closes the socket. */
export function connectLive(url: string, onMessage: (m: LiveMessage) => void, onStatus: (up: boolean) => void): () => void {
  let socket: WebSocket | null = null;
  let closed = false;
  let retry: number | undefined;

  const open = () => {
    socket = new WebSocket(url);
    socket.onopen = () => onStatus(true);
    socket.onmessage = (e) => onMessage(JSON.parse(e.data) as LiveMessage);
    socket.onclose = () => {
      onStatus(false);
      if (!closed) retry = window.setTimeout(open, 2000);
    };
  };
  open();
  return () => {
    closed = true;
    window.clearTimeout(retry);
    socket?.close();
  };
}
