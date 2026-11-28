// The conservator's review page (O4), /inspection/<id>: the rectified photo with numbered marks, the urgent banner
// with DGDA's action text, one row per finding (Accept, Correct, Reject), Approve and Print. Reloads on the core's
// live messages for this inspection. The printed report is its own block (print only) and shows every correction.
import { type ReactNode, useCallback, useEffect, useMemo, useState } from "react";

import { connectLive } from "../api/client";
import { CoreError, core } from "../api/core";
import Brand from "../Brand";
import { CORE_WS } from "../config";
import { DAMAGE_FAMILIES, FAMILY_ORDER, familyOf } from "../twin/damage";
import type { Finding, Inspection, Registry, WorkOrderDraft } from "../types/contracts";
import { ESCALATION_NAMES_AR, SEVERITY_NAMES_AR } from "../types/contracts";
import FindingRow from "./FindingRow";
import {
  CLOSING_AR,
  REVIEW_NAMES_AR,
  URGENT_ACTION_AR,
  URGENT_ACTION_EN,
  buildingName,
  effectiveSeverity,
  effectiveType,
  mainCapture,
  nameAr,
  numbered,
  pendingCount,
  roleAr,
  shortZone,
  urgentOpen,
  wallOf,
} from "./model";
import WallPhoto from "./WallPhoto";
import "./review.css";

interface Props {
  inspectionId: string;
  onBack: () => void;
}

const RELOAD_ON = new Set(["finding", "review", "inspection", "work_order", "capture"]);

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString("ar-SA-u-nu-latn", { dateStyle: "medium", timeStyle: "short" }) : "");

export default function InspectionPage({ inspectionId, onBack }: Props) {
  const [inspection, setInspection] = useState<Inspection | null>(null);
  const [registry, setRegistry] = useState<Registry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [showZones, setShowZones] = useState(false);
  const [approving, setApproving] = useState(false);
  const [approveError, setApproveError] = useState<string | null>(null);

  const load = useCallback(
    () =>
      core
        .inspection(inspectionId)
        .then((i) => {
          setInspection(i);
          setError(null);
        })
        .catch((e) => setError(e instanceof CoreError && e.status === 404 ? "ما فيه فحص بهذا الرقم" : `الخادم ما رد: ${(e as Error).message}`)),
    [inspectionId],
  );

  useEffect(() => {
    load();
    core.registry().then(setRegistry).catch(() => setRegistry(null));
    let timer: number | undefined;
    const soon = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(load, 300);
    };
    // the core's v2 channels; only messages about this inspection (or a new capture) trigger a reload
    const stop = connectLive(
      CORE_WS,
      (m) => {
        if (!RELOAD_ON.has(m.channel)) return;
        const id = (m.data as { inspection_id?: string | null }).inspection_id;
        if (!id || id === inspectionId) soon();
      },
      (up) => up && soon(),
    );
    return () => {
      window.clearTimeout(timer);
      stop();
    };
  }, [inspectionId, load]);

  const items = useMemo(() => numbered(inspection?.findings ?? []), [inspection]);
  const numberOf = useMemo(() => new Map(items.map(({ n, finding }) => [finding.finding_id, n])), [items]);

  const replace = (updated: Finding) =>
    setInspection((i) => (i ? { ...i, findings: i.findings.map((f) => (f.finding_id === updated.finding_id ? updated : f)) } : i));

  const approve = async () => {
    if (!inspection) return;
    setApproving(true);
    setApproveError(null);
    try {
      setInspection(await core.approve(inspection.inspection_id));
    } catch (e) {
      setApproveError(e instanceof CoreError && e.status === 409 ? "راجع كل النتائج أولاً" : `ما تم الاعتماد: ${(e as Error).message}`);
    } finally {
      setApproving(false);
    }
  };

  if (!inspection) {
    return (
      <div className="ipage">
        <TopBar onBack={onBack} title="مراجعة الفحص" />
        <p className="iempty">{error ?? "جاري التحميل..."}</p>
      </div>
    );
  }

  const wall = wallOf(registry, inspection.building_id, inspection.wall_id);
  const name = buildingName(registry, inspection.building_id);
  const capture = mainCapture(inspection);
  const imageUrl = capture?.rectified_url ?? null;
  const urgent = urgentOpen(inspection.findings);
  const pending = pendingCount(inspection);
  const approved = inspection.status === "approved";
  const title = `${name.ar} · ${inspection.wall_id}`;

  return (
    <div className="ipage" dir="rtl" lang="ar">
      <div className="iscreen">
        <TopBar onBack={onBack} title={title}>
          <span className={`badge ${approved ? "ok" : "warn"}`}>{approved ? "معتمد" : "مسودة بانتظار اعتماد المختص"}</span>
          <span className={`conn ${error ? "" : "up"}`}>
            <i />
            {error ? "Offline" : "Live"}
          </span>
        </TopBar>

        {urgent.length > 0 && (
          <div className="iurgent" role="alert">
            <strong>عاجل ({urgent.length}): {urgent.map((f) => `D${numberOf.get(f.finding_id)} ${shortZone(f.zone_id)}`).join("، ")}</strong>
            <span>{URGENT_ACTION_AR}</span>
            <small dir="ltr">{URGENT_ACTION_EN} (DGDA Q14). The agent classifies and escalates; the conservator decides.</small>
          </div>
        )}

        <main className="imain">
          <section className="iview">
            <div className="ivhead">
              <span>
                {wall ? `${wall.name} · ${wall.width_m} × ${wall.height_m} م` : inspection.wall_id}
                {capture ? ` · صورة ${when(capture.captured_at)}` : ""}
                {capture?.scale === "relative" ? " · قياس نسبي" : ""}
              </span>
              <label className="ztoggle">
                <input type="checkbox" checked={showZones} onChange={(e) => setShowZones(e.target.checked)} /> شبكة المناطق
              </label>
            </div>
            {wall ? (
              <WallPhoto wall={wall} imageUrl={imageUrl} items={items} selected={selected} onSelect={setSelected} showZones={showZones} />
            ) : (
              <p className="iempty">ما وصل سجل الجدار من الخادم، فما نقدر نرسم العلامات. القائمة تعمل.</p>
            )}
            <Legend findings={inspection.findings} />
            <p className="inote" dir="ltr">
              Findings are model output ({capture?.provenance ?? "model_output"} photo, detector {inspection.findings[0]?.model ?? "n/a"}).
              Priority weights are ASSUMED. Work orders are drafts that need a field check.
            </p>
          </section>

          <aside className="iside">
            <div className="ihead">
              <h2>{title}</h2>
              <p className="muted">
                {inspection.findings.length} نتيجة · {pending ? `${pending} بانتظار المراجعة` : "كل النتائج مراجعة"}
                {approved ? ` · اعتمده ${roleAr(inspection.approved_by)} ${when(inspection.approved_at)}` : ""}
              </p>
            </div>
            <div className="ilist">
              {items.length === 0 && <p className="iempty">ما فيه نتائج في هذا الفحص.</p>}
              {items.map(({ n, finding }) => (
                <FindingRow
                  key={`${finding.finding_id}:${finding.review.status}:${finding.review.at ?? ""}`}
                  n={n}
                  finding={finding}
                  selected={selected === finding.finding_id}
                  locked={approved}
                  onSelect={() => setSelected(finding.finding_id)}
                  onReviewed={replace}
                />
              ))}
            </div>
            <div className="iactions">
              {!approved && (
                <button className="approve" disabled={approving || pending > 0} onClick={approve}>
                  {pending > 0 ? `اعتماد الفحص (باقي ${pending})` : "اعتماد الفحص"}
                </button>
              )}
              <button onClick={() => window.print()}>طباعة التقرير</button>
              {approveError && <p className="ferr">{approveError}</p>}
            </div>
          </aside>
        </main>
      </div>

      <PrintReport inspection={inspection} title={title} wallLabel={wall?.name ?? inspection.wall_id} wall={wall} imageUrl={imageUrl} />
    </div>
  );
}

// colour key: one colour per damage family (as on the building page), dashed = suspected or past repair
function Legend({ findings }: { findings: Finding[] }) {
  const families = FAMILY_ORDER.filter((fam) => findings.some((f) => familyOf(effectiveType(f)) === fam));
  return (
    <ul className="ilegend">
      {families.map((fam) => (
        <li key={fam}>
          <i style={{ borderColor: DAMAGE_FAMILIES[fam].color, borderStyle: DAMAGE_FAMILIES[fam].dashed ? "dashed" : "solid" }} />
          {DAMAGE_FAMILIES[fam].labelAr}
        </li>
      ))}
      {findings.some((f) => f.suspected) && (
        <li>
          <i className="dash" />
          اشتباه
        </li>
      )}
      {findings.some((f) => f.review.status === "rejected") && (
        <li>
          <i className="rej" />
          مرفوض
        </li>
      )}
    </ul>
  );
}

function TopBar({ onBack, title, children }: { onBack: () => void; title: string; children?: ReactNode }) {
  return (
    <header className="topbar">
      <button className="back" onClick={onBack} aria-label="Back">
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
          <path d="M6 3l5 5-5 5" stroke="currentColor" strokeWidth="1.8" fill="none" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        رجوع
      </button>
      <Brand title={title} />
      <div className="top-right">{children}</div>
    </header>
  );
}

// ---------------------------------------------------------------- the printed report (print only)

function PrintReport({
  inspection,
  title,
  wallLabel,
  wall,
  imageUrl,
}: {
  inspection: Inspection;
  title: string;
  wallLabel: string;
  wall: ReturnType<typeof wallOf>;
  imageUrl: string | null;
}) {
  const items = numbered(inspection.findings);
  const numberOf = new Map(items.map(({ n, finding }) => [finding.finding_id, n]));
  const urgent = urgentOpen(inspection.findings);
  const kept = inspection.work_orders.filter((o) => o.status !== "rejected");
  const approved = inspection.status === "approved";

  return (
    <section className="ireport" aria-hidden="true">
      <h1>تقرير فحص: {title}</h1>
      <p>
        {wallLabel} · الفحص {inspection.inspection_id} · بدأ {when(inspection.started_at)} ·{" "}
        {approved ? `معتمد من ${roleAr(inspection.approved_by)} ${when(inspection.approved_at)}` : "مسودة غير معتمدة"}
      </p>
      {urgent.length > 0 && (
        <p className="rurgent">
          عاجل: {urgent.map((f) => `D${numberOf.get(f.finding_id)}`).join("، ")}. {URGENT_ACTION_AR}.
        </p>
      )}
      {wall && <WallPhoto wall={wall} imageUrl={imageUrl} items={items} selected={null} />}
      <Legend findings={inspection.findings} />

      <h2>خريطة التلف وقائمة الأولويات</h2>
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>المنطقة</th>
            <th>النوع والشدة</th>
            <th>الأولوية</th>
            <th>السبب</th>
            <th>المراجعة</th>
          </tr>
        </thead>
        <tbody>
          {items.map(({ n, finding: f }) => (
            <tr key={f.finding_id} className={f.review.status === "rejected" ? "rejected" : ""}>
              <td>D{n}</td>
              <td>{shortZone(f.zone_id)}</td>
              <td>
                {nameAr(effectiveType(f))} ({SEVERITY_NAMES_AR[effectiveSeverity(f)]}){f.suspected ? "، اشتباه" : ""}
                {f.lookalike?.note_ar && <div className="small">{f.lookalike.note_ar}</div>}
              </td>
              <td>{f.priority ? `${ESCALATION_NAMES_AR[f.priority.escalation]} (${f.priority.score})` : ""}</td>
              <td>{f.priority?.reason_ar}</td>
              <td>
                {REVIEW_NAMES_AR[f.review.status]}
                {f.review.status === "corrected" && (
                  <div className="small">
                    صُحح من {nameAr(f.damage_type)} ({SEVERITY_NAMES_AR[f.severity]}) إلى {nameAr(effectiveType(f))} (
                    {SEVERITY_NAMES_AR[effectiveSeverity(f)]})
                  </div>
                )}
                {f.review.note && <div className="small">{f.review.note}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>مسودات أوامر العمل</h2>
      {kept.length === 0 && <p>لا توجد أوامر عمل.</p>}
      {kept.map((o) => (
        <WorkOrderBlock key={o.work_order_id} order={o} numberOf={numberOf} />
      ))}

      <p className="rnote">
        النتائج مخرجات نموذج، وأوزان الأولوية مفترضة. أوامر العمل مسودات تحتاج فحصاً ميدانياً. {CLOSING_AR}.
      </p>
    </section>
  );
}

function WorkOrderBlock({ order: o, numberOf }: { order: WorkOrderDraft; numberOf: Map<string, number> }) {
  return (
    <div className="rorder">
      <h3>
        {o.finding_ids.map((id) => `D${numberOf.get(id) ?? "?"}`).join("، ")} · {o.location.zone_ids.map(shortZone).join("، ")} ·{" "}
        {ESCALATION_NAMES_AR[o.escalation]} · {o.status === "approved" ? "معتمد" : "مسودة، تحتاج فحصاً ميدانياً"}
      </h3>
      <ol>
        {o.steps_ar.map((s, i) => (
          <li key={i}>{s}</li>
        ))}
      </ol>
      {o.materials_ar.length > 0 && <p>المواد: {o.materials_ar.join("، ")}</p>}
      {o.tools_ar.length > 0 && <p>الأدوات: {o.tools_ar.join("، ")}</p>}
      <p className="small" dir="ltr">
        Source: {o.citation.source}
        {o.citation.section ? `, ${o.citation.section}` : ""} ({o.citation.status === "approved_library" ? "approved library" : "team proposal"})
      </p>
    </div>
  );
}
