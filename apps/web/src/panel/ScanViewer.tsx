// Full-screen view of a detailed scan in Sketchfab's own viewer, with the author credit the embed asks for (owner: Omar).
import { useEffect } from "react";

import type { EmbeddedScan } from "../twin/scans";

export default function ScanViewer({ scan, onClose }: { scan: EmbeddedScan; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const src = `https://sketchfab.com/models/${scan.uid}/embed?autostart=1&preload=1&ui_theme=dark&ui_hint=0`;
  return (
    <div className="scan-overlay" onClick={onClose}>
      <div className="scan-box" onClick={(e) => e.stopPropagation()}>
        <div className="scan-head">
          <strong>Real 3D scan: {scan.title}</strong>
          <button onClick={onClose}>Close</button>
        </div>
        <iframe
          title={scan.title}
          src={src}
          allow="autoplay; fullscreen; xr-spatial-tracking"
          allowFullScreen
        />
        <p className="scan-credit">
          <a href={scan.modelUrl} target="_blank" rel="nofollow noreferrer">{scan.title}</a> by{" "}
          <a href={scan.authorUrl} target="_blank" rel="nofollow noreferrer">{scan.author}</a> on{" "}
          <a href="https://sketchfab.com" target="_blank" rel="nofollow noreferrer">Sketchfab</a>
        </p>
      </div>
    </div>
  );
}
