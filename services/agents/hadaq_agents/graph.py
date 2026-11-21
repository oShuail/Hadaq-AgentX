"""Three LangGraph agents: the Orchestrator and two specialists it delegates to.

Orchestrator --(ask_damage_advisor)-----> Damage and Treatment agent ---(tools)--> treatment table + Hadaq API
             --(ask_simulation_advisor)--> Simulation and Advisory agent --(tools)--> Hadaq API
Each one is a tool-calling loop: agent node -> tools node -> agent node ... until the model stops calling tools.
"""

from __future__ import annotations

import json
import os
from typing import Any

from langchain.chat_models import init_chat_model
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.graph import START, MessagesState, StateGraph
from langgraph.prebuilt import ToolNode, tools_condition

from hadaq_agents.prompts import ADVISOR_PROMPT, DAMAGE_PROMPT, ORCHESTRATOR_PROMPT
from hadaq_agents.tools import ADVISOR_TOOLS, DAMAGE_TOOLS, ORCHESTRATOR_TOOLS

ADVISOR_RECURSION_LIMIT = 40
LLM_TIMEOUT_S = float(os.getenv("HADAQ_LLM_TIMEOUT_S", "90"))
LLM_EFFORT = os.getenv("HADAQ_LLM_EFFORT", "medium")  # medium suits multi-step tool use and keeps the demo quick
# Current models (5.x): no sampling parameters (temperature is rejected), thinking is adaptive by default, effort sets
# the depth, and a server-side fallback answers when the model declines a request.
CURRENT_MODELS = ("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")


def text_of(message: BaseMessage | Any) -> str:
    """LLM content can be a string or a list of blocks (Anthropic: text, thinking, tool_use); return the plain text."""
    content = getattr(message, "content", message)
    if isinstance(content, str):
        return content
    return "".join(block.get("text", "") for block in content if isinstance(block, dict) and block.get("type", "text") == "text")


def model_settings(model: str) -> dict[str, Any]:
    """Request settings per provider. No temperature: current models reject sampling parameters."""
    provider, _, name = model.partition(":")
    settings: dict[str, Any] = {"timeout": LLM_TIMEOUT_S, "max_retries": 2}
    if provider == "anthropic":
        settings["max_tokens"] = 16000  # room for adaptive thinking plus the answer
        if name in CURRENT_MODELS:
            settings["output_config"] = {"effort": LLM_EFFORT}
            settings["betas"] = ["server-side-fallback-2026-07-01"]
            settings["model_kwargs"] = {"fallbacks": "default"}
    return settings


def _chat_model(model: str | BaseChatModel) -> BaseChatModel:
    """A "provider:model" name from HADAQ_LLM, or a ready chat model (tests pass a scripted one)."""
    return init_chat_model(model, **model_settings(model)) if isinstance(model, str) else model


def _system(prompt: str, cache: bool) -> SystemMessage:
    """The system prompt; on Anthropic it gets a cache breakpoint (tools + system are the same on every call)."""
    if not cache:
        return SystemMessage(prompt)
    return SystemMessage(content=[{"type": "text", "text": prompt, "cache_control": {"type": "ephemeral"}}])


def _build_agent(llm: BaseChatModel, system_prompt: str, tools: list):
    bound = llm.bind_tools(tools)
    system = _system(system_prompt, cache=getattr(llm, "_llm_type", "") == "anthropic-chat")

    def agent(state: MessagesState) -> dict:
        return {"messages": [bound.invoke([system, *state["messages"]])]}

    graph = StateGraph(MessagesState)
    graph.add_node("agent", agent)
    graph.add_node("tools", ToolNode(tools))
    graph.add_edge(START, "agent")
    graph.add_conditional_edges("agent", tools_condition)
    graph.add_edge("tools", "agent")
    return graph.compile()


def _ask(advisor, question: str) -> str:
    """Run a specialist and hand back its answer plus every tool output (the verifier checks numbers against them)."""
    out = advisor.invoke({"messages": [HumanMessage(question)]}, config={"recursion_limit": ADVISOR_RECURSION_LIMIT})
    tool_outputs = [text_of(m) for m in out["messages"] if isinstance(m, ToolMessage)]
    return json.dumps({"advisor_answer": text_of(out["messages"][-1]), "advisor_tool_outputs": tool_outputs}, ensure_ascii=False)


def build_agents(model: str | BaseChatModel):
    """Returns the compiled Orchestrator graph (it contains both specialists as tools)."""
    llm = _chat_model(model)
    simulation_advisor = _build_agent(llm, ADVISOR_PROMPT, ADVISOR_TOOLS)
    damage_advisor = _build_agent(llm, DAMAGE_PROMPT, DAMAGE_TOOLS)

    @tool
    def ask_simulation_advisor(question: str) -> str:
        """Delegate to the Simulation and Advisory agent. It decides which physics simulation to run, runs it,
        compares scenarios, scores risk and proposes actions for expert review. Include the situation and the question."""
        return _ask(simulation_advisor, question)

    @tool
    def ask_damage_advisor(question: str) -> str:
        """Delegate to the Damage and Treatment agent. It reads the damage found in the latest wall photo, maps each finding
        onto the team's treatment table and gives its treatment, materials and tools; it can submit the damage report for
        expert review. Include the exact question and whether the report should be submitted."""
        return _ask(damage_advisor, question)

    return _build_agent(llm, ORCHESTRATOR_PROMPT, [*ORCHESTRATOR_TOOLS, ask_simulation_advisor, ask_damage_advisor])
