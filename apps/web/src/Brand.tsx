// Hadaq logo and title block, shared by the map and the building pages.
export default function Brand({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div className="brand">
      <div className="mark">
        <span className="ar">حَدَق</span>
        <span className="en">HADAQ</span>
      </div>
      <div className="titles">
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
    </div>
  );
}
