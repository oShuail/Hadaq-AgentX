"""Two LangGraph agents: the Orchestrator and the Simulation and Advisory agent.

Orchestrator --(tool: ask_simulation_advisor)--> Simulation and Advisory agent --(tools)--> Hadaq API
Both are tool-calling loops: agent node -> tools node -> agent node ... until the model stops calling tools.
"""

from __future__ import annotations

import json
from typing import Any

from langchain.chat_models import init_chat_model
from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.graph import START, MessagesState, StateGraph
from langgraph.prebuilt import ToolNode, tools_condition

from hadaq_agents.prompts import ADVISOR_PROMPT, ORCHESTRATOR_PROMPT
from hadaq_agents.tools import ADVISOR_TOOLS, ORCHESTRATOR_TOOLS


def text_of(message: BaseMessage | Any) -> str:
    """LLM content can be a string or a list of blocks (Anthropic); return plain text."""
    content = getattr(message, "content", message)
    if isinstance(content, str):
        return content
    return "".join(block.get("text", "") for block in content if isinstance(block, dict))


def _build_agent(model_name: str, system_prompt: str, tools: list):
    llm = init_chat_model(model_name, temperature=0).bind_tools(tools)

    def agent(state: MessagesState) -> dict:
        return {"messages": [llm.invoke([SystemMessage(system_prompt), *state["messages"]])]}

    graph = StateGraph(MessagesState)
    graph.add_node("agent", agent)
    graph.add_node("tools", ToolNode(tools))
    graph.add_edge(START, "agent")
    graph.add_conditional_edges("agent", tools_condition)
    graph.add_edge("tools", "agent")
    return graph.compile()


def build_agents(model_name: str):
    """Returns the compiled Orchestrator graph (it contains the advisor as a tool)."""
    advisor = _build_agent(model_name, ADVISOR_PROMPT, ADVISOR_TOOLS)

    @tool
    def ask_simulation_advisor(question: str) -> str:
        """Delegate to the Simulation and Advisory agent. It decides which physics simulation to run, runs it,
        compares scenarios, scores risk and proposes actions for expert review. Include the situation and the question."""
        out = advisor.invoke({"messages": [HumanMessage(question)]}, config={"recursion_limit": 30})
        tool_outputs = [text_of(m) for m in out["messages"] if isinstance(m, ToolMessage)]
        return json.dumps({"advisor_answer": text_of(out["messages"][-1]), "advisor_tool_outputs": tool_outputs})

    return _build_agent(model_name, ORCHESTRATOR_PROMPT, [*ORCHESTRATOR_TOOLS, ask_simulation_advisor])
