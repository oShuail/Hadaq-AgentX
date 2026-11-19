// Bottom bar (owner: Omar): the day clock that drives the sun, lights and crowds, and, when a simulation has
// run, a second row that scrubs through it hour by hour (the sun and wall colours follow it).
import { type Dispatch, type SetStateAction, useEffect } from "react";

import { atMinutes, hhmm, isoDay, minutesOfDay, onDay } from "./clock";
import { HOUR_MS, SITE } from "./config";
import { dayPhase, sunPosition } from "./twin3d/sun";
import type { SimulationResult } from "./types/contracts";

interface Props {
  time: Date;
  setTime: Dispatch<SetStateAction<Date>>;
  playing: boolean;
  setPlaying: (p: boolean) => void;
  speed: number; // simulated minutes per real second
  setSpeed: (s: number) => void;
  simulation: SimulationResult | null;
  running: boolean;
  hour: number;
  setHour: (h: number) => void;
  simPlaying: boolean;
  setSimPlaying: (p: boolean) => void;
  layer: "moisture" | "risk";
  setLayer: (l: "moisture" | "risk") => void;
}

const TICK_MS = 100;

export default function TimeBar(p: Props) {
  const { time, setTime, playing, speed } = p;

  // Day clock.
  useEffect(() => {
    if (!playing) return;
    const id = window.setInterval(() => setTime((t) => new Date(t.getTime() + speed * 60_000 * (TICK_MS / 1000))), TICK_MS);
    return () => window.clearInterval(id);
  }, [playing, speed, setTime]);

  // Simulation scrubber.
  const last = p.simulation ? p.simulation.times.length - 1 : 0;
  useEffect(() => {
    if (!p.simPlaying || !p.simulation) return;
    const id = window.setInterval(() => p.setHour(p.hour >= last ? 0 : p.hour + 1), HOUR_MS);
    return () => window.clearInterval(id);
  }, [p.simPlaying, p.hour, last, p.simulation]); // eslint-disable-line react-hooks/exhaustive-deps

  const sun = sunPosition(time, SITE.lat, SITE.lon);
  const rising = sunPosition(new Date(time.getTime() + 600_000), SITE.lat, SITE.lon).altitude > sun.altitude;
  const minutes = minutesOfDay(time);

  return (
    <footer className="clockbar">
      <div className="clock-row">
        <button className="play" onClick={() => p.setPlaying(!playing)} aria-label={playing ? "Pause the day" : "Play the day"}>
          {playing ? <PauseIcon /> : <PlayIcon />}
        </button>
        <div className="clock">
          <strong>{hhmm(time)}</strong>
          <span>
            Sun {sun.altitude.toFixed(1)}° · {dayPhase(sun.altitude, rising)}
          </span>
        </div>
        <input
          className="day-slider"
          type="range"
          min={0}
          max={1439}
          value={minutes}
          onChange={(e) => setTime((t) => atMinutes(t, Number(e.target.value)))}
          aria-label="Time of day"
        />
        <input className="date" type="date" value={isoDay(time)} onChange={(e) => setTime((t) => onDay(t, e.target.value))} aria-label="Date" />
        <select className="speed" value={speed} onChange={(e) => p.setSpeed(Number(e.target.value))} aria-label="Clock speed">
          <option value={1}>1 min/s</option>
          <option value={10}>10 min/s</option>
          <option value={30}>30 min/s</option>
        </select>
        <button className="now" onClick={() => setTime(new Date())}>Now</button>
      </div>

      {(p.simulation || p.running) && (
        <div className="sim-row">
          <button className="play small" onClick={() => p.setSimPlaying(!p.simPlaying)} disabled={!p.simulation} aria-label="Play the simulation">
            {p.simPlaying ? <PauseIcon /> : <PlayIcon />}
          </button>
          <div className="sim-label">
            <strong>Simulation</strong>
            <span>
              {p.simulation
                ? `${p.simulation.scenario.replace(/_/g, " ")} · hour ${p.hour + 1} of ${last + 1}`
                : "running..."}
            </span>
          </div>
          <input type="range" min={0} max={last} value={p.hour} onChange={(e) => p.setHour(Number(e.target.value))} disabled={!p.simulation} aria-label="Simulation hour" />
          <div className="seg">
            <button className={p.layer === "moisture" ? "on" : ""} onClick={() => p.setLayer("moisture")}>Moisture</button>
            <button className={p.layer === "risk" ? "on" : ""} onClick={() => p.setLayer("risk")}>Risk</button>
          </div>
        </div>
      )}
    </footer>
  );
}

function PlayIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <path d="M4 2.5v11l9-5.5z" fill="currentColor" />
    </svg>
  );
}

function PauseIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <path d="M3.5 2.5h3v11h-3zM9.5 2.5h3v11h-3z" fill="currentColor" />
    </svg>
  );
}
