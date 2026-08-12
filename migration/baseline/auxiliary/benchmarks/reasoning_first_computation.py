from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Any

from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.computation.policy import ComputationContext
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.schemas import ExperimentOutcome, ExperimentSpec
from mathproofmesh.store import ArtifactStore


def _spec(
    method: str,
    target: str,
    arguments: dict[str, Any],
    domains: dict[str, Any],
    max_cases: int,
) -> ExperimentSpec:
    return ExperimentSpec(
        purpose="falsify_claim",
        target_claim=target,
        assumptions=["Variables use exactly the declared finite domains."],
        reasoning_basis=(
            "A structural argument produced this precise intermediate claim, and one bounded refutation can discard the route."
        ),
        why_computation_is_needed=(
            "Writing every substitution in the reasoning transcript would be repetitive and error-prone."
        ),
        decision_if_confirmed="Continue reasoning without treating the finite check as proof.",
        decision_if_refuted="Discard the false intermediate claim.",
        noncomputational_alternative="Seek a direct invariant or algebraic identity.",
        method=method,
        arguments=arguments,
        domains=domains,
        max_cases=max_cases,
    )


def run_benchmark(run_root: str | Path | None = None) -> dict[str, Any]:
    temporary: tempfile.TemporaryDirectory[str] | None = None
    if run_root is None:
        temporary = tempfile.TemporaryDirectory(prefix="mathproofmesh_benchmark_")
        run_root = Path(temporary.name)
    config = build_demo_config(str(run_root))
    config.computation.enabled = True
    config.computation.soft_experiments_per_path = 2
    config.computation.hard_experiments_per_path = 6
    store = ArtifactStore(config.runtime.run_root, "reasoning-first-computation")
    broker = ToolBroker(config, store)

    cases = [
        (
            _spec(
                "modular_exhaustive",
                "Every residue x modulo 101 satisfies x to the 101st congruent to x.",
                {
                    "lhs": "x^101",
                    "rhs": "x",
                    "modulus": 101,
                    "finite_reduction": True,
                    "reduction_justification": "Integer polynomial values depend only on x modulo 101.",
                },
                {"x": {"min": 0, "max": 100}},
                101,
            ),
            ExperimentOutcome.CERTIFIED,
            101,
        ),
        (
            _spec(
                "bounded_integer_search",
                "For all declared integer pairs, x squared plus y squared is nonnegative.",
                {
                    "target": {
                        "lhs": "x*x+y*y",
                        "rhs": "0",
                        "relation": "ge",
                    }
                },
                {"x": {"min": -499, "max": 500}, "y": {"min": -499, "max": 500}},
                1_000_000,
            ),
            ExperimentOutcome.NOT_REFUTED,
            1_000_000,
        ),
        (
            _spec(
                "recurrence_check",
                "The Fibonacci recurrence equals n throughout the declared interval.",
                {
                    "initial_values": [0, 1],
                    "coefficients": [1, 1],
                    "start_n": 0,
                    "end_n": 500,
                    "claimed_expression": "n",
                },
                {},
                501,
            ),
            ExperimentOutcome.COUNTEREXAMPLE_FOUND,
            501,
        ),
    ]

    outcomes: list[dict[str, Any]] = []
    structured_chars = 0
    legacy_reasoning_token_estimate = 0
    for index, (spec, expected, declared_cases) in enumerate(cases):
        context = ComputationContext(
            path_id=f"benchmark-{index}", remaining_llm_calls=10
        )
        decision = broker.decide(spec, context)
        result = broker.run_experiment(spec, decision)
        correct = result.outcome == expected
        outcomes.append(
            {
                "method": spec.method.value,
                "expected": expected.value,
                "actual": result.outcome.value,
                "correct": correct,
                "cases_checked": result.cases_checked,
            }
        )
        structured_chars += len(spec.model_dump_json()) + len(result.model_dump_json())
        # Conservative proxy: a prose trace needs at least one short substitution
        # line per declared case. The experiment protocol sends one bounded request.
        legacy_reasoning_token_estimate += declared_cases * 8

    structured_token_estimate = max(1, structured_chars // 4)
    reduction = 1.0 - structured_token_estimate / legacy_reasoning_token_estimate
    report = {
        "benchmark": "reasoning_first_computation_offline_proxy",
        "correctness_rate": sum(item["correct"] for item in outcomes) / len(outcomes),
        "legacy_reasoning_token_estimate": legacy_reasoning_token_estimate,
        "structured_token_estimate": structured_token_estimate,
        "estimated_token_reduction": reduction,
        "outcomes": outcomes,
        "note": (
            "This offline gate compares auditable request/result size with explicit per-case prose enumeration; it does not claim provider-side hidden-token measurements."
        ),
    }
    if temporary is not None:
        temporary.cleanup()
    return report


if __name__ == "__main__":
    print(json.dumps(run_benchmark(), ensure_ascii=False, indent=2))
