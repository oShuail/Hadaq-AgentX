// Top-left tower (owner: Omar): the Hadaq mark, the title and the four tabs.
export type Tab = "explore" | "twin" | "agent" | "activity" | "priority";

export const TABS: { id: Tab; label: string }[] = [
  { id: "explore", label: "Explore" },
  { id: "twin", label: "Building twin" },
  { id: "agent", label: "Agent" },
  { id: "activity", label: "Activity" },
  // the site manager's ranking (T3, Turki): GET /api/v2/site/priority from the core API
  { id: "priority", label: "Priority · الأولويات" },
];

export default function BrandTower({ tab, onTab }: { tab: Tab; onTab: (t: Tab) => void }) {
  return (
    <div className="tower tower-tl">
      <Crenels />
      <div className="tower-body">
        <div className="hud-brand">
          <span className="ar" lang="ar">حَدَق</span>
          <div>
            <strong>HADAQ</strong>
            <span>At-Turaif digital twin</span>
          </div>
        </div>
        <div className="hud-tabs" role="tablist" aria-label="Panels">
          {TABS.map((t) => (
            <button key={t.id} role="tab" aria-selected={tab === t.id} className={tab === t.id ? "on" : ""} onClick={() => onTab(t.id)}>
              {t.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

/** The Najdi parapet (shurfat) along the top of a tower. */
export function Crenels() {
  return (
    <svg className="crenels" viewBox="0 0 288 12" preserveAspectRatio="none" aria-hidden="true">
      <defs>
        <pattern id="hud-cren" width="18" height="12" patternUnits="userSpaceOnUse">
          <path d="M0 12 L0 6 L4 6 L9 0 L14 6 L18 6 L18 12 Z" />
        </pattern>
      </defs>
      <rect width="288" height="12" fill="url(#hud-cren)" />
    </svg>
  );
}
