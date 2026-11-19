import { useEffect, useState } from "react";

import { api, connectLive } from "../api/client";
import { WS_URL } from "../config";
import type { BuildingRegistry, Detection, HadaqEvent, Photo, RiskScore, SensorReading, SimulationResult } from "../types/contracts";

export interface LiveState {
  building: BuildingRegistry | null;
  readings: Record<string, SensorReading>;
  detections: Detection[];
  risk: RiskScore[];
  events: HadaqEvent[];
  photo: Photo | null;
  simulation: SimulationResult | null;
  simulationRunning: boolean;
  connected: boolean;
}

/** Loads the current state once, then keeps it fresh from the /ws/live stream. */
export function useLive(buildingId: string): LiveState {
  const [state, setState] = useState<LiveState>({
    building: null,
    readings: {},
    detections: [],
    risk: [],
    events: [],
    photo: null,
    simulation: null,
    simulationRunning: false,
    connected: false,
  });

  useEffect(() => {
    let alive = true;
    const patch = (p: Partial<LiveState> | ((s: LiveState) => Partial<LiveState>)) =>
      alive && setState((s) => ({ ...s, ...(typeof p === "function" ? p(s) : p) }));

    Promise.allSettled([
      api.building(buildingId).then((building) => patch({ building })),
      api.latestReadings(buildingId).then((list) => patch({ readings: Object.fromEntries(list.map((r) => [r.sensor_id, r])) })),
      api.latestDetections(buildingId).then((detections) => patch({ detections })),
      api.risk(buildingId).then((risk) => patch({ risk })),
      api.events(buildingId).then((events) => patch({ events })),
      api.latestPhotos(buildingId).then((photos) => patch({ photo: photos[0] ?? null })),
      api.latestSimulation(buildingId).then((simulation) => patch({ simulation })),
    ]);

    const close = connectLive(
      WS_URL,
      (m) => {
        switch (m.channel) {
          case "reading":
            patch((s) => ({ readings: { ...s.readings, [m.data.sensor_id]: m.data } }));
            break;
          case "event":
            patch((s) => ({
              events: [m.data, ...s.events].slice(0, 50),
              simulationRunning: m.data.type === "simulation_done" ? false : s.simulationRunning,
            }));
            break;
          case "photo":
            patch({ photo: m.data, detections: [] });
            break;
          case "detection":
            patch({ detections: m.data.detections });
            break;
          case "simulation":
            if (m.data.status === "done" && m.data.result) patch({ simulation: m.data.result, simulationRunning: false });
            else patch({ simulationRunning: m.data.status === "running" });
            break;
          case "risk":
            patch({ risk: m.data.scores });
            break;
        }
      },
      (up) => patch({ connected: up }),
    );
    return () => {
      alive = false;
      close();
    };
  }, [buildingId]);

  return state;
}
