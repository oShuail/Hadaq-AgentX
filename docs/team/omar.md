# Omar: agents, digital twin, chat, integration

**Mission:** you own Hadaq's brain and face. The two LangGraph agents decide what to investigate and run real tools; the twin view shows the result on the wall; the chat lets judges talk to it. Judges score "AI design and level of autonomy" mostly on your slice. You also integrate everyone's pieces.

## What you own

| Path | What |
| --- | --- |
| `services/agents/hadaq_agents/graph.py` | Orchestrator + Simulation and Advisory agents (LangGraph) |
| `services/agents/hadaq_agents/tools.py` | Tools that call the API (no made-up data) |
| `services/agents/hadaq_agents/prompts.py` | System prompts and rules |
| `services/agents/hadaq_agents/verifier.py` | Checks every number in an answer against tool outputs |
| `services/agents/hadaq_agents/server.py` | `POST /agents/chat`, `POST /agents/trigger`, mock mode |
| `apps/web/src/twin3d/*` | The 3D twin of Diriyah (three.js): terrain, buildings, palms, sun, focus model, wall zones |
| `apps/web/src/twin/*` | Zone colors, attention rules, scan embed |
| `apps/web/src/bake/bake.ts` | Bakes the open-data site pack in `apps/web/public/site/` (see `tools/site_data/README.md`) |
| `apps/web/src/chat/AgentChat.tsx` | Chat UI with evidence chips |
| `contracts/building_b01.json` | Building and wall coordinates (you fill them) |
| `docs/demo-script.md` | The 2-minute storyline |

## Setup (first 15 minutes)

```bash
make setup && make up
make api        # terminal 1
make agents     # terminal 2 (mock mode, no key)
make web        # terminal 3
curl -s localhost:8100/agents/chat -H 'content-type: application/json' \
  -d '{"message":"Simulate three days of heavy rain","building_id":"B01"}' | python -m json.tool
```

The mock answer already cites `sim_...`, `risk_...` and `evt_...` IDs. That proves the tools, API and simulation are wired end to end before any LLM is involved.

## Tasks for Round 1, in order

### 1. Freeze the contracts tonight (with Turky and Saleh)

Read `contracts/README.md` top to bottom. Confirm: zone grid 4 rows x 8 cols, `R1` = base; topic `hadaq/v1/{building}/sensors/{sensor_id}`; event names; evidence ID prefixes. After tonight, changes only by PR.

### 2. Pick the focus building and measure its main wall

Done for Round 1: B01 is our citadel model at a placeholder spot in At-Turaif (`contracts/building_b01.json`). To move it:

1. `make web`, open the At-Turaif view.
2. Hold **Shift and click** the two bottom corners of where the main facade should stand. The app shows `lon, lat, height`. Standing in front of the facade, the **left** corner is `base_line.start` (column C1), the **right** corner is `base_line.end`. The model, the flattened ground under it and the zones all follow `base_line` and `azimuth_deg`.
4. Compute the facade azimuth (the direction it faces) and put it in `walls[0].azimuth_deg`:

```python
import math

def bearing(lon1, lat1, lon2, lat2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360) % 360

start = (46.57235, 24.73340)   # left corner (lon, lat)
end = (46.57235, 24.73360)     # right corner
azimuth = (bearing(*start, *end) + 90) % 360
print(round(azimuth))           # facade faces this way
```

5. Estimate `height_m` from a photo with a known object (a door is about 2 m). Mark it ASSUMED in the notes.

### 3. Turn on the real LLM

In `.env`:

```bash
HADAQ_LLM=anthropic:claude-sonnet-5-5
ANTHROPIC_API_KEY=sk-ant-...
```

`init_chat_model` also accepts other providers (`openai:...` after `pip install -e "services/agents[openai]"`). Restart `make agents` and ask the four suggestion questions from the chat. Watch the terminal: you should see the Orchestrator call `get_building_status`, then `ask_simulation_advisor`, and the advisor call `run_rain_simulation`, `get_risk`, `propose_recommendation`.

If the verifier appends "these numbers were not found in any tool output", tighten `prompts.py`; do not loosen the verifier.

### 4. Make the autonomy visible

The API forwards `storm_started`, `moisture_anomaly` and `detection_ready` (only when something changed) to `POST /agents/trigger`. The agent investigates in the background and posts an `agent_note` event, which appears in the panel's Activity feed. Test:

```bash
curl -s localhost:8000/events -H 'content-type: application/json' \
  -d '{"type":"storm_started","building_id":"B01","source":"scenario_panel","provenance":"synthetic"}'
```

Within a few seconds: `simulation_done`, then `recommendation_ready`, then `agent_note`. The wall starts animating on its own because a new simulation auto-plays. That sequence is the core of the video.

`TRIGGER_COOLDOWN_S=60` stops a storm plus an anomaly from launching two investigations at once.

### 5. Add a tool (pattern)

```python
# services/agents/hadaq_agents/tools.py
@tool
def get_zone_history(building_id: str, sensor_id: str, limit: int = 50) -> str:
    """Reading history for one sensor, newest first. Use it to describe a trend."""
    rows = _get(f"/buildings/{building_id}/readings", sensor_id=sensor_id, limit=limit)
    return json.dumps([{k: r[k] for k in ("ts", "value", "unit", "provenance", "evidence_id")} for r in rows])

ADVISOR_TOOLS = [*ADVISOR_TOOLS, get_zone_history]  # or ORCHESTRATOR_TOOLS
```

Rules for tools: verb_noun names, a docstring the LLM can act on, return compact JSON with evidence IDs, never compute "facts" the API does not return.

Sensor tools already there (both agents have them):

| Tool | API | Use it for |
| --- | --- | --- |
| `get_building_status` | `GET /buildings/{b}/sensors`, detections, risk | First look; every reading carries its sensor's `status` |
| `get_sensor_overview` | `GET /buildings/{b}/sensors?window=60` | What is changing: trend, change, min, max, rate per minute, health and why, with evidence IDs |
| `get_sensor_history` | `GET /buildings/{b}/sensors/{id}?window=&points=` | How one value developed (downsampled, oldest first) |
| `get_zone_detail` | `GET /buildings/{b}/zones/{zone_id}` | One zone: its sensors, camera detections, risk and simulation line |

Readings from sensors whose status is not `ok` (fault, suspect, stale) must not be used as evidence; the prompts say so and the mock plan lists them under "Not used". Times are real time: the simulator compresses a day into two minutes, so "per minute" rates look fast.

### 6. Twin view

- `apps/web/src/twin3d/focus.ts` draws 32 panels 0.4 m in front of the facade from `base_line`, `height_m`, `rows`, `cols`, plus the attention boxes. If the panels look off, fix the coordinates in the registry, not the code.
- Layers (pill above the sun clock): Damage (default, from the latest damage report), Moisture (each zone's peak in the latest simulation) and Risk (ABC level). Colors in `twin/colors.ts`.
- Clicking a panel, or a reading card pinned next to it, opens the zone view in the Building twin panel (moisture now, ABC breakdown, sensors in that zone).

### 7. Our own model of the building

Done: `apps/web/public/models/b01_v3.glb` (the decimated citadel from `assets/turaif/`) is the default (`VITE_FOCUS_MODEL_URL`). It stands at the wall's midpoint, rotated by `azimuth_deg`; the ground under its outline (`b01_v3.footprint.json`) is flattened and the context buildings and palms there are removed.

To swap in a real scan: GLB under 50 MB with origin = middle of the main wall's base, +Y up, +X out of the facade, +Z towards `base_line.start`; save it next to a `<name>.footprint.json` and point `VITE_FOCUS_MODEL_URL` at it. See `docs/scan-guide.md`.

### 8. Integration runs

Twice a day, run the full demo from `docs/demo-script.md` with everyone's latest `main`. Log what broke in the group chat with the owner's name.

## Naming rules

- Python: `snake_case` functions, tools named `verb_noun` (`run_rain_simulation`).
- React components: `PascalCase.tsx`, one per file, in `src/<area>/`.
- Never invent an ID format: zones `B01-W01-R1C3`, evidence prefixes `sim_ det_ evt_ ph_ risk_ rd_`.

## Contracts

- Produces: `ChatResponse`, events `recommendation_ready`, `agent_note`.
- Consumes: every API endpoint through `tools.py`; events through `/agents/trigger`.

## Definition of done (21 Nov)

- [ ] Real LLM answers the four suggestion questions with evidence chips and no verifier warnings
- [ ] Start storm triggers an investigation with no human typing
- [ ] Focus building set; zones line up with the facade
- [ ] Recommendations clearly say "waiting for expert approval"
- [ ] Full demo run passes twice in a row

## Deck slides you own

AI concept (agents, autonomy, tools, decisions) and the twin and chat screens.

## Round 2

Reporting agent (PDF with 3D viewpoints), LangGraph checkpointer in Postgres, real human approval with LangGraph interrupts, Arabic RTL UI, scenario comparison view.
