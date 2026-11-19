// Explore tab (owner: Omar): camera views, layer switches with counts, what the model is made of, sources.
import { LAYERS, type LayerId, type TwinStats, VIEWS, type ViewId } from "../twin3d/Twin";

interface Props {
  view: ViewId | null;
  onView: (v: ViewId) => void;
  layers: Record<LayerId, boolean>;
  setLayer: (id: LayerId, on: boolean) => void;
  stats: TwinStats | null;
}

export default function ExplorePanel({ view, onView, layers, setLayer, stats }: Props) {
  return (
    <div className="explore">
      <section>
        <h3>Views</h3>
        <div className="views">
          {VIEWS.map((v) => (
            <button key={v.id} className={view === v.id ? "on" : ""} onClick={() => onView(v.id)}>
              {v.label}
            </button>
          ))}
        </div>
      </section>

      <section>
        <h3>Layers</h3>
        <ul className="layers">
          {LAYERS.map((l) => (
            <li key={l.id}>
              <label>
                <input type="checkbox" checked={layers[l.id]} onChange={(e) => setLayer(l.id, e.target.checked)} />
                <span>{l.label}</span>
              </label>
              <span className="count">{stats?.counts[l.id] !== undefined ? stats.counts[l.id]!.toLocaleString() : ""}</span>
            </li>
          ))}
        </ul>
      </section>

      {stats && (
        <section>
          <h3>Model</h3>
          <dl className="facts">
            {stats.model.map((m) => (
              <div key={m.label}>
                <dt>{m.label}</dt>
                <dd>{m.value}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}

      <section>
        <h3>About</h3>
        <p className="about">
          Context model of Diriyah built from open data. Heights are estimates, the ruins filled into At-Turaif are
          illustrative, and visitors and cars are simulated. The focus building B01 is our own 3D model with live
          (partly simulated) sensors.
        </p>
        {stats && (
          <p className="about small">
            {stats.sources.join(" · ")}
            <br />
            Data baked {new Date(stats.generated).toLocaleDateString()}
          </p>
        )}
      </section>
    </div>
  );
}
