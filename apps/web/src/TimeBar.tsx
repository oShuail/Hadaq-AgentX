// Demo clock: scrubs through the latest simulation hour by hour (sun and wall colors follow it).
import { useEffect, type ReactNode } from "react";

import { HOUR_MS } from "./config";
import type { SimulationResult } from "./types/contracts";

interface Props {
  simulation: SimulationResult | null;
  running: boolean;
  hour: number;
  setHour: (h: number) => void;
  playing: boolean;
  setPlaying: (p: boolean) => void;
  layer: "moisture" | "risk";
  setLayer: (l: "moisture" | "risk") => void;
  children?: ReactNode;
}

export default function TimeBar({ simulation, running, hour, setHour, playing, setPlaying, layer, setLayer, children }: Props) {
  const last = simulation ? simulation.times.length - 1 : 0;

  useEffect(() => {
    if (!playing || !simulation) return;
    const id = window.setInterval(() => setHour(hour >= last ? 0 : hour + 1), HOUR_MS);
    return () => window.clearInterval(id);
  }, [playing, hour, last, simulation, setHour]);

  const time = simulation?.times[hour];
  return (
    <footer className="timebar">
      <button className="play" onClick={() => setPlaying(!playing)} disabled={!simulation} aria-label={playing ? "Pause" : "Play"}>
        {playing ? "❚❚" : "▶"}
      </button>
      <div className="clock">
        <strong>{time ? new Date(time).toLocaleString([], { weekday: "short", hour: "2-digit", minute: "2-digit" }) : "--:--"}</strong>
        <span className="muted small">{simulation ? `${simulation.scenario} | ${simulation.run_id}` : running ? "simulation running..." : "no simulation yet"}</span>
      </div>
      <input type="range" min={0} max={last} value={hour} onChange={(e) => setHour(Number(e.target.value))} disabled={!simulation} />
      <span className="muted small">1 day per minute</span>
      <div className="layer-toggle">
        <button className={layer === "moisture" ? "active" : ""} onClick={() => setLayer("moisture")}>Moisture</button>
        <button className={layer === "risk" ? "active" : ""} onClick={() => setLayer("risk")}>Risk</button>
      </div>
      {children}
    </footer>
  );
}
