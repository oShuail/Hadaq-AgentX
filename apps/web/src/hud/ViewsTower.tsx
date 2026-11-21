// Bottom-left tower (owner: Omar): the six camera views, "About and sources" (what the model is made of,
// the honesty notes, the sources and the bake date) and the map attribution.
import { useState } from "react";

import { type TwinStats, VIEWS, type ViewId } from "../twin3d/Twin";

interface Props {
  view: ViewId | null;
  onView: (v: ViewId) => void;
  stats: TwinStats | null;
}

export default function ViewsTower({ view, onView, stats }: Props) {
  const [about, setAbout] = useState(false);
  return (
    <div className="tower tower-bl">
      {about && (
        <div className="about-pop" role="dialog" aria-label="About and sources">
          <div className="about-head">
            <strong>About this model</strong>
            <button className="x" aria-label="Close" onClick={() => setAbout(false)}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </button>
          </div>
          {stats && (
            <dl className="facts">
              {stats.model.map((m) => (
                <div key={m.label}>
                  <dt>{m.label}</dt>
                  <dd>{m.value}</dd>
                </div>
              ))}
            </dl>
          )}
          <p className="about">
            Context model of Diriyah built from open data. Heights are estimates, the ruins filled into At-Turaif are
            illustrative, and visitors and cars are simulated. Qasr Salwa (B01) is an AI-generated model at a
            placeholder spot with live, partly simulated sensors.
          </p>
          {stats && (
            <p className="about small">
              {stats.sources.join(" · ")}
              <br />
              Data baked {new Date(stats.generated).toLocaleDateString()}
            </p>
          )}
        </div>
      )}
      <div className="tower-body">
        <div className="tower-head">
          <span>Views</span>
          <button className="link" aria-expanded={about} onClick={() => setAbout((a) => !a)}>
            About and sources
          </button>
        </div>
        <div className="hud-views" role="group" aria-label="Camera views">
          {VIEWS.map((v) => (
            <button key={v.id} aria-pressed={view === v.id} className={view === v.id ? "on" : ""} onClick={() => onView(v.id)}>
              {v.label}
            </button>
          ))}
        </div>
        <p className="hud-fine">
          © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a> · Microsoft footprints · Esri imagery · AWS terrain
        </p>
      </div>
    </div>
  );
}
