"""Three LangGraph agents: the Orchestrator and two specialists it delegates to.

Orchestrator --(ask_damage_advisor)-----> Damage and Treatment agent ---(tools)--> treatment table + Hadaq API
             --(ask_simulation_advisor)--> Simulation and Advisory agent --(tools)--> Hadaq API
Each one is a tool-calling loop: agent node -> tools node -> agent node ... until the model stops calling tools.
"""

from __future__ import annotations

import json
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


def text_of(message: BaseMessage | Any) -> str:
    """LLM content can be a string or a list of blocks (Anthropic); return plain text."""
    content = getattr(message, "content", message)
    if isinstance(content, str):
        return content
    return "".join(block.get("text", "") for block in content if isinstance(block, dict))


def _chat_model(model: str | BaseChatModel) -> BaseChatModel:
    """A "provider:model" name from HADAQ_LLM, or a ready chat model (tests pass a scripted one)."""
    return init_chat_model(model, temperature=0) if isinstance(model, str) else model


def _build_agent(llm: BaseChatModel, system_prompt: str, tools: list):
    bound = llm.bind_tools(tools)

    def agent(state: MessagesState) -> dict:
        return {"messages": [bound.invoke([SystemMessage(system_prompt), *state["messages"]])]}

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
