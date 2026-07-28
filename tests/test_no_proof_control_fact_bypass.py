from __future__ import annotations

import ast
from pathlib import Path

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.schemas import (
    MessageEnvelope,
    ObligationKind,
    ProofObligation,
    QuantifierSpec,
    VariableBinding,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_fact,
    make_proof_control_config,
)


def _qualified_name(node: ast.AST) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        prefix = _qualified_name(node.value)
        return f"{prefix}.{node.attr}" if prefix else node.attr
    return ""


def test_proof_control_package_has_no_fact_or_graph_close_authority() -> None:
    package = Path(__file__).parents[1] / "src" / "mathproofmesh" / "proof_control"
    forbidden_calls: list[tuple[str, str]] = []
    forbidden_text: list[tuple[str, str]] = []
    for path in package.glob("*.py"):
        source = path.read_text(encoding="utf-8")
        tree = ast.parse(source, filename=str(path))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            name = _qualified_name(node.func)
            if name in {
                "self.typed_memory.add_fact",
                "self.typed_memory.promote",
                "typed_memory.add_fact",
                "typed_memory.promote",
                "self.proof_graph.close_obligation",
                "proof_graph.close_obligation",
            }:
                forbidden_calls.append((path.name, name))
        for marker in ("api_key", "reasoning_content", "max_output_tokens"):
            if marker in source:
                forbidden_text.append((path.name, marker))

    assert forbidden_calls == []
    assert forbidden_text == []


def _forall_scope() -> tuple[list[QuantifierSpec], list[VariableBinding]]:
    return (
        [
            QuantifierSpec(
                order=0,
                kind="forall",
                variable_id="n",
                display_name="n",
                domain="positive integers",
            )
        ],
        [
            VariableBinding(
                variable_id="n",
                display_name="n",
                domain="positive integers",
                owner_scope="message",
            )
        ],
    )


def test_active_scope_gate_blocks_eventual_fact_before_memory_or_graph(
    tmp_path: Path,
) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    quantifiers, bindings = _forall_scope()
    goal = graph.add_obligation(
        ProofObligation(
            obligation_id="all-scope-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a", "route-b"],
            kind=ObligationKind.MAIN_GOAL,
            statement="P(n) holds.",
            normalized_statement="p(n) holds.",
            quantifiers=quantifiers,
        )
    )
    ProofControlLayer(config, store, None, graph, memory, broker, registry)

    eventual_payload = make_fact(
        message_id="eventual-fact",
        statement="Eventually P(n) holds.",
        conclusion="Eventually P(n) holds.",
        target_routes=["route-b"],
    ).model_dump(mode="python")
    eventual_payload.update(
        {
            "normalized_statement": goal.normalized_statement,
            "content_hash": "",
        }
    )
    eventual = MessageEnvelope.model_validate(eventual_payload)
    rejected = broker.publish(
        eventual,
        referee_agent_id="referee-b",
        current_round=1,
    )

    assert rejected.accepted is False
    assert "scope" in (rejected.rejection_reason or "")
    assert all(item.message_id != eventual.message_id for item in memory.facts)
    assert graph.get_obligation(goal.obligation_id).status == "open"

    global_fact = make_fact(
        message_id="global-fact",
        statement="P(n) holds.",
        conclusion="P(n) holds.",
        target_routes=["route-b"],
        quantifiers=quantifiers,
        variable_bindings=bindings,
    )
    accepted = broker.publish(
        global_fact,
        referee_agent_id="referee-b",
        current_round=1,
    )

    assert accepted.accepted is True
    assert any(item.message_id == global_fact.message_id for item in memory.facts)
    assert graph.get_obligation(goal.obligation_id).status == "closed"
