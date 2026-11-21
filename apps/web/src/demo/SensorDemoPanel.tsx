import { DEMO_DURATION } from "./sensorDemo";
import type { useSensorDemo } from "./useSensorDemo";

const PHASE_LABELS = {
  storm: "Storm: base moisture rising",
  drying: "Storm stopped: upper wall drying, base still wet",
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
  const reading = (id: string, digits = 1) => demo.readings[id]?.value.toFixed(digits) ?? "—";

  return (
    <section className="sensor-demo" aria-label="Two-minute sensor simulation">
      <div className="sensor-demo-head">
        <div><strong>Two-minute sensor run</strong><span>{demo.active || demo.phase === "complete" ? PHASE_LABELS[demo.phase] : "Ready to play"}</span></div>
        <time>{Math.floor(demo.elapsed / 60)}:{String(Math.floor(demo.elapsed % 60)).padStart(2, "0")} / 2:00</time>
      </div>
      <div className="sensor-demo-actions">
        <button className="primary" onClick={demo.start} disabled={!demo.available}>{demo.elapsed > 0 ? "Replay" : "Play sensor run"}</button>
        {demo.elapsed > 0 && <button onClick={demo.reset}>Reset demo</button>}
      </div>
      {!demo.available && <p className="muted small">Start the API and the sensor simulator (make sensors, or tools\windows\start-hadaq.bat) to run the live feed.</p>}
      {demo.error && <p className="muted small" role="alert">{demo.error}</p>}
      <div className="sensor-demo-track" role="progressbar" aria-valuemin={0} aria-valuemax={DEMO_DURATION} aria-valuenow={demo.elapsed} aria-label="Sensor run progress">
        <i style={{ width: `${demo.elapsed / DEMO_DURATION * 100}%` }} />
      </div>
      <svg className="sensor-demo-chart" viewBox="0 0 340 135" role="img" aria-label="Base and upper wall moisture over two minutes">
        {[20, 60, 100].map((v) => <g key={v}><line x1="28" x2="320" y1={y(v)} y2={y(v)} /><text x="2" y={y(v) + 4}>{v}</text></g>)}
        {SERIES.map((series) => <polyline key={series.id} fill="none" stroke={series.color} strokeWidth="2.5"
          points={demo.samples.map((sample) => `${x(sample.second)},${y(series.id === "B01-S04" ? sample.base : sample.upper)}`).join(" ")} />)}
        <line className="cursor" x1={x(demo.elapsed)} x2={x(demo.elapsed)} y1="12" y2="112" />
        <text x="27" y="130">0:00</text><text x="282" y="130">2:00</text>
      </svg>
      <div className="sensor-demo-legend">{SERIES.map((s) => <span key={s.id}><i style={{ background: s.color }} />{s.label}</span>)}</div>
      <div className="sensor-demo-values">
        <span>Base <b>{reading("B01-S04")} %rel</b></span>
        <span>Upper <b>{reading("B01-S05")} %rel</b></span>
        <span>Humidity <b>{reading("B01-S03")} %RH</b></span>
        <span>Temperature <b>{reading("B01-S02")} °C</b></span>
        <span>Crack S06 <b>{reading("B01-S06", 3)} mm</b></span>
        <span>S04 status <b>{demo.readings["B01-S04"]?.status ?? demo.readings["B01-S04"]?.quality ?? "—"}</b></span>
      </div>
    </section>
  );
}
