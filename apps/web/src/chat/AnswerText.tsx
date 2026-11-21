// Renders an agent answer (owner: Omar): blank lines split blocks, "- " lines become a list, **text** is bold, and
// evidence IDs in [brackets] become small chips (clickable when onEvidence is given). Plain React elements only,
// no HTML from the answer is injected.
import type { ReactNode } from "react";

const EVIDENCE = /\[((?:sim|det|evt|ph|risk)_[0-9a-f]{12}|rd_\d+)\]/g;

function inline(text: string, onEvidence?: (id: string) => void): ReactNode[] {
  const out: ReactNode[] = [];
  text.split(/(\*\*[^*]+\*\*)/g).forEach((part, i) => {
    if (part.startsWith("**") && part.endsWith("**") && part.length > 4) {
      out.push(<strong key={`b${i}`}>{part.slice(2, -2)}</strong>);
      return;
    }
    let last = 0;
    for (const m of part.matchAll(EVIDENCE)) {
      if (m.index! > last) out.push(part.slice(last, m.index));
      const id = m[1];
      out.push(
        onEvidence ? (
          <button key={`e${i}-${m.index}`} className="ev-chip" onClick={() => onEvidence(id)} title="Open the record behind this">{id}</button>
        ) : (
          <span key={`e${i}-${m.index}`} className="ev-chip">{id}</span>
        ),
      );
      last = m.index! + m[0].length;
    }
    if (last < part.length) out.push(part.slice(last));
  });
  return out;
}

export default function AnswerText({ text, onEvidence }: { text: string; onEvidence?: (id: string) => void }) {
  const blocks: ReactNode[] = [];
  let list: string[] = [];
  let para: string[] = [];
  const flush = () => {
    if (para.length) {
      blocks.push(
        <p key={blocks.length}>
          {para.map((line, i) => (
            <span key={i}>
              {i > 0 && <br />}
              {inline(line, onEvidence)}
            </span>
          ))}
        </p>,
      );
      para = [];
    }
    if (list.length) {
      blocks.push(<ul key={blocks.length}>{list.map((item, i) => <li key={i}>{inline(item, onEvidence)}</li>)}</ul>);
      list = [];
    }
  };
  for (const raw of text.split("\n")) {
    const line = raw.trimEnd();
    if (!line.trim()) {
      flush();
    } else if (/^\s*[-•]\s+/.test(line)) {
      if (para.length) {
        const keep = list;
        list = [];
        flush();
        list = keep;
      }
      list.push(line.replace(/^\s*[-•]\s+/, ""));
    } else {
      if (list.length) {
        const keep = para;
        para = [];
        flush();
        para = keep;
      }
      para.push(/^#{1,4}\s+/.test(line) ? `**${line.replace(/^#{1,4}\s+/, "")}**` : line);
    }
  }
  flush();
  return <div className="answer" dir="auto">{blocks}</div>;
}
