// The latest field inspections of a building from the core API, each a link to its review page (/inspection/<id>).
// Used on the building page and in the Activity tab. Reloads on the core's v2 live messages.
import { useEffect, useState } from "react";

import { connectLive } from "../api/client";
import { core } from "../api/core";
import { CORE_WS } from "../config";
import type { Inspection } from "../types/contracts";
import { ESCALATION_NAMES_AR } from "../types/contracts";
import { pendingCount, worstEscalation } from "./model";

interface Props {
  buildingId: string;
  onOpen: (inspectionId: string) => void;
  limit?: number;
}

const RELOAD_ON = new Set(["finding", "review", "inspection", "capture"]);

export default function InspectionLinks({ buildingId, onOpen, limit = 3 }: Props) {
  const [rows, setRows] = useState<Inspection[] | null>(null);
  const [online, setOnline] = useState(true);

  useEffect(() => {
    let alive = true;
    const load = () =>
      core
        .buildingInspections(buildingId)
        .then((r) => alive && (setRows(r), setOnline(true)))
        .catch(() => alive && (setOnline(false), setRows((old) => old ?? [])));
    load();
    let timer: number | undefined;
    const stop = connectLive(
      CORE_WS,
      (m) => {
        if (!RELOAD_ON.has(m.channel)) return;
        window.clearTimeout(timer);
        timer = window.setTimeout(load, 400);
      },
      () => undefined,
    );
    return () => {
      alive = false;
      window.clearTimeout(timer);
      stop();
    };
  }, [buildingId]);

  if (!online) return <p className="placeholder">Field inspections: the core API is offline.</p>;
  if (rows === null) return <p className="placeholder">Loading field inspections...</p>;
  if (rows.length === 0) return <p className="placeholder">No field inspection yet. Photos from the surveyor app appear here once analysed.</p>;

  return (
    <ul className="ilinks">
      {rows.slice(0, limit).map((i) => {
        const worst = worstEscalation(i.findings);
        const pending = pendingCount(i);
        return (
          <li key={i.inspection_id}>
            <button onClick={() => onOpen(i.inspection_id)}>
              <span className="iltitle">
                Wall {i.wall_id} · {new Date(i.started_at).toLocaleDateString()}
                {worst && <span className={`esc esc-${worst}`}>{ESCALATION_NAMES_AR[worst]}</span>}
              </span>
              <span className="muted">
                {i.findings.length} finding{i.findings.length === 1 ? "" : "s"} ·{" "}
                {i.status === "approved" ? "approved" : pending ? `${pending} to review` : "ready to approve"} · Open review
              </span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}
