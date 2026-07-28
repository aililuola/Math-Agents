from __future__ import annotations

from typing import Any


def export_hierarchical_checkpoint(
    *,
    current_round: int,
    graph_frozen: bool,
    final_repair_failed: bool,
    proof_debt_history: dict[str, list[float]] | None,
    route_team_reviews: dict[str, list[dict[str, Any]]] | None,
    capability_domain: str,
    route_registry: Any,
    typed_memory: Any,
    proof_graph: Any,
    message_broker: Any,
    bridge_broker: Any,
    contradiction_broker: Any,
    inspiration_engine: Any,
    capability_profile: Any,
    deep_exploration_registry: Any = None,
    proof_control: Any = None,
) -> dict[str, Any]:
    def state_of(component: Any) -> dict[str, Any] | None:
        return component.export_state() if component is not None else None

    payload = {
        "current_round": current_round,
        "graph_frozen": graph_frozen,
        "final_repair_failed": final_repair_failed,
        "proof_debt_history": proof_debt_history or {},
        "route_team_reviews": route_team_reviews or {},
        "capability_domain": capability_domain,
        "route_registry": state_of(route_registry),
        "typed_memory": state_of(typed_memory),
        "proof_graph": state_of(proof_graph),
        "message_broker": state_of(message_broker),
        "bridge_broker": state_of(bridge_broker),
        "contradiction_broker": state_of(contradiction_broker),
        "inspiration_engine": state_of(inspiration_engine),
        "agent_capability": state_of(capability_profile),
        "deep_exploration_registry": state_of(deep_exploration_registry),
    }
    if proof_control is not None:
        payload["proof_control_state"] = state_of(proof_control)
    return payload
