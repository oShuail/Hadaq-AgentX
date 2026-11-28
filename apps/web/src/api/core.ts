// The core API (Spring, services/core, owner: Turki): contracts v2 under /api/v2. Every request says who is acting
// with the X-Hadaq-User header (hackathon only, no real auth). The review and the approval need "conservator".
import { CORE_URL } from "../config";
import type { Finding, Inspection, Registry, ReviewRequest, Role, SitePriorityRow } from "../types/contracts";

const V2 = `${CORE_URL}/api/v2`;

/** An error from the core API with its HTTP status (409 = not allowed in this state, 403 = wrong role). */
export class CoreError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

async function call<T>(path: string, role: Role, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { "X-Hadaq-User": role };
  if (init.body) headers["Content-Type"] = "application/json";
  const response = await fetch(`${V2}${path}`, { ...init, headers });
  if (!response.ok) {
    let detail = "";
    try {
      const body = (await response.json()) as { message?: string; error?: string };
      detail = body.message || body.error || "";
    } catch {
      // not JSON: keep the status only
    }
    throw new CoreError(detail || `${path}: ${response.status}`, response.status);
  }
  return (await response.json()) as T;
}

export const core = {
  // one row per building, urgent first, then the longest since the last survey
  sitePriority: () => call<SitePriorityRow[]>("/site/priority", "site_manager"),
  registry: () => call<Registry>("/registry", "conservator"),
  inspection: (id: string) => call<Inspection>(`/inspections/${encodeURIComponent(id)}`, "conservator"),
  // newest first
  buildingInspections: (buildingId: string) =>
    call<Inspection[]>(`/buildings/${encodeURIComponent(buildingId)}/inspections`, "conservator"),
  review: (findingId: string, request: ReviewRequest) =>
    call<Finding>(`/findings/${encodeURIComponent(findingId)}/review`, "conservator", {
      method: "PATCH",
      body: JSON.stringify(request),
    }),
  // 409 while any finding is pending, or when already approved
  approve: (inspectionId: string) =>
    call<Inspection>(`/inspections/${encodeURIComponent(inspectionId)}/approve`, "conservator", { method: "POST" }),
};
