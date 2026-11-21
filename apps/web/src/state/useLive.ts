import { useEffect, useState } from "react";

import { api, connectLive } from "../api/client";
import { WS_URL } from "../config";
import { type DamageReportEvent, damageReportOf } from "../twin/damage";
import type {
  BuildingRegistry,
  Detection,
  HadaqEvent,
  Photo,
  RiskScore,
  SensorReading,
  SensorStatus,
  SimulationResult,
} from "../types/contracts";

const TREND_POINTS = 40;
const STALE_MS = 30_000; // same rule as the API (SENSOR_STALE_S)

export interface SensorHealth {
  status: SensorStatus;
  reasons: string[];
}

export interface LiveState {
  building: BuildingRegistry | null;
  readings: Record<string, SensorReading>;
  /** Last good values per sensor, oldest first (for the sparklines). */
  trends: Record<string, number[]>;
  /** Health per sensor from the API (ok, suspect, fault, stale, no_data). */
  health: Record<string, SensorHealth>;
  /** Ticks every few seconds so "stale" shows up even when nothing new arrives. */
  now: number;
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
    trends: {},
    health: {},
    now: Date.now(),
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
      api.readings(buildingId, 600).then((list) => {
        const trends: Record<string, number[]> = {};
        for (const r of [...list].reverse()) if (r.quality === "ok") (trends[r.sensor_id] ??= []).push(r.value);
        for (const k of Object.keys(trends)) trends[k] = trends[k].slice(-TREND_POINTS);
        patch({ trends });
      }),
      api.sensors(buildingId).then((list) => patch({ health: Object.fromEntries(list.map((s) => [s.sensor_id, { status: s.status, reasons: s.reasons }])) })),
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
          case "reading": {
            const r = m.data;
            const good = (r.status ?? (r.quality === "ok" ? "ok" : r.quality)) === "ok";
            patch((s) => ({
              readings: { ...s.readings, [r.sensor_id]: r },
              trends: good ? { ...s.trends, [r.sensor_id]: [...(s.trends[r.sensor_id] ?? []), r.value].slice(-TREND_POINTS) } : s.trends,
              health: r.status ? { ...s.health, [r.sensor_id]: { status: r.status, reasons: r.reasons ?? [] } } : s.health,
            }));
            break;
          }
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
    const tick = window.setInterval(() => patch({ now: Date.now() }), 5000);
    return () => {
      alive = false;
      window.clearInterval(tick);
      close();
    };
  }, [buildingId]);

  return state;
}

/** A sensor's health right now: the API's verdict, turned "stale" when nothing has arrived for a while. */
export function sensorHealth(live: Pick<LiveState, "readings" | "health" | "now">, sensorId: string): SensorHealth {
  const r = live.readings[sensorId];
  if (!r) return { status: "no_data", reasons: ["no reading yet"] };
  const age = live.now - Date.parse(r.ts);
  if (age > STALE_MS) return { status: "stale", reasons: [`no reading for ${Math.round(age / 1000)} s`] };
  return live.health[sensorId] ?? { status: r.quality === "ok" ? "ok" : r.quality, reasons: [] };
}
