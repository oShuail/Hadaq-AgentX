// Diriyah site map (owner: Omar): React wrapper around the three.js twin in twin3d/Twin.ts.
import { useEffect, useRef, useState } from "react";

import { FOCUS_MODEL_URL } from "../config";
import { type LayerId, Twin, type TwinSelection, type TwinStats, type ViewId } from "../twin3d/Twin";
import type { Callout } from "../twin3d/focus";
import type { AttentionItem } from "../twin/WallZones";
import type { Escalation, WallSpec } from "../types/contracts";

export type Selection = TwinSelection;
// a camera view, or "twin:<building id>" to fly to one of the twin buildings (the Priority list)
export type FlyTarget = ViewId | `twin:${string}`;

interface Props {
  buildingId: string;
  wall: WallSpec | null;
  zoneColors: Record<string, string>;
  attention: AttentionItem[];
  callouts: Callout[];
  zonesVisible: boolean;
  selectedZone: string | null;
  fly: { target: FlyTarget; nonce: number };
  /** Worst open escalation per twin building (core API site priority): tints the ground under it. */
  escalations?: Record<string, Escalation | null>;
  time: Date;
  layers: Record<LayerId, boolean>;
  paused?: boolean; // a building page covers the map: stop drawing
  onSelect: (selection: Selection | null) => void;
  onCoordinate: (lon: number, lat: number, height: number) => void;
  onStats: (stats: TwinStats) => void;
}

export default function SiteMap(props: Props) {
  const container = useRef<HTMLDivElement>(null);
  const twinRef = useRef<Twin | null>(null);
  const propsRef = useRef(props);
  propsRef.current = props;
  const [status, setStatus] = useState<{ loading: boolean; message: string; error?: boolean }>({ loading: true, message: "Starting" });
  const [ready, setReady] = useState(false);

  // Build the twin once.
  useEffect(() => {
    if (!container.current) return;
    const twin = new Twin(container.current, {
      onStatus: (s) => {
        setStatus(s);
        if (!s.loading && !s.error) setReady(true);
      },
      onSelect: (s) => propsRef.current.onSelect(s),
      onStats: (s) => propsRef.current.onStats(s),
      onCoordinate: (lon, lat, h) => propsRef.current.onCoordinate(lon, lat, h),
    });
    twinRef.current = twin;
    const p = propsRef.current;
    for (const [id, on] of Object.entries(p.layers)) twin.setLayer(id as LayerId, on);
    twin.setTime(p.time);
    twin.load(p.wall, p.buildingId, FOCUS_MODEL_URL).catch((err) => {
      console.error("Twin failed to load", err);
      setStatus({ loading: false, error: true, message: `The 3D model failed to load: ${String(err)}` });
    });
    if (import.meta.env.DEV) Object.assign(window, { __twin: twin }); // console debugging
    return () => {
      twin.dispose();
      twinRef.current = null;
    };
  }, []);

  useEffect(() => twinRef.current?.setTime(props.time), [props.time]);
  useEffect(() => twinRef.current?.setPaused(!!props.paused), [props.paused]);
  useEffect(() => {
    const t = twinRef.current;
    if (!t) return;
    for (const [id, on] of Object.entries(props.layers)) t.setLayer(id as LayerId, on);
    t.setZonesVisible(props.zonesVisible && props.layers.twin);
  }, [props.layers, props.zonesVisible, ready]);
  useEffect(() => twinRef.current?.setZoneColors(props.zoneColors), [props.zoneColors, ready]);
  useEffect(() => twinRef.current?.setAttention(props.attention), [props.attention, ready]);
  useEffect(() => twinRef.current?.setCallouts(props.callouts), [props.callouts, ready]);
  useEffect(() => twinRef.current?.setSelectedZone(props.selectedZone), [props.selectedZone, ready]);
  useEffect(() => twinRef.current?.setEscalations(props.escalations ?? {}), [props.escalations, ready]);
  useEffect(() => {
    if (props.fly.nonce > 0) twinRef.current?.flyTo(props.fly.target);
  }, [props.fly.nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="site-map-wrap">
      <div ref={container} className="site-map" />
      {(status.loading || status.error) && (
        <div className={`map-status ${status.error ? "error" : ""}`}>
          {!status.error && <span className="spinner" />}
          {status.message}
        </div>
      )}
    </div>
  );
}
