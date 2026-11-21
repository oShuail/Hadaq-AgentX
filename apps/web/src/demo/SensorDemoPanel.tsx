import { DEMO_DURATION, sensorValuesAt } from "./sensorDemo";
import type { useSensorDemo } from "./useSensorDemo";

const PHASE_LABELS = {
  storm: "Storm: base moisture rising",
  drying: "Storm stopped: wall drying",
  fault: "S04 sensor fault",
  crack: "New crack: S06 stepped up",
  recovery: "S04 recovered; wall drying",
  complete: "Two-minute run complete",
};

const SERIES = [
  { id: "B01-S04", label: "Base S04", color: "#e0b15a" },
  { id: "B01-S05", label: "Upper S05", color: "#85b39a" },
] as const;

export default function SensorDemoPanel({ demo }: { demo: ReturnType<typeof useSensorDemo> }) {
  const x = (seconds: number) => 28 + (seconds / DEMO_DURATION) * 292;
  const y = (value: number) => 112 - value;
  const times = Array.from({ length: Math.floor(demo.elapsed / 2) + 1 }, (_, i) => i * 2);
  if (demo.elapsed % 2) times.push(demo.elapsed);

  return (
    <section className="sensor-demo" aria-label="Two-minute sensor simulation">
      <div className="sensor-demo-head">
        <div><strong>Two-minute sensor run</strong><span>{demo.active ? PHASE_LABELS[demo.phase] : "Ready to play"}</span></div>
        <time>{Math.floor(demo.elapsed / 60)}:{String(demo.elapsed % 60).padStart(2, "0")} / 2:00</time>
      </div>
      <div className="sensor-demo-actions">
        <button className="primary" onClick={demo.start}>{demo.active ? "Replay" : "Play sensor run"}</button>
        {demo.active && <button onClick={demo.reset}>Clear</button>}
      </div>
      <div className="sensor-demo-track" role="progressbar" aria-valuemin={0} aria-valuemax={DEMO_DURATION} aria-valuenow={demo.elapsed} aria-label="Sensor run progress">
        <i style={{ width: `${demo.elapsed / DEMO_DURATION * 100}%` }} />
      </div>
      <svg className="sensor-demo-chart" viewBox="0 0 340 135" role="img" aria-label="Base and upper wall moisture over two minutes">
        {[20, 60, 100].map((v) => <g key={v}><line x1="28" x2="320" y1={y(v)} y2={y(v)} /><text x="2" y={y(v) + 4}>{v}</text></g>)}
        {SERIES.map((series) => <polyline key={series.id} fill="none" stroke={series.color} strokeWidth="2.5"
          points={times.map((t) => `${x(t)},${y(sensorValuesAt(t)[series.id])}`).join(" ")} />)}
        <line className="cursor" x1={x(demo.elapsed)} x2={x(demo.elapsed)} y1="12" y2="112" />
        <text x="27" y="130">0:00</text><text x="282" y="130">2:00</text>
      </svg>
      <div className="sensor-demo-legend">{SERIES.map((s) => <span key={s.id}><i style={{ background: s.color }} />{s.label}</span>)}</div>
      <div className="sensor-demo-values">
        <span>Base <b>{demo.values["B01-S04"].toFixed(1)} %rel</b></span>
        <span>Upper <b>{demo.values["B01-S05"].toFixed(1)} %rel</b></span>
        <span>Humidity <b>{demo.values["B01-S03"].toFixed(1)} %RH</b></span>
        <span>Temperature <b>{demo.values["B01-S02"].toFixed(1)} °C</b></span>
        <span>Crack S06 <b>{demo.values["B01-S06"].toFixed(3)} mm</b></span>
        <span>S04 quality <b>{demo.readings?.["B01-S04"].quality ?? "ok"}</b></span>
      </div>
    </section>
  );
}
