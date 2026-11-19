"""Agents API (owner: Omar).

Run:  uvicorn hadaq_agents.server:app --reload --port 8100
LLM:  HADAQ_LLM=anthropic:claude-sonnet-5-5 (needs ANTHROPIC_API_KEY) or HADAQ_LLM=mock (default, no key needed).

POST /agents/chat     user questions from the web chat
POST /agents/trigger  events forwarded by the API (storm_started, moisture_anomaly, detection_ready)
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time

from fastapi import BackgroundTasks, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from hadaq_agents import tools as T
from hadaq_agents.verifier import verify
from hadaq_schemas import ChatRequest, ChatResponse, Event

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("hadaq.agents")

LLM = os.getenv("HADAQ_LLM", "mock")
TRIGGER_COOLDOWN_S = float(os.getenv("TRIGGER_COOLDOWN_S", "60"))

app = FastAPI(title="Hadaq Agents", version="0.1.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

_graph = None
_sessions: dict[str, list] = {}
_last_trigger: dict[str, float] = {}
_lock = threading.Lock()


def _orchestrator():
    global _graph
    if _graph is None:
        from hadaq_agents.graph import build_agents

        _graph = build_agents(LLM)
    return _graph


def _finish(answer: str, tool_outputs: list[str], calls: list[dict]) -> ChatResponse:
    unverified, cited = verify(answer, tool_outputs)
    if unverified:
        answer += f"\n\n(Verifier: these numbers were not found in any tool output: {', '.join(unverified)})"
    return ChatResponse(
        answer=answer,
        evidence_ids=cited,
        tool_calls=calls,
        needs_approval=any("pending_expert_approval" in out for out in tool_outputs),
        unverified_numbers=unverified,
    )


def run_llm(message: str, building_id: str, session_id: str) -> ChatResponse:
    from hadaq_agents.graph import text_of

    history = _sessions.setdefault(session_id, [])
    start = len(history) + 1
    out = _orchestrator().invoke({"messages": [*history, HumanMessage(f"[building {building_id}] {message}")]},
                                 config={"recursion_limit": 30})
    new = out["messages"][start:]
    tool_outputs = [text_of(m) for m in new if isinstance(m, ToolMessage)]
    calls = [{"name": c["name"], "args": c["args"]} for m in new if isinstance(m, AIMessage) for c in (m.tool_calls or [])]
    answer = text_of(out["messages"][-1])
    history.extend([HumanMessage(message), AIMessage(answer)])
    del history[:-12]  # keep the last 6 exchanges as plain text memory
    return _finish(answer, tool_outputs, calls)


def run_mock(message: str, building_id: str, trigger: bool = False) -> ChatResponse:
    """Fixed plan with the real tools, for development without an LLM key. Every number comes from a tool output."""
    outputs: list[str] = []
    calls: list[dict] = []

    def call(fn, **args):
        calls.append({"name": fn.name, "args": args})
        raw = fn.invoke(args)
        outputs.append(raw)
        return json.loads(raw)

    text = message.lower()
    wants_compare = any(w in text for w in ("drain", "compare", "intervention", "what if", "تصريف", "قارن"))
    wants_sim = trigger or wants_compare or any(w in text for w in ("rain", "storm", "simulat", "moist", "مطر", "رطوب", "محاكا", "عاصف"))
    lines = ["[mock agent: no LLM configured, fixed plan]"]

    status = call(T.get_building_status, building_id=building_id)
    moisture = [r for r in status["readings"] if r["kind"] == "moisture"]
    if moisture:
        m = max(moisture, key=lambda r: r["value"])
        lines.append(f"Highest moisture reading: {m['value']} {m['unit']} at {m['zone_id']} ({m['provenance']}) [{m['evidence_id']}].")
    if wants_compare:
        cmp_ = call(T.compare_scenarios, building_id=building_id)
        hours = cmp_.get("base_row_hours_above_threshold", {})
        ids = cmp_.get("run_ids", {})
        lines.append("Base row hours above threshold: " + ", ".join(f"{k} {v} [{ids.get(k)}]" for k, v in hours.items())
                     + " (uncalibrated model output, indicative only).")
    if wants_sim:
        sim = call(T.run_rain_simulation, building_id=building_id, scenario="storm_replay")
        if sim.get("wettest_zones"):
            z = sim["wettest_zones"][0]
            lines.append(f"Simulation: wettest zone {z['zone_id']}, peak moisture {z['peak_moisture']}, "
                         f"{z['hours_above_threshold']} h above {sim['threshold']} [{sim['run_id']}] (uncalibrated model).")
    risk = call(T.get_risk, building_id=building_id, top=5)
    if risk:
        top = risk[0]
        lines.append(f"Top risk: {top['zone_id']} is {top['level']} (magnitude {top['magnitude']}) [{top['risk_id']}].")
        high = [r for r in risk if r["level"] in ("high", "very_high")]
        if high:
            evidence = list(dict.fromkeys(e for r in high[:3] for e in [r["risk_id"], *r.get("evidence_ids", [])[:1]]))
            zones = [r["zone_id"] for r in high[:3]]
            rec = call(T.propose_recommendation, building_id=building_id,
                       action="Inspect the base render and ground drainage at the wall foot; check for pooling after rain",
                       zone_ids=zones, priority="high", evidence_ids=evidence,
                       rationale="High rain risk on base zones in the simulation")
            lines.append(f"Proposed for expert review: inspect base render and drainage at {', '.join(zones)} [{rec['event_id']}]. Waiting for approval.")
    else:
        lines.append("No risk scores yet: run a simulation first.")
    return _finish("\n".join(lines), outputs, calls)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "llm": LLM}


@app.post("/agents/chat")
def chat(request: ChatRequest) -> ChatResponse:
    if LLM == "mock":
        return run_mock(request.message, request.building_id)
    return run_llm(request.message, request.building_id, request.session_id)


def _investigate(event: Event) -> None:
    prompt = (
        f"Automatic trigger. Event {event.type} ({event.provenance.value}) at {event.ts.isoformat()} "
        f"with details {json.dumps(event.payload)}. Investigate now: check the building status, decide whether a "
        "simulation is needed and run it through the advisor, assess risk, and propose actions for expert review "
        "if warranted. Summarize in three sentences."
    )
    try:
        if LLM == "mock":
            response = run_mock(prompt, event.building_id, trigger=True)
        else:
            response = run_llm(prompt, event.building_id, session_id=f"trigger-{event.building_id}")
        T._post("/events", {
            "type": "agent_note",
            "building_id": event.building_id,
            "source": "agent",
            "provenance": "model_output",
            "payload": {"trigger_event_id": event.event_id, "trigger_type": event.type, **response.model_dump()},
        })
    except Exception:  # noqa: BLE001 - a failed investigation must not crash the server
        log.exception("Investigation for %s failed", event.event_id)


@app.post("/agents/trigger")
def trigger(event: Event, background: BackgroundTasks) -> dict:
    now = time.time()
    with _lock:
        if now - _last_trigger.get(event.building_id, 0.0) < TRIGGER_COOLDOWN_S:
            return {"accepted": False, "reason": "cooldown"}
        _last_trigger[event.building_id] = now
    background.add_task(_investigate, event)
    return {"accepted": True}
