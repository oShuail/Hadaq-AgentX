"""Contract test of the real-LLM path, without a key or network: the real ChatAnthropic client, graph, tools and verifier
talk to a local stand-in for the Messages API. It checks what we send (no sampling parameters, effort, server-side
fallback, a cache breakpoint on the system prompt, our tools, thinking blocks passed back unchanged) and that we parse
what comes back (thinking + tool_use + text blocks).
"""

import os

os.environ.setdefault("MQTT_ENABLED", "false")
os.environ.setdefault("AGENTS_ENABLED", "false")
os.environ.setdefault("DATABASE_URL", "sqlite:///./data/test_agents.db")
os.environ.setdefault("PHOTO_DIR", "./data/test_photos")

import itertools  # noqa: E402
import json  # noqa: E402
import threading  # noqa: E402
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer  # noqa: E402

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from hadaq_agents import server, tools  # noqa: E402
from hadaq_agents.graph import build_agents  # noqa: E402
from hadaq_api.main import app as api_app  # noqa: E402
from hadaq_schemas import new_id  # noqa: E402

from test_damage import seed_wall  # noqa: E402

MODEL = "anthropic:claude-sonnet-5-5"
_ids = itertools.count(1)


def thinking(signature: str) -> dict:
    return {"type": "thinking", "thinking": "", "signature": signature}


def call(name: str, **args) -> dict:
    return {"type": "tool_use", "id": f"toolu_{next(_ids):04d}", "name": name, "input": args}


def text(value: str) -> dict:
    return {"type": "text", "text": value}


class FakeMessagesAPI:
    """Replies to POST /v1/messages with scripted assistant turns and records every request."""

    def __init__(self, replies: list[list[dict]]):
        self.replies = list(replies)
        self.requests: list[dict] = []
        api = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 - http.server naming
                body = json.loads(self.rfile.read(int(self.headers["content-length"])))
                api.requests.append({"path": self.path, "beta": self.headers.get("anthropic-beta", ""), "body": body})
                content = api.replies.pop(0)
                stop = "tool_use" if any(b["type"] == "tool_use" for b in content) else "end_turn"
                reply = {"id": f"msg_{len(api.requests):04d}", "type": "message", "role": "assistant", "model": body["model"],
                         "content": content, "stop_reason": stop, "stop_sequence": None,
                         "usage": {"input_tokens": 1200, "output_tokens": 80, "cache_creation_input_tokens": 0,
                                   "cache_read_input_tokens": 0}}
                data = json.dumps(reply).encode()
                self.send_response(200)
                self.send_header("content-type", "application/json")
                self.send_header("request-id", f"req_{len(api.requests)}")
                self.send_header("content-length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):  # keep the test output quiet
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()


@pytest.fixture()
def api():
    with TestClient(api_app) as client:
        tools.set_client(client)
        yield client


def run_against(fake: FakeMessagesAPI, monkeypatch, message: str):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key-not-real")
    monkeypatch.setenv("ANTHROPIC_BASE_URL", fake.url)
    monkeypatch.setenv("ANTHROPIC_API_URL", fake.url)
    server._graph = build_agents(MODEL)
    try:
        return server.run_llm(message, "B01", session_id=new_id("contract"))
    finally:
        server._graph = None


def test_real_client_requests_and_parsing_for_the_damage_report(api, monkeypatch):
    ids = seed_wall(api, crack_width=1.55)
    final = (f"Crack at B01-W01-R2C5, 1.55 mm wide [{ids['crack']}] and growing: a structural engineer checks it first, "
             "then a fine mud grout is injected. Waiting for expert approval.")
    fake = FakeMessagesAPI([
        [thinking("sig-orchestrator-1"), call("get_building_status", building_id="B01")],                       # orchestrator
        [thinking("sig-orchestrator-2"), call("ask_damage_advisor", question="Prepare and submit the damage report for B01.")],
        [thinking("sig-advisor-1"), call("get_damage_findings", building_id="B01")],                            # damage advisor
        [call("get_treatment", damage_type="crack", severity="fine"), call("get_treatment", damage_type="plaster_loss")],
        [call("propose_damage_report", building_id="B01",
              items=[{"detection_id": ids["crack"], "damage_type": "crack", "severity": "fine"},
                     {"detection_id": ids["plaster"], "damage_type": "plaster_loss"}],
              summary="Growing crack and plaster loss on the facade.")],
        [text(f"Report submitted for {ids['crack']}: 1.55 mm, fine, growing. Waiting for expert approval.")],
        [text(final)],                                                                                           # orchestrator
    ])
    try:
        response = run_against(fake, monkeypatch, "Prepare the damage report")
    finally:
        fake.close()

    # Parsing: the final text, the tool calls, the verifier and the approval flag all come through.
    assert response.answer == final
    assert [c["name"] for c in response.tool_calls] == ["get_building_status", "ask_damage_advisor"]
    assert response.unverified_numbers == [] and response.needs_approval and ids["crack"] in response.evidence_ids
    assert fake.replies == [] and len(fake.requests) == 7

    # What we send on every request: current-model settings, no sampling parameters, nothing forced.
    for req in fake.requests:
        body = req["body"]
        assert req["path"].startswith("/v1/messages")
        assert body["model"] == "claude-sonnet-5-5" and body["max_tokens"] == 16000
        assert not {"temperature", "top_p", "top_k"} & set(body)
        assert body["output_config"] == {"effort": "medium"}
        assert body["fallbacks"] == "default" and "server-side-fallback-2026-07-01" in req["beta"]
        assert body.get("tool_choice", {"type": "auto"})["type"] == "auto"
        assert body["system"][-1]["cache_control"] == {"type": "ephemeral"}

    # Each agent sees its own tools.
    orchestrator_tools = {t["name"] for t in fake.requests[0]["body"]["tools"]}
    advisor_tools = {t["name"] for t in fake.requests[2]["body"]["tools"]}
    assert {"get_building_status", "ask_damage_advisor", "ask_simulation_advisor"} <= orchestrator_tools
    assert advisor_tools == {"get_damage_findings", "get_treatment", "list_damage_types", "propose_damage_report"}
    report_schema = next(t for t in fake.requests[2]["body"]["tools"] if t["name"] == "propose_damage_report")["input_schema"]
    assert "$ref" not in json.dumps(report_schema)  # nested item schema is inlined for the API

    # Thinking blocks go back unchanged, in the same conversation.
    second = fake.requests[1]["body"]["messages"]
    assert any(b == thinking("sig-orchestrator-1") for m in second if m["role"] == "assistant" for b in m["content"])
    last = fake.requests[6]["body"]["messages"]
    assert any(b == thinking("sig-orchestrator-2") for m in last if m["role"] == "assistant" for b in m["content"])
    # Parallel tool results come back in one user message.
    fourth = fake.requests[4]["body"]["messages"][-1]
    assert fourth["role"] == "user" and sum(b["type"] == "tool_result" for b in fourth["content"]) == 2

    # The report the tool stored carries the table's treatment, not text from the model.
    events = api.get("/buildings/B01/events", params={"limit": 5}).json()
    report = next(e for e in events if e["type"] == "recommendation_ready" and e["payload"].get("kind") == "damage_report")
    crack = next(i for i in report["payload"]["report"]["items"] if i["detection_id"] == ids["crack"])
    assert crack["step_en"] == "Inject the crack with a fine mud grout" and crack["tools_ar"] == ["محقان", "مسطرين", "فرش ناعمة"]


def test_a_failing_llm_falls_back_to_the_labelled_fixed_plan(api, monkeypatch):
    seed_wall(api)

    class Broken:
        def invoke(self, *args, **kwargs):
            raise ConnectionError("no route to the model")

    monkeypatch.setattr(server, "_graph", Broken())
    monkeypatch.setattr(server, "LLM", MODEL)
    response = server.chat(server.ChatRequest(message="What damage is on this wall, and where?", building_id="B01"))
    assert response.answer.startswith("(The language model is unavailable right now: ConnectionError")
    assert "Grout syringe" in response.answer and response.unverified_numbers == []
