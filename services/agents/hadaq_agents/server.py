"""Agents API (owner: Omar).

Run:  uvicorn hadaq_agents.server:app --reload --port 8100
LLM:  HADAQ_LLM=anthropic:claude-sonnet-5-5 (needs ANTHROPIC_API_KEY) or HADAQ_LLM=mock (default, no key needed).

POST /agents/chat     user questions from the web chat
POST /agents/trigger  events forwarded by the API (storm_started, moisture_anomaly, detection_ready)

Two investigation tracks run on their own: a new analysed photo (detection_ready) goes to the Damage and Treatment
agent, rain and moisture events go to the Simulation and Advisory agent. Each track has its own cooldown.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from typing import Callable

from fastapi import BackgroundTasks, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from hadaq_agents import knowledge as KB
from hadaq_agents import tools as T
from hadaq_agents.prompts import DAMAGE_TRIGGER_PROMPT, MOISTURE_TRIGGER_PROMPT
from hadaq_agents.verifier import verify
from hadaq_schemas import ChatRequest, ChatResponse, Event

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("hadaq.agents")

LLM = os.getenv("HADAQ_LLM", "mock")
TRIGGER_COOLDOWN_S = float(os.getenv("TRIGGER_COOLDOWN_S", "60"))
DAMAGE_TRIGGERS = {"detection_ready"}

app = FastAPI(title="Hadaq Agents", version="0.2.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

_graph = None
_sessions: dict[str, list] = {}
_last_trigger: dict[str, float] = {}
_lock = threading.Lock()

# Keyword routing for the mock agent (lower case). The real LLM decides on its own.
RAIN_WORDS = ("rain", "storm", "simulat", "moist", "wet", "drain", "what if", "مطر", "عاصف", "محاكا", "رطوب", "تصريف", "سيول")
COMPARE_WORDS = ("drain", "compare", "intervention", "what if", "تصريف", "قارن")
DAMAGE_WORDS = (
    "damage", "defect", "crack", "plaster", "erosion", "treat", "repair", "material", "tools", "report", "salt", "holes",
    "burrow", "parapet", "timber", "cement", "photo",
    "تلف", "شق", "تشقق", "لياس", "طلاء", "تآكل", "معالج", "علاج", "ترميم", "مواد", "أدوات", "ادوات", "تقرير", "أملاح",
    "املاح", "فجو", "جحور", "شرفات", "خشب", "إسمنت", "اسمنت", "صور",
)
REPORT_WORDS = ("report", "submit", "تقرير", "ارفع", "أرسل", "ارسل")
TYPE_WORDS = {
    "crack": ("crack", "شق", "تشقق"),
    "plaster_loss": ("plaster", "render", "لياس", "طلاء"),
    "base_erosion": ("erosion", "eroded", "تآكل"),
    "rain_grooves": ("gull", "أخدود", "أخاديد", "اخاديد"),
    "salt_efflorescence": ("salt", "efflorescence", "أملاح", "املاح", "تزهر", "تزهّر"),
    "voids_burrows": ("holes", "burrow", "فجوة", "فجوات", "جحور"),
    "parapet_loss": ("parapet", "crenel", "شرفات", "زخارف"),
    "incompatible_repair": ("cement", "incompatible", "إسمنت", "اسمنت"),
    "timber_decay": ("timber", "wood", "beam", "خشب", "ميزاب", "مزراب"),
    "biological_growth": ("biological", "moss", "lichen", "fung", "mould", "mold", "نمو حيوي", "فطري", "طحالب"),
    "structural_deformation": ("structural", "leaning", "bulg", "settlement", "إنشائي", "انشائي", "ميلان", "هبوط", "انتفاخ"),
}
RISK_LEVEL_AR = {"low": "منخفض", "medium": "متوسط", "high": "عالي", "very_high": "عالي جداً"}


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
    last = out["messages"][-1]
    answer = text_of(last).strip()
    if not answer:
        refused = getattr(last, "response_metadata", {}).get("stop_reason") == "refusal"
        log.warning("Empty LLM answer (stop_reason=%s)", getattr(last, "response_metadata", {}).get("stop_reason"))
        answer = ("The model declined this request. Ask about the wall's damage, its treatment, materials and tools, or the rain risk."
                  if refused else "No answer came back from the model. Please ask again.")
    history.extend([HumanMessage(message), AIMessage(answer)])
    del history[:-12]  # keep the last 6 exchanges as plain text memory
    return _finish(answer, tool_outputs, calls)


def _llm_unavailable(exc: Exception, message: str, building_id: str, **mock_args) -> ChatResponse:
    """The LLM failed (network, key, rate limit): answer with the fixed plan, clearly labelled, so the demo keeps going."""
    log.exception("LLM call failed (%s); answering with the fixed plan", type(exc).__name__)
    response = run_mock(message, building_id, **mock_args)
    response.answer = f"(The language model is unavailable right now: {type(exc).__name__}. Fixed-plan answer.)\n" + response.answer
    return response


def _llm_ready() -> bool:
    """True when the configured provider has credentials (never returns the key itself)."""
    provider = LLM.partition(":")[0]
    keys = {"anthropic": ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"), "openai": ("OPENAI_API_KEY",)}.get(provider, ())
    return LLM != "mock" and (not keys or any(os.getenv(k) for k in keys))


# ---------- mock agent: fixed plans with the real tools, for development without an LLM key ----------

def _is_arabic(text: str) -> bool:
    return any("؀" <= ch <= "ۿ" for ch in text)


def _has(text: str, words: tuple[str, ...]) -> bool:
    return any(w in text for w in words)


def _mentioned_types(text: str) -> list[str]:
    return [damage_type for damage_type, words in TYPE_WORDS.items() if _has(text, words)]


def _short_zone(zone_id: str) -> str:
    """B01-W01-R2C5 -> R2C5 (the full ID stays in the evidence records)."""
    return zone_id.split("-")[-1]


def _treatment_lines(t: dict, ar: bool, whole_row: bool = False) -> list[str]:
    """Treatment, flags, materials, tools and source of one table row, word for word from get_treatment, as bullets."""
    lang = "ar" if ar else "en"
    sep = "، " if ar else ", "
    action = t[f"treatment_{lang}"] if whole_row else KB.step(t, lang)
    lines = []
    if t["fix_moisture_source_first"]:
        lines.append("- يُعالج مصدر الرطوبة أولاً." if ar else "- Treat the moisture source first.")
    if t.get(f"first_step_{lang}") and not whole_row:
        lines.append(f"- **{'أولاً' if ar else 'First'}:** {t[f'first_step_{lang}']}")
    lines.append(f"- **{'المعالجة' if ar else 'Treatment'}:** {action}")
    if t["test_on_small_area_first"]:
        lines.append("- تُجرَّب أي كمادات أو مواد كيميائية على منطقة صغيرة أولاً." if ar
                     else "- Test any poultice or chemical on a small area first.")
    if t.get(f"avoid_{lang}"):
        lines.append(f"- **{'يُتجنب' if ar else 'Avoid'}:** {sep.join(t[f'avoid_{lang}'])}")
    lines.append(f"- **{'المواد' if ar else 'Materials'}:** {sep.join(t[f'materials_{lang}'])}")
    lines.append(f"- **{'الأدوات' if ar else 'Tools'}:** {sep.join(t[f'tools_{lang}'])}")
    source = t[f"source_label_{lang}"]
    if t["source"] == "team_proposal":
        source += f" ({t[f'source_status_{lang}']})"
    lines.append(f"- **{'المصدر' if ar else 'Source'}:** {source}")
    return lines


def _finding_lines(n: int, f: dict, t: dict, ar: bool) -> list[str]:
    """One finding: a bold title line, where it is, the measured facts, then the treatment bullets."""
    severity = f.get("severity")
    zone = _short_zone(f["zone_id"])
    if ar:
        lines = [f"**{n}. {t['name_ar']} في {zone}** [{f['detection_id']}]", f"{f['position_ar']}."]
        if f.get("crack_width_mm") is not None:
            width = f"- **العرض:** {f['crack_width_mm']} مم [{f['crack_width_evidence_id']}]"
            lines.append(width + (f"، الشدة: {KB.SEVERITY_AR[severity]}" if severity else ""))
        elif severity:
            lines.append(f"- **الشدة:** {KB.SEVERITY_AR[severity]}")
        if f.get("growing"):
            lines.append(f"- **يكبر:** {f['growth_pct']}%" if f.get("growth_pct") is not None else "- **يكبر**")
        lines.append(f"- **الأولوية:** {KB.PRIORITY_AR[f['priority']]}")
        lines.append(f"- **ثقة الكاشف:** {f['confidence']}")
        if f.get("needs_review") and f.get("alternatives"):
            alternatives = "، ".join(KB.types()[a]["name_ar"] for a in f["alternatives"])
            lines.append(f"- النوع يحتاج تأكيد المختص (البديل: {alternatives}).")
    else:
        lines = [f"**{n}. {t['name_en']} at {zone}** [{f['detection_id']}]", f"{f['position_en'][0].upper()}{f['position_en'][1:]}."]
        if f.get("crack_width_mm") is not None:
            width = f"- **Width:** {f['crack_width_mm']} mm [{f['crack_width_evidence_id']}]"
            lines.append(width + (f", {severity}" if severity else ""))
        elif severity:
            lines.append(f"- **Severity:** {severity}")
        if f.get("growing"):
            lines.append(f"- **Growing:** {f['growth_pct']}%" if f.get("growth_pct") is not None else "- **Growing**")
        lines.append(f"- **Priority:** {f['priority']}")
        lines.append(f"- **Detector confidence:** {f['confidence']}")
        if f.get("needs_review") and f.get("alternatives"):
            alternatives = ", ".join(KB.types()[a]["name_en"] for a in f["alternatives"])
            lines.append(f"- The type needs the expert's confirmation (alternative: {alternatives}).")
    lines.extend(_treatment_lines(t, ar))
    if f.get("active"):
        lines.append("- الشق يكبر: مهندس إنشائي يفحصه قبل أي حقن أو تعبئة." if ar
                     else "- The crack is growing: a structural engineer should check it before any filling.")
    elif severity == "structural" or t["refer_to_engineer"]:
        lines.append("- يُحال لمهندس إنشائي." if ar else "- Refer it to a structural engineer.")
    return lines


def _brief_line(n: int, f: dict, t: dict, ar: bool) -> str:
    """One line per finding for automatic notes: the report card in the activity feed carries the details."""
    tags = []
    if f.get("severity"):
        tags.append(KB.SEVERITY_AR[f["severity"]] if ar else f["severity"])
    if f.get("growing"):
        grow = f" {f['growth_pct']}%" if f.get("growth_pct") is not None else ""
        tags.append(f"يكبر{grow}" if ar else f"growing{grow}")
    name = t["name_ar" if ar else "name_en"]
    tag_text = f" ({('، ' if ar else ', ').join(tags)})" if tags else ""
    return f"- **{name} {_short_zone(f['zone_id'])}**{tag_text} [{f['detection_id']}]: {KB.step(t, 'ar' if ar else 'en')}"


def _mock_damage(call: Callable, lines: list[str], building_id: str, text: str, ar: bool, submit: bool,
                 brief: bool = False) -> None:
    found = call(T.get_damage_findings, building_id=building_id)
    findings = found.get("findings", [])
    wanted = _mentioned_types(text)
    shown = [f for f in findings if not wanted or f["damage_type"] in wanted]
    # Most urgent first; the wall order (base row first, left to right) breaks ties.
    shown.sort(key=lambda f: -KB.PRIORITY_ORDER.get(f.get("priority", "medium"), 1))
    if not findings:
        lines.append("ما فيه صورة محللة فيها تلف بعد: ارفع صورة للجدار أو شغّل الكاميرا." if ar
                     else "No analysed photo with damage yet: upload a wall photo or start the camera.")
    elif shown:
        provenance = findings[0].get("provenance") or ""
        simulated = provenance == "synthetic"
        counts = {p: sum(1 for f in shown if f.get("priority") == p) for p in ("high", "medium", "low")}
        split_ar = "، ".join(f"{n} {KB.PRIORITY_AR[p]}" for p, n in counts.items() if n)
        split_en = ", ".join(f"{n} {p}" for p, n in counts.items() if n)
        lines.append(f"**آخر صورة للجدار{' (محاكاة)' if simulated else ''}:** {KB.count_ar(len(shown))} ({split_ar}) [{found['photo_id']}]" if ar
                     else f"**Latest wall photo{' (simulated)' if simulated else ''}:** "
                          f"{len(shown)} finding{'' if len(shown) == 1 else 's'} ({split_en} priority)"
                          f"{', most urgent first' if len(shown) > 1 else ''} [{found['photo_id']}]")
    treatments: dict[tuple[str, str], dict] = {}
    for n, f in enumerate(shown, 1):
        if not f["damage_type"]:
            lines.append("")
            lines.append(f"**{n}. {f['detector_class']} في {_short_zone(f['zone_id'])}** [{f['detection_id']}]: ما له نوع في الجدول، المختص يصنفه." if ar
                         else f"**{n}. {f['detector_class']} at {_short_zone(f['zone_id'])}** [{f['detection_id']}]: no table row for it, the expert classifies it.")
            continue
        key = (f["damage_type"], f.get("severity") or "")
        if key not in treatments:
            treatments[key] = call(T.get_treatment, damage_type=f["damage_type"], severity=f.get("severity") or "")
        if brief:
            lines.append(_brief_line(n, f, treatments[key], ar))
        else:
            lines.append("")
            lines.extend(_finding_lines(n, f, treatments[key], ar))
    for damage_type in wanted:
        if not any(f["damage_type"] == damage_type for f in findings):
            t = call(T.get_treatment, damage_type=damage_type, severity="")
            lines.append("")
            lines.append(f"**{t['name_ar']}:** ما ظهر في آخر صورة. من جدول المعالجات:" if ar
                         else f"**{t['name_en']}:** not seen in the latest photo. From the treatment table:")
            lines.extend(_treatment_lines(t, ar, whole_row=True))
    if not findings:
        return
    if submit:
        items = [{"detection_id": f["detection_id"], "damage_type": f["damage_type"], "severity": f.get("severity")}
                 for f in findings if f["damage_type"]]
        report = call(T.propose_damage_report, building_id=building_id, items=items,
                      summary="Damage found in the latest wall photo, mapped onto the team's treatment table.")
        if report.get("event_id"):
            before = report.get("first_steps_ar" if ar else "first_steps_en", [])
            if before:
                lines.append("")
                lines.append("**قبل أي ترميم:**" if ar else "**Before any repair:**")
                lines.extend(f"- {step}." for step in before)
            lines.append("")
            lines.append(f"**أُرسل تقرير التلف لمراجعة المختص** [{report['event_id']}]. الأولوية العامة: {KB.PRIORITY_AR[report['priority']]}. "
                         "بانتظار اعتماد المختص." if ar else
                         f"**Damage report sent for expert review** [{report['event_id']}]. Overall priority: {report['priority']}. "
                         "Waiting for expert approval.")
        else:
            lines.append(f"Report not sent: {report.get('error')}")
    elif shown:
        lines.append("")
        lines.append("اطلب \"تقرير التلف\" عشان يوصل للمختص للمراجعة والاعتماد." if ar
                     else "Want it sent to the conservation expert? Ask for the damage report and it goes to them for review and approval.")


def _mock_damage_summary(call: Callable, lines: list[str], building_id: str, ar: bool) -> None:
    found = call(T.get_damage_findings, building_id=building_id)
    findings = [f for f in found.get("findings", []) if f.get("damage_type")]
    if not findings:
        return
    parts = []
    for f in findings:
        tags = []
        if f.get("severity"):
            tags.append(KB.SEVERITY_AR[f["severity"]] if ar else f["severity"])
        if f.get("growing"):
            tags.append("يكبر" if ar else "growing")
        zone = _short_zone(f["zone_id"])
        part = f"- {f['name_ar']} في {zone}" if ar else f"- {f['name_en']} at {zone}"
        parts.append(part + (f" ({'، '.join(tags) if ar else ', '.join(tags)})" if tags else ""))
    lines.append("")
    lines.append(f"**التلف الظاهر في آخر صورة** [{found['photo_id']}]" if ar else f"**Visible damage in the latest photo** [{found['photo_id']}]")
    lines.extend(parts)
    lines.append("اسأل عن المعالجة عشان تعرف المواد والأدوات." if ar
                 else f"Ask how to treat {'it' if len(parts) == 1 else 'them'} to see the treatment, materials and tools.")


def run_mock(message: str, building_id: str, trigger: bool = False, track: str | None = None) -> ChatResponse:
    """Fixed plan with the real tools, for development without an LLM key. Every number comes from a tool output."""
    outputs: list[str] = []
    calls: list[dict] = []

    def call(fn, **args):
        calls.append({"name": fn.name, "args": args})
        raw = fn.invoke(args)
        outputs.append(raw)
        return json.loads(raw)

    text = message.lower()
    ar = _is_arabic(message) and not trigger
    damage_trigger = trigger and track == "damage"
    moisture_trigger = trigger and not damage_trigger
    wants_compare = not trigger and _has(text, COMPARE_WORDS)
    wants_sim = moisture_trigger or wants_compare or (not trigger and _has(text, RAIN_WORDS))
    wants_damage = damage_trigger or (not trigger and (_has(text, DAMAGE_WORDS) or bool(_mentioned_types(text))))
    general = not (wants_sim or wants_damage)
    submit = damage_trigger or (wants_damage and _has(text, REPORT_WORDS))
    lines: list[str] = []  # no label on top: mock mode is visible in GET /health ("llm": "mock")

    if wants_sim or general:
        status = call(T.get_building_status, building_id=building_id)
        sensors = call(T.get_sensor_overview, building_id=building_id)
        label = {x["sensor_id"]: x.get("label") for x in sensors}

        def name(sensor_id: str) -> str:  # "B01-S05 (Upper wall moisture)"; Arabic keeps the ID only (labels are English)
            return sensor_id if ar or not label.get(sensor_id) else f"{sensor_id} ({label[sensor_id]})"

        simulated = any(x.get("provenance") == "synthetic" for x in sensors)
        lines.append(f"**الحساسات الحين**{' (محاكاة)' if simulated else ''}" if ar else f"**Sensors right now**{' (simulated)' if simulated else ''}")
        moisture = [r for r in status["readings"] if r["kind"] == "moisture" and r.get("status", "ok") == "ok"]
        if moisture:
            m = max(moisture, key=lambda r: r["value"])
            lines.append(f"- **أعلى رطوبة:** {m['value']} {m['unit']} في {_short_zone(m['zone_id'])}، {name(m['sensor_id'])} [{m['evidence_id']}]" if ar
                         else f"- **Highest moisture:** {m['value']} {m['unit']} at {_short_zone(m['zone_id'])}, {name(m['sensor_id'])} [{m['evidence_id']}]")
        rising = [x for x in sensors if x["status"] == "ok" and x.get("trend") == "rising" and x["kind"] == "moisture"]
        if rising:
            r = max(rising, key=lambda x: x["change"])
            lines.append(f"- **أسرع ارتفاع:** {name(r['sensor_id'])} في {_short_zone(r['zone_id'])}، من {r['first']} إلى {r['latest']} {r['unit']} "
                         f"[{r['first_evidence_id']}] [{r['latest_evidence_id']}]" if ar
                         else f"- **Fastest rising:** {name(r['sensor_id'])} at {_short_zone(r['zone_id'])}, {r['first']} to {r['latest']} {r['unit']} "
                              f"[{r['first_evidence_id']}] [{r['latest_evidence_id']}]")
        distrust = [x for x in sensors if x["status"] not in ("ok", "no_data")]
        reporting = [x for x in sensors if x["status"] != "no_data"]
        if distrust:
            listed = "; ".join(f"{name(x['sensor_id'])} {x['status']} ({', '.join(x['reasons'])})" for x in distrust)
            lines.append(f"- **ما اعتمدنا عليها:** {listed}" if ar else f"- **Left out (sensor health):** {listed}")
        elif reporting:
            lines.append(f"- **الصحة:** كل الحساسات ({len(reporting)}) تقرأ طبيعي، ولا فيها عطل ولا قراءة عالقة" if ar
                         else f"- **Health:** all {len(reporting)} sensors report normally (no faults, nothing stuck or stale)")

        if wants_compare or wants_sim:
            lines.append("")
            lines.append("**سيناريو المطر** (نموذج غير معاير، للاسترشاد فقط)" if ar else "**Rain scenario** (uncalibrated model, indicative only)")
        if wants_compare:
            cmp_ = call(T.compare_scenarios, building_id=building_id)
            hours = cmp_.get("base_row_hours_above_threshold", {})
            ids = cmp_.get("run_ids", {})
            names_ar = {"storm_replay": "العاصفة كما هي", "drainage_fixed": "بعد إصلاح التصريف", "rain_x2": "ضعف المطر", "baseline": "بدون عاصفة"}
            for k, v in hours.items():
                pretty = k.replace("_", " ")
                lines.append(f"- **{names_ar.get(k, pretty)}:** {v} ساعة رطوبة عالية في صف القاعدة (مجموع مناطقه) [{ids.get(k)}]" if ar
                             else f"- **{pretty.capitalize()}:** {v} wet hours in the base row (all its zones added up) [{ids.get(k)}]")
            if "storm_replay" in hours and "drainage_fixed" in hours:
                lines.append(f"- **الخلاصة:** إصلاح التصريف ينزّلها من {hours['storm_replay']} إلى {hours['drainage_fixed']} ساعة." if ar
                             else f"- **Takeaway:** fixing the drainage cuts them from {hours['storm_replay']} to {hours['drainage_fixed']} h.")
        if wants_sim:
            sim = call(T.run_rain_simulation, building_id=building_id, scenario="storm_replay")
            if sim.get("wettest_zones"):
                z = sim["wettest_zones"][0]
                peak, limit = round(z["peak_moisture"] * 100), round(sim["threshold"] * 100)
                lines.append(f"- **أرطب منطقة:** {_short_zone(z['zone_id'])}، امتلأت {peak}% وبقيت {z['hours_above_threshold']} ساعة فوق {limit}% [{sim['run_id']}]" if ar
                             else f"- **Wettest zone:** {_short_zone(z['zone_id'])}, {peak}% full at its peak, "
                                  f"{z['hours_above_threshold']} h above {limit}% [{sim['run_id']}]")

        risk = call(T.get_risk, building_id=building_id, top=5)
        lines.append("")
        lines.append("**خطر المطر**" if ar else "**Rain risk**")
        if risk:
            top = risk[0]
            lines.append(f"- **الأعلى:** {_short_zone(top['zone_id'])}، {RISK_LEVEL_AR.get(top['level'], top['level'])} (الشدة {top['magnitude']} من 15) [{top['risk_id']}]" if ar
                         else f"- **Highest:** {_short_zone(top['zone_id'])}, {top['level'].replace('_', ' ')} (magnitude {top['magnitude']} of 15) [{top['risk_id']}]")
            high = [r for r in risk if r["level"] in ("high", "very_high")]
            if high:
                evidence = list(dict.fromkeys(e for r in high[:3] for e in [r["risk_id"], *r.get("evidence_ids", [])[:1]]))
                zones = [r["zone_id"] for r in high[:3]]
                short = [_short_zone(z) for z in zones]
                rec = call(T.propose_recommendation, building_id=building_id,
                           action="Inspect the base render and ground drainage at the wall foot; check for pooling after rain",
                           zone_ids=zones, priority="high", evidence_ids=evidence,
                           rationale="High rain risk on base zones in the simulation")
                lines.append(f"- **اقتراح للمختص:** فحص لياسة القاعدة والتصريف عند {'، '.join(short)} [{rec['event_id']}]. بانتظار الاعتماد." if ar
                             else f"- **Proposed to the expert:** inspect the base render and drainage at {', '.join(short)} [{rec['event_id']}]. Waiting for approval.")
        else:
            lines.append("- ما فيه درجات خطر بعد: شغّل محاكاة أولاً." if ar else "- No risk scores yet: run a simulation first.")

    if wants_damage:
        _mock_damage(call, lines, building_id, "" if trigger else text, ar, submit, brief=trigger)
    elif general:
        _mock_damage_summary(call, lines, building_id, ar)
    return _finish("\n".join(lines), outputs, calls)


# ---------- HTTP ----------

@app.get("/health")
def health() -> dict:
    from hadaq_agents.graph import LLM_EFFORT

    return {"status": "ok", "llm": LLM, "llm_ready": _llm_ready(), "effort": None if LLM == "mock" else LLM_EFFORT}


@app.post("/agents/chat")
def chat(request: ChatRequest) -> ChatResponse:
    if LLM == "mock":
        return run_mock(request.message, request.building_id)
    try:
        return run_llm(request.message, request.building_id, request.session_id)
    except Exception as exc:  # noqa: BLE001 - any LLM failure falls back to the labelled fixed plan
        return _llm_unavailable(exc, request.message, request.building_id)


def _track(event_type: str) -> str:
    return "damage" if event_type in DAMAGE_TRIGGERS else "moisture"


def _investigate(event: Event) -> None:
    track = _track(event.type)
    template = DAMAGE_TRIGGER_PROMPT if track == "damage" else MOISTURE_TRIGGER_PROMPT
    prompt = template.format(event_type=event.type, provenance=event.provenance.value, ts=event.ts.isoformat(),
                             details=json.dumps(event.payload, ensure_ascii=False))
    try:
        if LLM == "mock":
            response = run_mock(prompt, event.building_id, trigger=True, track=track)
        else:
            try:
                response = run_llm(prompt, event.building_id, session_id=f"trigger-{event.building_id}-{track}")
            except Exception as exc:  # noqa: BLE001 - the investigation still runs, on the fixed plan
                response = _llm_unavailable(exc, prompt, event.building_id, trigger=True, track=track)
        T._post("/events", {
            "type": "agent_note",
            "building_id": event.building_id,
            "source": "agent",
            "provenance": "model_output",
            "payload": {"trigger_event_id": event.event_id, "trigger_type": event.type, "track": track, **response.model_dump()},
        })
    except Exception:  # noqa: BLE001 - a failed investigation must not crash the server
        log.exception("Investigation for %s failed", event.event_id)


@app.post("/agents/trigger")
def trigger(event: Event, background: BackgroundTasks) -> dict:
    track = _track(event.type)
    key = f"{event.building_id}:{track}"
    now = time.time()
    with _lock:
        if now - _last_trigger.get(key, 0.0) < TRIGGER_COOLDOWN_S:
            return {"accepted": False, "reason": "cooldown", "track": track}
        _last_trigger[key] = now
    background.add_task(_investigate, event)
    return {"accepted": True, "track": track}
