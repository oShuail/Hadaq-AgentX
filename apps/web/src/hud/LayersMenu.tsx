// Top-right (owner: Omar): connection status, the "Simulated data" badge and the Layers button with its menu.
import { useEffect, useRef, useState } from "react";

import { LAYERS, type LayerId, type TwinStats } from "../twin3d/Twin";

interface Props {
  connected: boolean;
  layers: Record<LayerId, boolean>;
  setLayer: (id: LayerId, on: boolean) => void;
  stats: TwinStats | null;
}

export default function LayersMenu({ connected, layers, setLayer, stats }: Props) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const on = LAYERS.filter((l) => layers[l.id]).length;

  // Close on a click elsewhere or Escape.
  useEffect(() => {
    if (!open) return;
    const away = (e: PointerEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("pointerdown", away);
    window.addEventListener("keydown", esc);
    return () => {
      window.removeEventListener("pointerdown", away);
      window.removeEventListener("keydown", esc);
    };
  }, [open]);

  return (
    <div className="tower-tr" ref={root}>
      <div className="hud-status">
        <span className={`hud-chip conn ${connected ? "up" : ""}`}>
          <i />
          {connected ? "Live" : "Offline"}
        </span>
        <span className="hud-chip sim">Simulated data</span>
      </div>
      <button className={`layers-btn ${open ? "open" : ""}`} aria-expanded={open} aria-controls="layers-menu" onClick={() => setOpen((o) => !o)}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
          <path d="M12 3l9 5-9 5-9-5z" />
          <path d="M3 13l9 5 9-5" />
        </svg>
        <span className="lbl">Layers</span>
        <span className="count">
          {on}/{LAYERS.length}
        </span>
        <svg className="chev" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
      {open && (
        <div id="layers-menu" className="layers-menu" role="group" aria-label="Layers">
          {LAYERS.map((l) => {
            const count = stats?.counts[l.id];
            return (
              <button key={l.id} aria-pressed={layers[l.id]} className={layers[l.id] ? "" : "off"} onClick={() => setLayer(l.id, !layers[l.id])}>
                <span>{l.label}</span>
                <span className="n">{count !== undefined ? count.toLocaleString() : ""}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
