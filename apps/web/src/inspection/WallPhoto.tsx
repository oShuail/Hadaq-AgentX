// The rectified wall photo with a numbered mark on every finding, placed by its box in wall metres (box_wall_m).
// The rectified image is the wall face itself (left = C1 end, bottom = base), so u / width and v / height place a box
// on it directly. Without a photo the wall is drawn as a plain sand rectangle with its zone grid.
import { useState } from "react";

import { DAMAGE_FAMILIES, familyOf } from "../twin/damage";
import type { WallInfo } from "../types/contracts";
import { type Numbered, effectiveType, shortZone } from "./model";

interface Props {
  wall: WallInfo;
  imageUrl: string | null;
  items: Numbered[];
  selected: string | null;
  onSelect?: (findingId: string) => void;
  showZones?: boolean;
}

const pct = (x: number) => `${Math.max(0, Math.min(100, x * 100))}%`;

export default function WallPhoto({ wall, imageUrl, items, selected, onSelect, showZones = false }: Props) {
  const [broken, setBroken] = useState(false);
  const photo = imageUrl && !broken;
  const W = wall.width_m;
  const H = wall.height_m;

  return (
    <div className={`wphoto${photo ? "" : " schematic"}`} style={photo ? undefined : { aspectRatio: `${W} / ${H}` }}>
      {photo && <img src={imageUrl} alt={`Rectified photo of wall ${wall.id}`} onError={() => setBroken(true)} />}
      {(showZones || !photo) && (
        <div className="wgrid" aria-hidden="true" style={{ gridTemplateColumns: `repeat(${wall.cols}, 1fr)` }}>
          {Array.from({ length: wall.rows * wall.cols }, (_, i) => {
            const row = wall.rows - Math.floor(i / wall.cols); // top row first
            const col = (i % wall.cols) + 1;
            return <span key={i}>{`R${row}C${col}`}</span>;
          })}
        </div>
      )}
      {items.map(({ n, finding: f }) => {
        const [u0, v0, u1, v1] = f.box_wall_m;
        const family = DAMAGE_FAMILIES[familyOf(effectiveType(f))];
        const rejected = f.review.status === "rejected";
        return (
          <button
            key={f.finding_id}
            type="button"
            className={`wmark${selected === f.finding_id ? " on" : ""}${rejected ? " rejected" : ""}${f.suspected || family.dashed ? " dashed" : ""}`}
            style={{
              left: pct(u0 / W),
              width: pct((u1 - u0) / W),
              top: pct(1 - v1 / H),
              height: pct((v1 - v0) / H),
              borderColor: rejected ? undefined : family.color,
            }}
            title={`D${n} ${shortZone(f.zone_id)}`}
            onClick={() => onSelect?.(f.finding_id)}
          >
            <b style={{ background: rejected ? undefined : family.color }}>D{n}</b>
          </button>
        );
      })}
      {!photo && <p className="wnote">{imageUrl ? "The photo did not load: marks on a drawn wall" : "No rectified photo yet: marks on a drawn wall"}</p>}
    </div>
  );
}
