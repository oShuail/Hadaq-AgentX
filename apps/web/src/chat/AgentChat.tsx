// Chat with the Orchestrator (owner: Omar). Evidence chips open the record behind each number.
import { useState } from "react";

import { api } from "../api/client";
import type { ChatResponse } from "../types/contracts";
import AnswerText from "./AnswerText";

type Message = { role: "user" | "agent"; text: string; response?: ChatResponse };

const SUGGESTIONS = [
  "What damage is on this wall, and where?",
  "وش المعالجة للتشققات؟ ووش المواد والأدوات؟",
  "Prepare the damage report for expert review",
  "What if we fix the drainage?",
];

export default function AgentChat({ buildingId }: { buildingId: string }) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [evidence, setEvidence] = useState<{ id: string; body: string } | null>(null);

  const send = async (text: string) => {
    if (!text.trim() || busy) return;
    setMessages((m) => [...m, { role: "user", text }]);
    setInput("");
    setBusy(true);
    try {
      const response = await api.chat(text, buildingId);
      setMessages((m) => [...m, { role: "agent", text: response.answer, response }]);
    } catch (err) {
      setMessages((m) => [...m, { role: "agent", text: `Agent unreachable: ${String(err)}` }]);
    } finally {
      setBusy(false);
    }
  };

  const openEvidence = async (id: string) => {
    try {
      setEvidence({ id, body: JSON.stringify(await api.evidence(id), null, 2) });
    } catch (err) {
      setEvidence({ id, body: String(err) });
    }
  };

  return (
    <div className="chat">
      <div className="messages">
        {messages.length === 0 && (
          <div className="suggestions">
            {SUGGESTIONS.map((s) => (
              <button key={s} dir="auto" onClick={() => send(s)}>{s}</button>
            ))}
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role}`}>
            {m.role === "agent" ? <AnswerText text={m.text} onEvidence={openEvidence} /> : <div className="text" dir="auto">{m.text}</div>}
            {m.response && (
              <div className="meta">
                {m.response.needs_approval && <span className="badge warn">Waiting for expert approval</span>}
                {m.response.evidence_ids.map((id) => (
                  <button key={id} className="evidence" onClick={() => openEvidence(id)}>{id}</button>
                ))}
                {m.response.tool_calls.length > 0 && (
                  <details>
                    <summary>{m.response.tool_calls.length} tool calls</summary>
                    <ul>{m.response.tool_calls.map((c, j) => <li key={j}>{c.name}</li>)}</ul>
                  </details>
                )}
              </div>
            )}
          </div>
        ))}
        {busy && <div className="msg agent muted">Investigating...</div>}
      </div>
      {evidence && (
        <div className="evidence-view">
          <div className="panel-head">
            <strong>{evidence.id}</strong>
            <button onClick={() => setEvidence(null)}>Close</button>
          </div>
          <pre>{evidence.body}</pre>
        </div>
      )}
      <form onSubmit={(e) => { e.preventDefault(); send(input); }}>
        <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="Ask the agent about B01 (Arabic or English)" dir="auto" />
        <button type="submit" disabled={busy}>Send</button>
      </form>
    </div>
  );
}
