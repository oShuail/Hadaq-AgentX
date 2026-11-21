// Latest camera frame with detection boxes (owner: Abdurhmn).
import { useState } from "react";

import type { Detection, Photo } from "../types/contracts";

export interface PhotoPrivacy {
  checked: boolean;
  faces_hidden: number;
}

/** Privacy note the API puts on the new_photo event: faces are hidden before a photo is stored. */
export function privacyText(p: PhotoPrivacy): string {
  if (!p.checked) return "privacy not checked";
  return p.faces_hidden ? `${p.faces_hidden} face${p.faces_hidden === 1 ? "" : "s"} hidden` : "no faces found";
}

export default function CameraFeed({ photo, detections, privacy }: { photo: Photo | null; detections: Detection[]; privacy?: PhotoPrivacy | null }) {
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  if (!photo?.file_url) return <p className="muted small">No camera frame yet.</p>;
  const mine = detections.filter((d) => d.photo_id === photo.photo_id);

  return (
    <div className="camera">
      <h3>
        Camera {photo.camera_id} {photo.provenance === "synthetic" && <span className="tag">SIM</span>}{" "}
        {privacy && <span className={`ptag ${privacy.checked ? "" : "warn"}`} title="Faces are hidden before a photo is stored">{privacyText(privacy)}</span>}
      </h3>
      <div className="frame">
        <img src={photo.file_url} alt="Latest camera frame" onLoad={(e) => setSize({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })} />
        {size &&
          mine.map((d) => {
            const [x0, y0, x1, y1] = d.bbox_xyxy;
            return (
              <div
                key={d.detection_id}
                className={`box ${d.change ?? ""}`}
                style={{ left: `${(x0 / size.w) * 100}%`, top: `${(y0 / size.h) * 100}%`, width: `${((x1 - x0) / size.w) * 100}%`, height: `${((y1 - y0) / size.h) * 100}%` }}
                title={`${d.cls} ${Math.round(d.confidence * 100)}% ${d.change ?? ""}`}
              />
            );
          })}
      </div>
      <ul className="detections small">
        {mine.map((d) => (
          <li key={d.detection_id}>
            {d.cls} in {d.zone_id} ({Math.round(d.confidence * 100)}%){d.change && ` | ${d.change}`}
            {d.growth_pct !== null && ` ${d.growth_pct > 0 ? "+" : ""}${d.growth_pct}%`}
          </li>
        ))}
      </ul>
    </div>
  );
}
