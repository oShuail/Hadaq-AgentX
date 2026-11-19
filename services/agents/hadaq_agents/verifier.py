"""Verifier: every number in the agent's answer must appear in a tool output; collects cited evidence IDs.

This is a cheap guardrail, not proof. It catches the most common failure: a fluent answer with an invented number.
"""

from __future__ import annotations

import re

NUMBER_RE = re.compile(r"(?<![\w.])-?\d+(?:\.\d+)?")
EVIDENCE_RE = re.compile(r"\b(?:sim|det|evt|ph|risk)_[0-9a-f]{12}\b|\brd_\d+\b")
SMALL_COUNT_MAX = 32  # counts like "3 zones" or "row 1" are allowed without a source


def _decimals(text: str) -> int:
    return len(text.split(".")[1]) if "." in text else 0


def _supported(token: str, corpus_numbers: list[float]) -> bool:
    value = float(token)
    if value.is_integer() and 0 <= value <= SMALL_COUNT_MAX:
        return True
    places = _decimals(token)
    for c in corpus_numbers:
        if round(c, places) == value or round(c * 100, places) == value:  # also accept 0.79 shown as 79%
            return True
    return False


def verify(answer: str, tool_outputs: list[str]) -> tuple[list[str], list[str]]:
    """Returns (unverified_numbers, cited_evidence_ids_that_exist)."""
    corpus = " ".join(tool_outputs)
    corpus_numbers = [float(n) for n in NUMBER_RE.findall(corpus)]
    unverified = sorted({tok for tok in NUMBER_RE.findall(answer) if not _supported(tok, corpus_numbers)})
    known_ids = set(EVIDENCE_RE.findall(corpus))
    cited = [eid for eid in dict.fromkeys(EVIDENCE_RE.findall(answer)) if eid in known_ids]
    return unverified, cited
