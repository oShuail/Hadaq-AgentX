import { useEffect, useState } from "react";

import { DEMO_DURATION, demoReadingsAt, phaseAt, sensorValuesAt } from "./sensorDemo";
import type { SensorSpec } from "../types/contracts";

export function useSensorDemo(sensors: SensorSpec[]) {
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (startedAt === null) return;
    const tick = () => setElapsed(Math.min(DEMO_DURATION, Math.floor((Date.now() - startedAt) / 1000)));
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [startedAt]);

  const active = startedAt !== null;
  return {
    active,
    elapsed,
    phase: phaseAt(elapsed),
    readings: active ? demoReadingsAt(elapsed, sensors) : null,
    values: sensorValuesAt(elapsed),
    start: () => { setElapsed(0); setStartedAt(Date.now()); },
    reset: () => { setStartedAt(null); setElapsed(0); },
  };
}
