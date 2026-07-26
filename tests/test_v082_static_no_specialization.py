from __future__ import annotations

import ast
import inspect
from pathlib import Path

from mathproofmesh.proof_control.controller import ProofControlLayer


PRODUCTION = Path(__file__).parents[1] / "src" / "mathproofmesh"


def _string_literals(node: ast.AST) -> set[str]:
    return {
        item.value.casefold()
        for item in ast.walk(node)
        if isinstance(item, ast.Constant) and isinstance(item.value, str)
    }


def test_route_admission_source_orders_blueprint_before_gate() -> None:
    source = inspect.getsource(ProofControlLayer.admit_routes)

    assert source.index("compile_strategy_blueprint") < source.index(
        "route_admission_gate.evaluate"
    )


def test_no_problem_specific_conditionals_in_production() -> None:
    prohibited = {"prime", "gcd", "a_n"}
    findings: list[str] = []
    for path in (PRODUCTION / "proof_control").rglob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, (ast.If, ast.IfExp, ast.While, ast.Match)):
                continue
            literals = _string_literals(node)
            matched = sorted(prohibited & literals)
            if matched:
                findings.append(
                    f"{path.relative_to(PRODUCTION)}:{node.lineno}:{matched}"
                )

    assert findings == []


def test_dispatcher_has_no_fact_write_or_direct_close_authority() -> None:
    source = (PRODUCTION / "proof_control" / "action_dispatcher.py").read_text(
        encoding="utf-8"
    )

    assert ".add_fact(" not in source
    assert ".close_obligation(" not in source


def test_rewrite_execution_calls_semantic_gate_first() -> None:
    source = inspect.getsource(ProofControlLayer._handle_rewrite_blueprint)

    assert source.index("rewrite_semantic_gate") < source.index(
        "apply_reviewed_rewrite"
    )


def test_proof_control_does_not_assign_reasoning_limits() -> None:
    assignments: list[str] = []
    for path in (PRODUCTION / "proof_control").rglob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, (ast.Assign, ast.AnnAssign, ast.AugAssign)):
                continue
            target_text = ast.unparse(node)
            if any(
                name in target_text
                for name in (
                    "max_output_tokens",
                    "max_total_tokens",
                    "max_total_calls",
                    "max_rounds",
                    "agent_count",
                )
            ):
                assignments.append(
                    f"{path.relative_to(PRODUCTION)}:{node.lineno}:{target_text}"
                )

    assert assignments == []
