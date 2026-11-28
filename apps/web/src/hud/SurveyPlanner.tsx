// The site manager plans the photo rounds (owner: Turki): the surveys and how far each is, and a form for a new one
// (POST /api/v2/surveys). The surveyor app gets new surveys from GET /api/v2/surveys?assignee=<surveyor>.
import { type FormEvent, useEffect, useState } from "react";

import { connectLive } from "../api/client";
import { core } from "../api/core";
import { CORE_WS } from "../config";
import type { Registry, Survey, SurveyRequest, TaskStatus } from "../types/contracts";

const TASK_AR: Record<TaskStatus, string> = { not_started: "لم يبدأ", partial: "جزئي", done: "مكتمل", synced: "مُزامَن" };

// a wall in N parts: 1 = "full", 2 on 8 columns = C1-C4, C5-C8
export function shotsFor(cols: number, parts: number): string[] {
  if (parts <= 1) return ["full"];
  const size = Math.ceil(cols / parts);
  const shots: string[] = [];
  for (let from = 1; from <= cols; from += size) shots.push(`C${from}-C${Math.min(cols, from + size - 1)}`);
  return shots;
}

const today = () => new Date().toISOString().slice(0, 10);
const dateAr = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString("ar-SA-u-ca-gregory", { day: "numeric", month: "short" }) : "بلا موعد");

type WallChoice = { key: string; buildingId: string; wallId: string; label: string; cols: number; on: boolean; parts: number };

export default function SurveyPlanner() {
  const [surveys, setSurveys] = useState<Survey[]>([]);
  const [registry, setRegistry] = useState<Registry | null>(null);
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [assignee, setAssignee] = useState("surveyor");
  const [due, setDue] = useState("");
  const [walls, setWalls] = useState<WallChoice[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const load = () => core.surveys().then(setSurveys).catch(() => {});

  useEffect(() => {
    load();
    core
      .registry()
      .then((r) => {
        setRegistry(r);
        setWalls(
          r.buildings.flatMap((b) =>
            b.walls.map((w) => ({
              key: `${b.id}/${w.id}`,
              buildingId: b.id,
              wallId: w.id,
              label: `${b.name_ar} · ${w.id}`,
              cols: w.cols,
              on: r.buildings.length === 1 && b.walls.length === 1, // one wall on the site: ticked already
              parts: 1,
            })),
          ),
        );
      })
      .catch(() => {});
    // a shot arriving changes a task (done_shots): reload on new captures and after a demo reset
    return connectLive(CORE_WS, (m) => (m.channel === "capture" || m.channel === "inspection") && load(), () => {});
  }, []);

  const setWall = (key: string, change: Partial<WallChoice>) =>
    setWalls((ws) => ws.map((w) => (w.key === key ? { ...w, ...change } : w)));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const chosen = walls.filter((w) => w.on);
    if (!title.trim()) return setMessage({ ok: false, text: "اكتب عنوان المسح" });
    if (chosen.length === 0) return setMessage({ ok: false, text: "اختر جداراً واحداً على الأقل" });
    const request: SurveyRequest = {
      title: title.trim(),
      assignee: assignee.trim() || "surveyor",
      due: due ? `${due}T12:00:00+03:00` : null, // noon, Riyadh time
      tasks: chosen.map((w) => ({ building_id: w.buildingId, wall_id: w.wallId, required_shots: shotsFor(w.cols, w.parts) })),
    };
    setBusy(true);
    setMessage(null);
    try {
      const survey = await core.createSurvey(request);
      setMessage({ ok: true, text: `أُنشئ المسح «${survey.title}» وأُرسل إلى ${survey.assignee}` });
      setTitle("");
      setDue("");
      setOpen(false);
      load();
    } catch (err) {
      setMessage({ ok: false, text: err instanceof Error ? err.message : String(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="surveys">
      <div className="surveys-head">
        <h3>
          المسوحات <span className="muted">· Surveys</span>
        </h3>
        {registry && (
          <button className="surveys-new" onClick={() => { setOpen((o) => !o); setMessage(null); }}>
            {open ? "إلغاء" : "+ مسح جديد"}
          </button>
        )}
      </div>

      {open && (
        <form className="survey-form" dir="rtl" lang="ar" onSubmit={submit}>
          <label>
            العنوان
            <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="قصر سلوى، بعد أمطار يناير" maxLength={200} />
          </label>
          <div className="survey-form-row">
            <label>
              الماسح
              <input value={assignee} onChange={(e) => setAssignee(e.target.value)} dir="ltr" maxLength={60} />
            </label>
            <label>
              الموعد
              <input type="date" value={due} min={today()} onChange={(e) => setDue(e.target.value)} />
            </label>
          </div>
          <fieldset>
            <legend>الجدران المطلوب تصويرها</legend>
            {walls.map((w) => (
              <div key={w.key} className="survey-wall">
                <label className="check">
                  <input type="checkbox" checked={w.on} onChange={(e) => setWall(w.key, { on: e.target.checked })} />
                  {w.label}
                </label>
                <select value={w.parts} disabled={!w.on} onChange={(e) => setWall(w.key, { parts: Number(e.target.value) })}>
                  <option value={1}>الجدار كاملاً</option>
                  <option value={2}>على جزأين</option>
                  <option value={4}>على أربعة أجزاء</option>
                </select>
                {w.on && <span className="shots" dir="ltr">{shotsFor(w.cols, w.parts).join(" · ")}</span>}
              </div>
            ))}
          </fieldset>
          <button type="submit" className="primary" disabled={busy}>
            {busy ? "جارٍ الإرسال…" : "إنشاء المسح وإرساله للماسح"}
          </button>
        </form>
      )}

      {message && <p className={message.ok ? "survey-ok" : "survey-error"} dir="auto">{message.text}</p>}

      {surveys.length === 0 ? (
        <p className="muted">لا توجد مسوحات بعد.</p>
      ) : (
        <ul className="survey-list" dir="rtl" lang="ar">
          {surveys.map((s) => (
            <li key={s.survey_id}>
              <div className="survey-title">
                <b>{s.title}</b>
                <span className="muted">{s.assignee} · {dateAr(s.due)}</span>
              </div>
              {s.tasks.map((t) => {
                const missing = t.required_shots.filter((x) => !t.done_shots.includes(x));
                return (
                  <div key={`${t.building_id}/${t.wall_id}`} className="survey-task">
                    <span className={`task task-${t.status}`}>{TASK_AR[t.status]}</span>
                    <span dir="ltr">{t.building_id} {t.wall_id}</span>
                    <span className="muted" dir="ltr">
                      {t.done_shots.length}/{t.required_shots.length}
                      {missing.length > 0 && t.status !== "not_started" && <> · missing {missing.join(", ")}</>}
                    </span>
                  </div>
                );
              })}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
