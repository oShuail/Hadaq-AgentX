// The site manager's ranking (T3, owner: Turki): GET /api/v2/site/priority from the core API, reloaded whenever a
// v2 live message says the findings may have changed (new findings, a review, an approval, a demo reset).
import { useEffect, useState } from "react";

import { connectLive } from "../api/client";
import { core } from "../api/core";
import { CORE_WS } from "../config";
import type { Escalation, SitePriorityRow } from "../types/contracts";

export interface SitePriority {
  rows: SitePriorityRow[];
  online: boolean; // the core API answered
  loaded: boolean;
  /** Worst open escalation per building id (null = nothing open), for the tint on the map. */
  escalations: Record<string, Escalation | null>;
}

const RELOAD_ON = new Set(["finding", "review", "inspection", "capture"]);

export function useSitePriority(): SitePriority {
  const [rows, setRows] = useState<SitePriorityRow[]>([]);
  const [online, setOnline] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;
    const load = () =>
      core
        .sitePriority()
        .then((r) => {
          if (!alive) return;
          setRows(r);
          setOnline(true);
          setLoaded(true);
        })
        .catch(() => {
          if (!alive) return;
          setOnline(false);
          setLoaded(true);
        });
    load();
    // a capture is analysed in a few seconds; one reload per burst of messages is enough
    let timer: number | undefined;
    const soon = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(load, 400);
    };
    const stop = connectLive(CORE_WS, (m) => RELOAD_ON.has(m.channel) && soon(), (up) => up && soon());
    return () => {
      alive = false;
      window.clearTimeout(timer);
      stop();
    };
  }, []);

  const escalations = Object.fromEntries(rows.map((r) => [r.building_id, r.worst_escalation]));
  return { rows, online, loaded, escalations };
}
