import { useEffect, useState } from "react";

import { api } from "../api/client";
import type { SensorReading } from "../types/contracts";
import { phaseAt } from "./sensorDemo";

type RunStatus = Awaited<ReturnType<typeof api.sensorRunStatus>>;
type Sample = { second: number; base: number; upper: number };

export function useSensorDemo(buildingId: string, readings: Record<string, SensorReading>) {
  const [status, setStatus] = useState<RunStatus | null>(null);
  const [error, setError] = useState("");
  const [samples, setSamples] = useState<Sample[]>([]);
  const elapsed = status?.elapsed ?? 0;

  useEffect(() => {
    let alive = true;
    const update = () => api.sensorRunStatus(buildingId)
      .then((next) => { if (alive) setStatus(next); })
      .catch(() => { if (alive) setStatus(null); });
    void update();
    const timer = window.setInterval(update, 1000);
    return () => { alive = false; window.clearInterval(timer); };
  }, [buildingId]);

  useEffect(() => {
    if (!status?.running) return;
    const base = readings["B01-S04"];
    const upper = readings["B01-S05"];
    if (!base || !upper) return;
    const second = Math.floor(elapsed);
    setSamples((prev) => [...prev.filter((sample) => sample.second !== second),
      { second, base: base.value, upper: upper.value }].sort((a, b) => a.second - b.second));
  }, [elapsed, readings, status?.running]);

  const start = async () => {
    setError("");
    try {
      const next = await api.startSensorRun(buildingId);
      setSamples([]);
      setStatus(next);
    } catch (err) { setError(`Could not start the live feed: ${String(err)}`); }
  };
  const reset = async () => {
    setError("");
    try {
      const next = await api.stopSensorRun(buildingId);
      setSamples([]);
      setStatus(next);
    } catch (err) { setError(`Could not reset the live feed: ${String(err)}`); }
  };

  return { active: status?.running ?? false, available: status?.available ?? false,
    elapsed, phase: phaseAt(elapsed), readings, samples, error, start, reset };
}
