// A building's own twin page (owner: Omar), opened from the map at /building/<id>: the building in 3D,
// the condition of every wall zone, live sensors, cameras, activity and the agent. Buildings without a
// registry in contracts/ yet (twins.ts `registered: false`) show the same layout with placeholders.
import { useState } from "react";

import Brand from "../Brand";
import AgentChat from "../chat/AgentChat";
import CameraFeed, { type PhotoPrivacy } from "../panel/CameraFeed";
import EventFeed from "../panel/EventFeed";
import type { LiveState } from "../state/useLive";
import { ATTENTION_COLORS, type AttentionItem } from "../twin/WallZones";
import { RISK_COLORS, type WallLayer, moistureColor } from "../twin/colors";
import { DAMAGE_FAMILIES, FAMILY_ORDER, familyOf } from "../twin/damage";
import { type TwinDef, twinTitle } from "../twins";
import type { RiskLevel, WallSpec } from "../types/contracts";
import DamageMap from "./DamageMap";
import ModelViewer from "./ModelViewer";

interface Props {
  twin: TwinDef;
  live: LiveState;
  walls: WallSpec[];
  zoneColors: Record<string, string>;
  attention: AttentionItem[];
  layer: WallLayer;
  setLayer: (l: WallLayer) => void;
  hour: number;
  cameraWallBox: number[] | null;
  onBack: () => void;
}

const LEVELS: RiskLevel[] = ["low", "medium", "high", "very_high"];

export default function BuildingPage({ twin, live, walls, zoneColors, attention, layer, setLayer, hour, cameraWallBox, onBack }: Props) {
  const [zone, setZone] = useState<string | null>(null);
  const reg = twin.registered;
  const sensors = reg ? live.building?.sensors ?? [] : [];
  const worst = reg
    ? live.risk.reduce<RiskLevel | null>((acc, r) => (acc === null || LEVELS.indexOf(r.level) > LEVELS.indexOf(acc) ? r.level : acc), null)
    : null;
  const zoneRisk = zone ? live.risk.find((r) => r.zone_id === zone) : undefined;
  const zoneMoisture = zone && live.simulation ? live.simulation.zones[zone]?.[hour] : undefined;
  const zoneSensors = zone ? sensors.filter((s) => s.zone_id === zone) : [];
  const flagged = new Map(attention.map((a) => [a.zoneId, a]));
  const damageItems = reg ? live.damageReport?.report.items ?? [] : [];
  const zoneDamage = zone ? damageItems.filter((d) => d.zone_id === zone) : [];
  const damageFamilies = FAMILY_ORDER.filter((f) => damageItems.some((d) => familyOf(d.damage_type) === f));
  const photoPrivacy = (photoId: string | undefined): PhotoPrivacy | null =>
    (live.events.find((e) => e.type === "new_photo" && e.payload.photo_id === photoId)?.payload.privacy as PhotoPrivacy | undefined) ?? null;

  return (
    <div className="bpage">
      <header className="topbar">
        <button className="back" onClick={onBack} aria-label="Back to the map">
          <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" strokeWidth="1.8" fill="none" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          Map
        </button>
        <Brand title={twinTitle(twin)} subtitle={`Building twin ${twin.id}${twin.mapLabel ? ` · "${twin.mapLabel}" on the map` : ""}`} />
        <div className="top-right">
          {reg && (
            <span className={`conn ${live.connected ? "up" : ""}`}>
              <i />
              {live.connected ? "Live" : "Offline"}
            </span>
          )}
          <span className="badge sim">Simulated data</span>
        </div>
      </header>

      <main className="bpage-main">
        <section className="bpage-view">
          {twin.view.kind === "model" ? (
            <ModelViewer
              url={twin.view.url}
              wall={walls[0] ?? null}
              buildingId={twin.id}
              zoneColors={zoneColors}
              attention={attention}
              selectedZone={zone}
              onSelectZone={setZone}
            />
          ) : (
            <div className="scan-frame">
              <iframe
                title={twin.view.scan.title}
                src={`https://sketchfab.com/models/${twin.view.scan.uid}/embed?autostart=1&preload=1&ui_theme=dark&ui_hint=0`}
                allow="autoplay; fullscreen; xr-spatial-tracking"
                allowFullScreen
              />
              <p className="scan-credit">
                <a href={twin.view.scan.modelUrl} target="_blank" rel="nofollow noreferrer">{twin.view.scan.title}</a> by{" "}
                <a href={twin.view.scan.authorUrl} target="_blank" rel="nofollow noreferrer">{twin.view.scan.author}</a> on{" "}
                <a href="https://sketchfab.com" target="_blank" rel="nofollow noreferrer">Sketchfab</a>
              </p>
            </div>
          )}
          <p className="bpage-note">{twin.note}</p>
        </section>

        <aside className="bpage-side">
          <div className="panel-head">
            <h2>{twinTitle(twin)}</h2>
            {worst ? (
              <span className="badge" style={{ borderColor: RISK_COLORS[worst], color: RISK_COLORS[worst] }}>
                Risk: {worst.replace("_", " ")}
              </span>
            ) : (
              <span className="badge muted">{reg ? "No risk yet" : "Not monitored yet"}</span>
            )}
          </div>

          {/* Wall condition */}
          <section className="bsec">
            <div className="bsec-head">
              <h3>Wall condition</h3>
              {reg && (
                <div className="seg">
                  <button className={layer === "damage" ? "on" : ""} onClick={() => setLayer("damage")}>Damage</button>
                  <button className={layer === "moisture" ? "on" : ""} onClick={() => setLayer("moisture")}>Moisture</button>
                  <button className={layer === "risk" ? "on" : ""} onClick={() => setLayer("risk")}>Risk</button>
                </div>
              )}
            </div>
            {reg && walls.length ? (
              walls.map((w) => (
                <div key={w.id} className="facade">
                  <div className="facade-title">
                    <strong>{w.id}</strong> <span className="muted small">{w.name} · faces {Math.round(w.azimuth_deg)}° · {w.height_m} m high</span>
                  </div>
                  <div className="facade-grid" style={{ gridTemplateColumns: `repeat(${w.cols}, 1fr)` }}>
                    {Array.from({ length: w.rows }, (_, i) => w.rows - i).map((r) =>
                      Array.from({ length: w.cols }, (_, c) => c + 1).map((c) => {
                        const id = `${twin.id}-${w.id}-R${r}C${c}`;
                        const flag = flagged.get(id);
                        return (
                          <button
                            key={id}
                            className={`cell ${zone === id ? "sel" : ""}`}
                            style={{
                              background: zoneColors[id] ?? "rgba(217, 195, 160, 0.25)",
                              outline: flag ? `2px solid ${ATTENTION_COLORS[flag.level]}` : undefined,
                            }}
                            title={`${id}${flag ? ` · ${flag.reasons.join(", ")}` : ""}`}
                            onClick={() => setZone(id)}
                          >
                            R{r}C{c}
                          </button>
                        );
                      }),
                    )}
                  </div>
                  <p className="muted small">Row 1 is the wall base. Columns run left to right facing the facade. Click a zone, here or on the model.</p>
                </div>
              ))
            ) : (
              <p className="placeholder">Wall zones are not mapped for this building yet. They come with its registry entry in contracts/ and its first sensors.</p>
            )}
            {reg && (
              <div className="legend small">
                {layer === "damage" ? (
                  damageFamilies.length ? (
                    damageFamilies.map((f) => (
                      <span key={f}>
                        <i className={DAMAGE_FAMILIES[f].dashed ? "dashed" : ""} style={{ background: DAMAGE_FAMILIES[f].color }} /> {DAMAGE_FAMILIES[f].label}
                      </span>
                    ))
                  ) : (
                    <span className="muted">no damage report yet</span>
                  )
                ) : layer === "moisture" ? (
                  <>
                    <span><i style={{ background: moistureColor(0.15) }} /> dry</span>
                    <span><i style={{ background: moistureColor(0.5) }} /> damp</span>
                    <span><i style={{ background: moistureColor(0.9) }} /> wet</span>
                    {!live.simulation && <span className="muted">run a simulation to colour the zones</span>}
                  </>
                ) : (
                  LEVELS.map((l) => (
                    <span key={l}><i style={{ background: RISK_COLORS[l] }} /> {l.replace("_", " ")}</span>
                  ))
                )}
              </div>
            )}
            {zone && (
              <div className="zone-facts">
                <strong>{zone}</strong>
                <div>Simulated moisture now: {zoneMoisture !== undefined ? zoneMoisture.toFixed(2) : "run a simulation"}</div>
                <div>
                  Risk: {zoneRisk ? `${zoneRisk.level.replace("_", " ")} (A ${zoneRisk.A} + B ${zoneRisk.B} + C ${zoneRisk.C} = ${zoneRisk.magnitude})` : "not scored yet"}
                </div>
                <div>Sensors here: {zoneSensors.length ? zoneSensors.map((s) => s.label).join(", ") : "none"}</div>
                <div>
                  Damage here:{" "}
                  {zoneDamage.length
                    ? zoneDamage.map((d) => `${d.name_en}${d.severity ? ` (${d.severity}${d.active ? ", growing" : ""})` : ""}: ${d.step_en}`).join(" · ")
                    : "none in the latest report"}
                </div>
              </div>
            )}
            {attention.length > 0 && (
              <ul className="attention-list">
                {attention.map((a) => (
                  <li key={a.zoneId}>
                    <button onClick={() => setZone(a.zoneId)} style={{ borderColor: ATTENTION_COLORS[a.level] }}>
                      <strong>{a.zoneId.split("-").slice(-1)[0]}</strong> {a.reasons.join(" · ")}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* Damage map: the agent's latest damage report on its photo */}
          <section className="bsec">
            <h3>Damage map</h3>
            {reg ? (
              <DamageMap
                key={live.damageReport?.eventId ?? "none"}
                damage={live.damageReport}
                wallBox={cameraWallBox}
                rows={walls[0]?.rows ?? 4}
                cols={walls[0]?.cols ?? 8}
              />
            ) : (
              <p className="placeholder">No damage report for this building yet.</p>
            )}
          </section>

          {/* Live sensors */}
          <section className="bsec">
            <h3>Live sensors</h3>
            {sensors.length ? (
              <ul className="sensors">
                {sensors.map((s) => {
                  const r = live.readings[s.id];
                  const real = r?.provenance === "measured";
                  return (
                    <li key={s.id} className={r?.quality === "fault" ? "fault" : ""}>
                      <span>
                        {s.label} <span className="muted small">{s.zone_id.split("-").slice(-1)[0]}</span>
                      </span>
                      <span className="value">{r ? `${r.value} ${r.unit}` : "no data"}</span>
                      <span className={`tag ${real ? "real" : ""}`}>{r ? (real ? "REAL" : "SIM") : ""}</span>
                    </li>
                  );
                })}
              </ul>
            ) : (
              <p className="placeholder">{reg ? "No sensors reporting. Start the API and the sensor simulator (make api, make sensors)." : "No sensors installed on this building yet."}</p>
            )}
          </section>

          {/* Cameras */}
          <section className="bsec">
            <h3>Cameras</h3>
            {reg ? (
              <CameraFeed photo={live.photo} detections={live.detections} privacy={photoPrivacy(live.photo?.photo_id)} />
            ) : (
              <p className="placeholder">No camera on this building yet.</p>
            )}
          </section>

          {/* Activity */}
          <section className="bsec">
            {reg ? <EventFeed events={live.events} /> : (
              <>
                <h3>Activity</h3>
                <p className="placeholder">Nothing yet.</p>
              </>
            )}
          </section>

          {/* Agent */}
          <section className="bsec">
            <h3>Ask the agent</h3>
            {reg ? <AgentChat buildingId={twin.id} /> : <p className="placeholder">The agent works on registered buildings only (B01 for now).</p>}
          </section>
        </aside>
      </main>
    </div>
  );
}
