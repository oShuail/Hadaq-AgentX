// One finding in the review list: what the agent found, why it ranks where it does, the evidence a look-alike needs,
// and the conservator's three actions: Accept, Correct (type and severity), Reject.
import { useState } from "react";

import { CoreError, core } from "../api/core";
import type { DamageType, Finding, ReviewRequest, Severity } from "../types/contracts";
import { CORE_DAMAGE_TYPES, ESCALATION_NAMES_AR, SEVERITY_NAMES_AR } from "../types/contracts";
import { DAMAGE_NAMES_EN, REVIEW_NAMES_AR, effectiveSeverity, effectiveType, nameAr, shortZone } from "./model";

interface Props {
  n: number;
  finding: Finding;
  selected: boolean;
  locked: boolean; // the inspection is approved: no more changes
  onSelect: () => void;
  onReviewed: (finding: Finding) => void;
}

const TYPES: DamageType[] = [...CORE_DAMAGE_TYPES, "structural_deformation", "incompatible_repair", "parapet_loss", "timber_decay", "biological_growth"];
const SEVERITIES: Severity[] = [1, 2, 3];

export default function FindingRow({ n, finding: f, selected, locked, onSelect, onReviewed }: Props) {
  const [correcting, setCorrecting] = useState(false);
  const [type, setType] = useState<DamageType>(effectiveType(f));
  const [severity, setSeverity] = useState<Severity>(effectiveSeverity(f));
  const [note, setNote] = useState(f.review.note ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async (request: ReviewRequest) => {
    setBusy(true);
    setError(null);
    try {
      onReviewed(await core.review(f.finding_id, request));
      setCorrecting(false);
    } catch (e) {
      const status = e instanceof CoreError ? e.status : 0;
      setError(
        status === 409 ? "الفحص معتمد، لا يمكن التعديل" : status === 403 ? "المراجعة لمهندس الترميم فقط" : `ما وصل الطلب: ${(e as Error).message}`,
      );
    } finally {
      setBusy(false);
    }
  };

  const correct = () => {
    const changedType = type !== f.damage_type ? type : null;
    const changedSeverity = severity !== f.severity ? severity : null;
    if (!changedType && !changedSeverity) {
      setError("غيّر النوع أو الشدة، أو اختر قبول");
      return;
    }
    send({ status: "corrected", corrected_type: changedType, corrected_severity: changedSeverity, note: note.trim() || null });
  };

  const kind = effectiveType(f);
  const sev = effectiveSeverity(f);
  const esc = f.priority?.escalation;
  const status = f.review.status;

  return (
    <article className={`frow${selected ? " on" : ""} review-${status}`} onClick={onSelect}>
      <header>
        <span className="fnum">D{n}</span>
        <div className="ftitle">
          <strong>
            {nameAr(kind)} <span className="muted">· {SEVERITY_NAMES_AR[sev]}</span>
          </strong>
          <small dir="ltr">
            {DAMAGE_NAMES_EN[kind]}, severity {sev} · {shortZone(f.zone_id)} · confidence {Math.round(f.confidence * 100)}%
            {f.change ? ` · ${f.change}` : ""}
          </small>
        </div>
        {esc && <span className={`esc esc-${esc}`}>{ESCALATION_NAMES_AR[esc]}</span>}
      </header>

      {status === "corrected" && (
        <p className="fcorr">
          صُحح من {nameAr(f.damage_type)} ({SEVERITY_NAMES_AR[f.severity]}) إلى {nameAr(kind)} ({SEVERITY_NAMES_AR[sev]})
          {f.review.note ? `: ${f.review.note}` : ""}
        </p>
      )}
      {f.priority && (
        <p className="freason">
          {f.priority.reason_ar} <span className="muted">· {f.priority.score}</span>
        </p>
      )}
      {f.suspected && <p className="fflag">اشتباه: يحال للمختص بدون معالجة</p>}
      {f.lookalike && (
        <div className="flook">
          <p>{f.lookalike.note_ar ?? f.lookalike.options.join(" / ")}</p>
          <p className="muted" dir="ltr">
            Evidence needed: {f.lookalike.evidence_needed.join("; ")}
          </p>
        </div>
      )}

      <footer onClick={(e) => e.stopPropagation()}>
        <span className={`rstat rstat-${status}`}>{REVIEW_NAMES_AR[status]}</span>
        {!locked && !correcting && (
          <div className="ractions">
            <button className="ok" disabled={busy} onClick={() => send({ status: "accepted" })}>قبول</button>
            <button disabled={busy} onClick={() => setCorrecting(true)}>تصحيح</button>
            <button className="no" disabled={busy} onClick={() => send({ status: "rejected", note: note.trim() || null })}>رفض</button>
          </div>
        )}
      </footer>

      {correcting && (
        <div className="fcorrect" onClick={(e) => e.stopPropagation()}>
          <label>
            النوع
            <select value={type} onChange={(e) => setType(e.target.value as DamageType)}>
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {nameAr(t)} · {DAMAGE_NAMES_EN[t]}
                </option>
              ))}
            </select>
          </label>
          <label>
            الشدة
            <select value={severity} onChange={(e) => setSeverity(Number(e.target.value) as Severity)}>
              {SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  {s} · {SEVERITY_NAMES_AR[s]}
                </option>
              ))}
            </select>
          </label>
          <label className="wide">
            ملاحظة
            <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="سبب التصحيح (اختياري)" />
          </label>
          <div className="ractions">
            <button className="ok" disabled={busy} onClick={correct}>حفظ التصحيح</button>
            <button disabled={busy} onClick={() => setCorrecting(false)}>إلغاء</button>
          </div>
        </div>
      )}
      {error && <p className="ferr">{error}</p>}
    </article>
  );
}
