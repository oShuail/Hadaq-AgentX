// The site manager's question «وين المناطق الأكثر خطورة؟» (T3, owner: Turki): At-Turaif ranked by risk from the core
// API (GET /api/v2/site/priority), urgent first, then the longest since the last survey. A click flies to the building.
import { CORE_URL } from "../config";
import type { SitePriority } from "../state/useSitePriority";
import { ESCALATION_NAMES_AR } from "../types/contracts";

const days = (iso: string | null) => (iso ? Math.max(0, Math.floor((Date.now() - Date.parse(iso)) / 864e5)) : null);

export default function PriorityPanel({ priority, onFly }: { priority: SitePriority; onFly: (buildingId: string) => void }) {
  const { rows, online, loaded } = priority;
  if (!loaded) return <p className="muted">Loading the ranking…</p>;
  if (!online) {
    return (
      <p className="muted">
        The core API is not reachable at {CORE_URL}. Start it with <code>make core</code> (or set VITE_CORE_URL).
      </p>
    );
  }
  const surveyed = rows.filter((r) => r.last_survey_at).length;
  const urgent = rows.filter((r) => r.worst_escalation === "urgent").length;

  return (
    <div className="priority-panel">
      <p className="priority-sub" dir="rtl" lang="ar">
        {surveyed} مبنى تم مسحه هذه الدورة · {urgent} عاجل
      </p>
      <ol className="priority-list">
        {rows.map((r, i) => {
          const d = days(r.last_survey_at);
          const counts = Object.entries(r.open_findings)
            .map(([esc, n]) => `${n} ${ESCALATION_NAMES_AR[esc as keyof typeof ESCALATION_NAMES_AR] ?? esc}`)
            .join(" · ");
          return (
            <li key={r.building_id}>
              <button className="priority-row" onClick={() => onFly(r.building_id)} title={`Fly to ${r.name_en}`}>
                <span className="priority-rank">{i + 1}</span>
                <span className="priority-main" dir="rtl" lang="ar">
                  <span className="priority-head">
                    {r.worst_escalation ? (
                      <span className={`esc esc-${r.worst_escalation}`}>{ESCALATION_NAMES_AR[r.worst_escalation]}</span>
                    ) : (
                      <span className="esc esc-none">لا يوجد</span>
                    )}
                    <b>{r.name_ar}</b>
                    <span className="priority-en" dir="ltr">{r.name_en}</span>
                  </span>
                  <span className="priority-why">
                    {r.top_reason_ar ?? (r.last_survey_at ? "لا تلف مفتوح" : "لم يُمسح بعد")}
                  </span>
                  <span className="priority-when">
                    {d === null ? "لم يُمسح" : d === 0 ? "آخر مسح اليوم" : `آخر مسح قبل ${d} يوم`}
                    {counts && <> · {counts}</>}
                  </span>
                </span>
              </button>
            </li>
          );
        })}
      </ol>
      <p className="muted priority-note">
        Ranked by the core API: DGDA criteria with weights ASSUMED; the conservator's review decides.
      </p>
    </div>
  );
}
