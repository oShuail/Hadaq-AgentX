// Bottom-centre clock (owner: Omar). The sun travels a half-circle arc (left = midnight, top = noon) that
// drives the sun, lights and shadows; drag it, or use the keyboard on the time slider. When a simulation has
// run, a pill above it plays the simulation hour by hour (the sun and wall colours follow it).
import { type Dispatch, type PointerEvent as ReactPointerEvent, type SetStateAction, useEffect, useRef } from "react";

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
// The arc: an ellipse centred at (CX, CY) with radii RX, RY, drawn in a 500 x 150 box.
const CX = 250;
const CY = 138;
const RX = 196;
const RY = 116;
const SPEEDS = [1, 10, 30];

const arcPoint = (minutes: number) => {
  const a = Math.PI * (1 - minutes / 1440);
  return { x: CX + RX * Math.cos(a), y: CY - RY * Math.sin(a) };
};

export default function TimeBar(p: Props) {
  const { time, setTime, playing, speed } = p;
  const svg = useRef<SVGSVGElement>(null);
  const dragging = useRef(false);

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
  const dot = arcPoint(minutes);
  const up = sun.altitude > -0.8;

  // Drag along the arc to set the time of day.
  const fromPointer = (e: ReactPointerEvent<SVGSVGElement>) => {
    const box = svg.current?.getBoundingClientRect();
    if (!box) return;
    const x = ((e.clientX - box.left) / box.width) * 500;
    const y = ((e.clientY - box.top) / box.height) * 150;
    let a = Math.atan2((CY - y) / RY, (x - CX) / RX);
    if (a < 0) a = x < CX ? Math.PI : 0;
    const m = Math.round((1 - a / Math.PI) * 1439);
    setTime((t) => atMinutes(t, m));
  };

  return (
    <footer className="sunclock">
      {(p.simulation || p.running) && (
        <div className="sim-pill">
          <button className="round gold" onClick={() => p.setSimPlaying(!p.simPlaying)} disabled={!p.simulation} aria-label={p.simPlaying ? "Pause the simulation" : "Play the simulation"}>
            {p.simPlaying ? <PauseIcon /> : <PlayIcon />}
          </button>
          <span className="sim-text">
            <b>Simulation</b> ·{" "}
            {p.simulation ? `${p.simulation.scenario.replace(/_/g, " ")} · hour ${p.hour + 1} of ${last + 1}` : "running..."}
          </span>
          <input type="range" min={0} max={last} value={p.hour} onChange={(e) => p.setHour(Number(e.target.value))} disabled={!p.simulation} aria-label="Simulation hour" />
          <span className="seg-pill" role="group" aria-label="Wall zone colours">
            <button className={p.layer === "moisture" ? "on" : ""} aria-pressed={p.layer === "moisture"} onClick={() => p.setLayer("moisture")}>Moisture</button>
            <button className={p.layer === "risk" ? "on" : ""} aria-pressed={p.layer === "risk"} onClick={() => p.setLayer("risk")}>Risk</button>
          </span>
        </div>
      )}

      <div className="sun-dome">
        <svg
          ref={svg}
          className="sun-arc"
          viewBox="0 0 500 150"
          onPointerDown={(e) => {
            dragging.current = true;
            e.currentTarget.setPointerCapture(e.pointerId);
            fromPointer(e);
          }}
          onPointerMove={(e) => dragging.current && fromPointer(e)}
          onPointerUp={() => (dragging.current = false)}
          aria-hidden="true"
        >
          <defs>
            <linearGradient id="sun-sky" x1="0" x2="1">
              <stop offset="0" stopColor="#4A3A5C" />
              <stop offset="0.24" stopColor="#B98A6A" />
              <stop offset="0.32" stopColor="#E3C48A" />
              <stop offset="0.5" stopColor="#F4E8D6" />
              <stop offset="0.7" stopColor="#E3C48A" />
              <stop offset="0.77" stopColor="#C8794A" />
              <stop offset="1" stopColor="#4A3A5C" />
            </linearGradient>
          </defs>
          <path d={`M${CX - RX} ${CY} A${RX} ${RY} 0 0 1 ${CX + RX} ${CY}`} fill="none" stroke="url(#sun-sky)" strokeWidth="5" strokeLinecap="round" />
          {[6, 12, 18].map((h) => {
            const q = arcPoint(h * 60);
            const inner = { x: CX + (q.x - CX) * 0.9, y: CY + (q.y - CY) * 0.9 };
            return (
              <g key={h}>
                <line x1={q.x} y1={q.y} x2={inner.x} y2={inner.y} stroke="#E3D2B8" strokeWidth="1.2" />
                <text x={CX + (q.x - CX) * 0.8} y={CY + (q.y - CY) * 0.8 + 4} textAnchor="middle" className="tick">{`${String(h).padStart(2, "0")}:00`}</text>
              </g>
            );
          })}
          <circle cx={dot.x} cy={dot.y} r="15" fill={up ? "rgba(224,184,102,0.25)" : "rgba(220,214,235,0.15)"} />
          <circle cx={dot.x} cy={dot.y} r="10" fill={up ? "#C9A24D" : "#CFC8DA"} stroke="#F4E8D6" strokeWidth="2" />
        </svg>
        <div className="sun-read">
          <strong>{hhmm(time)}</strong>
          <span>
            Riyadh · Sun {sun.altitude.toFixed(1)}° · {dayPhase(sun.altitude, rising)}
          </span>
        </div>
        <label className="sr-only">
          Time of day
          <input type="range" min={0} max={1439} value={minutes} onChange={(e) => setTime((t) => atMinutes(t, Number(e.target.value)))} />
        </label>
        <div className="sun-controls">
          <button className="round" onClick={() => p.setPlaying(!playing)} aria-label={playing ? "Pause the day" : "Play the day"}>
            {playing ? <PauseIcon /> : <PlayIcon />}
          </button>
          <input className="date" type="date" value={isoDay(time)} onChange={(e) => setTime((t) => onDay(t, e.target.value))} aria-label="Date" />
          <span className="speeds" role="group" aria-label="Clock speed">
            {SPEEDS.map((s) => (
              <button key={s} className={speed === s ? "on" : ""} aria-pressed={speed === s} onClick={() => p.setSpeed(s)}>
                {s === 30 ? "30 min/s" : s}
              </button>
            ))}
          </span>
          <button className="now" onClick={() => setTime(new Date())}>Now</button>
        </div>
      </div>
    </footer>
  );
}

function PlayIcon() {
  return (
    <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true">
      <path d="M4 2.5v11l9-5.5z" fill="currentColor" />
    </svg>
  );
}

function PauseIcon() {
  return (
    <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true">
      <path d="M3.5 2.5h3v11h-3zM9.5 2.5h3v11h-3z" fill="currentColor" />
    </svg>
  );
}
