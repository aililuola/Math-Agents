from __future__ import annotations

import ast
from pathlib import Path

import pytest


CASES = [
    ("src/mathproofmesh/orchestrator.py", "_verify_proof_delta"),
    ("src/mathproofmesh/orchestrator.py", "_call_detailed_reviewers"),
    ("src/mathproofmesh/orchestrator.py", "_synthesize"),
    ("src/mathproofmesh/orchestrator.py", "_revise_final"),
    ("src/mathproofmesh/orchestrator.py", "_build_blind_review_packet"),
    ("src/mathproofmesh/synthesis_phase.py", "build_blind_review_packet"),
]


@pytest.mark.parametrize(("relative_path", "function_name"), CASES)
def test_global_context_stages_do_not_read_legacy_verified_memory_directly(
    relative_path: str,
    function_name: str,
) -> None:
    root = Path(__file__).resolve().parents[1]
    source = (root / relative_path).read_text(encoding="utf-8")
    tree = ast.parse(source)
    function = next(
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef)
        and node.name == function_name
    )
    direct_verified_calls = [
        node
        for node in ast.walk(function)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "verified"
    ]

    assert direct_verified_calls == []
