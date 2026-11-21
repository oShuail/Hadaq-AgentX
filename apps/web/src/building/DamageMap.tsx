// Damage map: the Damage and Treatment agent's latest report drawn on the photo it came from.
// One colour per damage family with a fixed legend; every mark is numbered and named, so identity never
// rests on colour alone. Click a mark, a legend entry or a list row to see the treatment, materials and tools.
import { useMemo, useState } from "react";

import { API_URL } from "../config";
import { DAMAGE_FAMILIES, type DamageItem, type DamageReportEvent, FAMILY_ORDER, familyOf } from "../twin/damage";
import { RISK_COLORS } from "../twin/colors";

interface Props {
  damage: DamageReportEvent | null;
  wallBox: number[] | null; // [x0, y0, x1, y1] of the wall face in the photo (camera entry of the registry)
  rows: number;
  cols: number;
}

const PRIORITY_RANK = { low: 0, medium: 1, high: 2 } as const;
const PRIORITY_COLOR = { low: RISK_COLORS.low, medium: RISK_COLORS.medium, high: RISK_COLORS.high } as const;

const shortZone = (zoneId: string) => zoneId.split("-").pop() ?? zoneId;
const colorOf = (item: DamageItem) => DAMAGE_FAMILIES[familyOf(item.damage_type)].color;
const pct = (value: number, total: number) => `${(value / total) * 100}%`;

function detail(item: DamageItem): string {
  const parts = [item.severity ? `${item.severity}${item.crack_width_mm != null ? ` ${item.crack_width_mm} mm` : ""}` : "", item.active ? "growing" : ""];
  return parts.filter(Boolean).join(", ");
}

export default function DamageMap({ damage, wallBox, rows, cols }: Props) {
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  const [picked, setPicked] = useState<number | null>(null);
  const [showZones, setShowZones] = useState(false);

  const items = useMemo(() => damage?.report.items ?? [], [damage]);
  const families = useMemo(
    () =>
      FAMILY_ORDER.map((family) => {
        const own = items.map((item, index) => ({ item, index })).filter(({ item }) => familyOf(item.damage_type) === family);
        const types = new Map<string, { name: string; count: number; first: number }>();
        for (const { item, index } of own) {
          const t = types.get(item.damage_type) ?? { name: item.name_en, count: 0, first: index };
          t.count += 1;
          types.set(item.damage_type, t);
        }
        return { family, types: [...types.values()] };
      }).filter((f) => f.types.length > 0),
    [items],
  );

  if (!damage) {
    return <p className="placeholder">No damage report yet. When the camera sends a photo with new or grown damage, the agent maps it here on its own.</p>;
  }

  const mostUrgent = items.reduce((best, item, i) => (PRIORITY_RANK[item.priority] > PRIORITY_RANK[items[best].priority] ? i : best), 0);
  const selected = picked ?? (items.length ? mostUrgent : null);
  const current = selected !== null ? items[selected] : null;
  const synthetic = damage.report.photo_provenance === "synthetic";

  return (
    <div className="damage">
      <div className="dmeta small">
        <span className="badge warn">Waiting for expert approval</span>
        <span className="prio" style={{ color: PRIORITY_COLOR[damage.priority] }}>{damage.priority} priority</span>
        <span className="muted">
          {new Date(damage.ts).toLocaleTimeString()} · {items.length} finding{items.length === 1 ? "" : "s"}
          {synthetic ? " · simulated photo" : ""}
        </span>
        <label className="ztoggle">
          <input type="checkbox" checked={showZones} onChange={(e) => setShowZones(e.target.checked)} /> Zones
        </label>
      </div>

      <div className="dmap">
        <img
          src={`${API_URL}/photos/${damage.report.photo_id}/file`}
          alt="Wall photo the damage report was made from"
          onLoad={(e) => setSize({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })}
        />
        {size && showZones && wallBox && (
          <div
            className="zones"
            style={{
              left: pct(wallBox[0], size.w),
              top: pct(wallBox[1], size.h),
              width: pct(wallBox[2] - wallBox[0], size.w),
              height: pct(wallBox[3] - wallBox[1], size.h),
              gridTemplateColumns: `repeat(${cols}, 1fr)`,
              gridTemplateRows: `repeat(${rows}, 1fr)`,
            }}
            aria-hidden="true"
          >
            {Array.from({ length: rows }, (_, i) => rows - i).map((r) =>
              Array.from({ length: cols }, (_, c) => <span key={`${r}-${c}`}>R{r}C{c + 1}</span>),
            )}
          </div>
        )}
        {size &&
          items.map((item, i) => {
            if (!item.bbox_xyxy || item.bbox_xyxy.length !== 4) return null;
            const [x0, y0, x1, y1] = item.bbox_xyxy;
            const family = DAMAGE_FAMILIES[familyOf(item.damage_type)];
            return (
              <button
                key={item.detection_id}
                className={`dmark ${family.dashed ? "dashed" : ""} ${selected === i ? "sel" : ""}`}
                style={{ ["--c" as string]: family.color, left: pct(x0, size.w), top: pct(y0, size.h), width: pct(x1 - x0, size.w), height: pct(y1 - y0, size.h) }}
                onClick={() => setPicked(i)}
                aria-label={`${i + 1}. ${item.name_en} at ${shortZone(item.zone_id)}`}
                title={`${i + 1}. ${item.name_en} · ${shortZone(item.zone_id)}`}
              >
                <span className="num" style={{ ["--c" as string]: family.color }}>{i + 1}</span>
              </button>
            );
          })}
      </div>

      <div className="dlegend" aria-label="Colour key">
        {families.map(({ family, types }) => {
          const f = DAMAGE_FAMILIES[family];
          return (
            <div key={family} className="fam">
              <i className={`sw ${f.dashed ? "dashed" : ""}`} style={{ ["--c" as string]: f.color }} />
              <span>
                <b>{f.label}</b>{" "}
                {types.map((t) => (
                  <button key={t.name} className="linkish" onClick={() => setPicked(t.first)}>
                    {t.name} ×{t.count}
                  </button>
                ))}
              </span>
            </div>
          );
        })}
      </div>

      <ol className="dlist">
        {items.map((item, i) => (
          <li key={item.detection_id}>
            <button className={selected === i ? "sel" : ""} onClick={() => setPicked(i)}>
              <span className="num" style={{ ["--c" as string]: colorOf(item) }}>{i + 1}</span>
              <span className="grow">
                {item.name_en} <span className="muted" dir="rtl">{item.name_ar}</span>
                <span className="muted"> · {shortZone(item.zone_id)}{detail(item) ? ` · ${detail(item)}` : ""}</span>
              </span>
              <span className="prio" style={{ color: PRIORITY_COLOR[item.priority] }}>{item.priority}</span>
            </button>
          </li>
        ))}
      </ol>

      {current && selected !== null && (
        <div className="dcard">
          <div className="dcard-head">
            <span className="num" style={{ ["--c" as string]: colorOf(current) }}>{selected + 1}</span>
            <strong>{current.name_en}</strong>
            <span className="muted" dir="rtl">{current.name_ar}</span>
          </div>
          <div className="muted small">
            {current.zone_id}{current.position_en ? ` · ${current.position_en}` : ""}
          </div>
          {current.needs_review && <div className="review small">Type to be confirmed by the expert.</div>}
          <dl>
            {detail(current) && (
              <>
                <dt>Severity</dt>
                <dd>{detail(current)}</dd>
              </>
            )}
            <dt>Treatment</dt>
            <dd>{current.step_en}</dd>
            {(current.refer_to_engineer || current.fix_moisture_source_first || current.test_on_small_area_first) && (
              <>
                <dt>First</dt>
                <dd>
                  {[
                    current.refer_to_engineer && "a structural engineer checks it before any filling",
                    current.fix_moisture_source_first && "treat the moisture source",
                    current.test_on_small_area_first && "test on a small area",
                  ]
                    .filter(Boolean)
                    .join("; ")}
                </dd>
              </>
            )}
            <dt>Materials</dt>
            <dd>{current.materials_en.join(", ")}</dd>
            <dt>Tools</dt>
            <dd>{current.tools_en.join(", ")}</dd>
            {current.avoid_en.length > 0 && (
              <>
                <dt>Avoid</dt>
                <dd>{current.avoid_en.join(", ")}</dd>
              </>
            )}
            <dt>Source</dt>
            <dd>{current.source_label_en}{current.source === "team_proposal" ? ", a conservator must confirm it" : ""}</dd>
          </dl>
        </div>
      )}

      {damage.report.first_steps_en.length > 0 && (
        <p className="dfirst small">Before any repair: {damage.report.first_steps_en.join("; ")}.</p>
      )}
    </div>
  );
}
