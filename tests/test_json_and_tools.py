from __future__ import annotations

from mathproofmesh.agents import extract_json_object
from mathproofmesh.schemas import ToolRequest
from mathproofmesh.tools import ToolBroker, UnsafeExpressionError, parse_expression


def test_balanced_json_extraction_tolerates_prose_and_braces_in_strings() -> None:
    text = 'prefix ```json\n{"a": {"b": 2}, "s": "literal } brace"}\n``` suffix'
    assert extract_json_object(text) == {"a": {"b": 2}, "s": "literal } brace"}


def test_safe_expression_parser_rejects_arbitrary_python() -> None:
    try:
        parse_expression("__import__('os').system('echo unsafe')")
    except (UnsafeExpressionError, SyntaxError, ValueError):
        pass
    else:  # pragma: no cover - safety regression guard
        raise AssertionError("arbitrary Python expression was accepted")

    assert str(parse_expression("(x+1)^2")) == "(x + 1)**2"


def test_tool_broker_equivalence_and_counterexample(demo_config, artifact_store) -> None:
    broker = ToolBroker(demo_config, artifact_store)
    equivalent = broker.execute(
        ToolRequest(
            kind="sympy_equivalent",
            arguments={"lhs": "(x+1)^2", "rhs": "x^2+2*x+1"},
            purpose="check expansion",
        )
    )
    assert equivalent.ok
    assert equivalent.result["equivalent"] is True

    refutation = broker.execute(
        ToolRequest(
            kind="numeric_counterexample",
            arguments={
                "lhs": "x^2",
                "rhs": "x",
                "relation": "eq",
                "variables": ["x"],
                "ranges": {"x": [2, 2]},
                "samples": 3,
            },
            purpose="refute a false universal identity",
        )
    )
    assert refutation.ok
    assert refutation.result["counterexample_found"] is True
