import type { SensorReading, SensorSpec } from "../types/contracts";

export const DEMO_DURATION = 120;

export type DemoPhase = "storm" | "drying" | "fault" | "crack" | "recovery" | "complete";

export function phaseAt(seconds: number): DemoPhase {
  if (seconds >= DEMO_DURATION) return "complete";
  if (seconds >= 105) return "recovery";
  if (seconds >= 75) return "crack";
  if (seconds >= 60) return "fault";
  if (seconds >= 35) return "drying";
  return "storm";
}

const rise = (start: number, target: number, seconds: number, tau: number) =>
  target - (target - start) * Math.exp(-Math.max(0, seconds) / tau);
const round = (value: number, places = 1) => Number(value.toFixed(places));

/** The same target values and response times as the MQTT simulator's default speed. */
export function sensorValuesAt(seconds: number): Record<string, number> {
  const t = Math.max(0, Math.min(DEMO_DURATION, seconds));
  const wet = Math.min(t, 35);
  const dry = Math.max(0, t - 35);
  const baseAtStop = rise(18, 78, 35, 20);
  const upperAtStop = rise(18, 52, 35, 60);
  const base = dry ? rise(baseAtStop, 18, dry, 600) : rise(18, 78, wet, 20);
  const upper = dry ? rise(upperAtStop, 18, dry, 300) : rise(18, 52, wet, 60);
  const humidityAtStop = rise(30, 85, 35, 15);
  const temperatureAtStop = rise(28.5, 24.5, 35, 15);
  const humidity = dry ? rise(humidityAtStop, 30, dry, 15) : rise(30, 85, wet, 15);
  const temperature = dry ? rise(temperatureAtStop, 28.5, dry, 15) : rise(28.5, 24.5, wet, 15);
  const fault = t >= 60 && t < 105;
  return {
    "B01-S01": round(base),
    "B01-S02": round(temperature),
    "B01-S03": round(humidity),
    "B01-S04": fault ? (Math.floor((t - 60) / 2) % 2 ? 96 : 8) : round(base),
    "B01-S05": round(upper),
    "B01-S06": round(Math.min(3, 1.2 + 0.002 * t / 5 + (t >= 75 ? 0.35 : 0)), 3),
  };
}

export function demoReadingsAt(seconds: number, sensors: SensorSpec[]): Record<string, SensorReading> {
  const values = sensorValuesAt(seconds);
  const fault = seconds >= 60 && seconds < 105;
  const ts = new Date().toISOString();
  return Object.fromEntries(sensors.map((sensor) => [sensor.id, {
    sensor_id: sensor.id,
    building_id: "B01",
    zone_id: sensor.zone_id,
    kind: sensor.kind,
    value: values[sensor.id],
    unit: sensor.unit,
    ts,
    provenance: "synthetic" as const,
    device_id: sensor.device_id,
    quality: sensor.id === "B01-S04" && fault ? "fault" as const : "ok" as const,
  }]));
}
