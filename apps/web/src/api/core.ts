// The core API (Spring, services/core, owner: Turki): contracts v2 under /api/v2. Every request says who is acting
// with the X-Hadaq-User header (hackathon only, no real auth). Omar extends this file in O4 (inspection, review,
// approve); T3 needs only the site priority list.
import { CORE_URL } from "../config";
import type { Role, SitePriorityRow } from "../types/contracts";

const V2 = `${CORE_URL}/api/v2`;

async function getJson<T>(path: string, role: Role): Promise<T> {
  const response = await fetch(`${V2}${path}`, { headers: { "X-Hadaq-User": role } });
  if (!response.ok) throw new Error(`${path}: ${response.status}`);
  return (await response.json()) as T;
}

export const core = {
  // one row per building, urgent first, then the longest since the last survey
  sitePriority: () => getJson<SitePriorityRow[]>("/site/priority", "site_manager"),
};
