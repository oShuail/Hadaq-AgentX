import { useEffect, useState } from "react";

import { api, connectLive } from "../api/client";
import { WS_URL } from "../config";
import { type DamageReportEvent, damageReportOf } from "../twin/damage";
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
  /** Latest damage report from the Damage and Treatment agent, kept even when the camera floods the event list. */
  damageReport: DamageReportEvent | null;
}

const REPORT_LOOKBACK = 300; // events searched for the latest damage report on page load

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
    damageReport: null,
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
      api.events(buildingId, REPORT_LOOKBACK).then((list) => {
        const latest = list.map(damageReportOf).find((r) => r !== null) ?? null;
        patch((s) => ({ damageReport: s.damageReport ?? latest }));
      }),
    ]);

    const close = connectLive(
      WS_URL,
      (m) => {
        switch (m.channel) {
          case "reading":
            patch((s) => ({ readings: { ...s.readings, [m.data.sensor_id]: m.data } }));
            break;
          case "event": {
            const report = damageReportOf(m.data);
            patch((s) => ({
              events: [m.data, ...s.events].slice(0, 50),
              simulationRunning: m.data.type === "simulation_done" ? false : s.simulationRunning,
              damageReport: report ?? (m.data.type === "demo_reset" ? null : s.damageReport),
            }));
            break;
          }
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
