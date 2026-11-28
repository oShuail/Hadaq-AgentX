// The core API (Spring, services/core, owner: Turki): contracts v2 under /api/v2. Every request says who is acting
// with the X-Hadaq-User header (hackathon only, no real auth). Omar extends this file in O4 (inspection, review,
// approve).
import { CORE_URL } from "../config";
import type { Registry, Role, SitePriorityRow, Survey, SurveyRequest } from "../types/contracts";

const V2 = `${CORE_URL}/api/v2`;

// the API answers errors as {"message": "..."}: show that text, it says what to fix
async function failure(path: string, response: Response): Promise<Error> {
  try {
    const body = (await response.json()) as { message?: string };
    if (body.message) return new Error(body.message);
  } catch {
    // not JSON
  }
  return new Error(`${path}: ${response.status}`);
}

async function getJson<T>(path: string, role: Role): Promise<T> {
  const response = await fetch(`${V2}${path}`, { headers: { "X-Hadaq-User": role } });
  if (!response.ok) throw await failure(path, response);
  return (await response.json()) as T;
}

async function postJson<T>(path: string, role: Role, body: unknown): Promise<T> {
  const response = await fetch(`${V2}${path}`, {
    method: "POST",
    headers: { "X-Hadaq-User": role, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw await failure(path, response);
  return (await response.json()) as T;
}

export const core = {
  // one row per building, urgent first, then the longest since the last survey
  sitePriority: () => getJson<SitePriorityRow[]>("/site/priority", "site_manager"),
  // buildings and walls (wall size and columns), for planning a survey
  registry: () => getJson<Registry>("/registry", "site_manager"),
  // every survey, newest first
  surveys: () => getJson<Survey[]>("/surveys", "site_manager"),
  // site manager only; 201 with the new survey
  createSurvey: (request: SurveyRequest) => postJson<Survey>("/surveys", "site_manager", request),
};
