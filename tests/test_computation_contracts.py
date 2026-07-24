from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.computation.contracts import validate_experiment_contract
from mathproofmesh.computation.policy import ComputationContext
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    ComputationContractRepair,
    ComputationContractRepairStatus,
    ComputationDecisionStatus,
    ExperimentSpec,
    ProblemContract,
)
from mathproofmesh.store import ArtifactStore


def _spec(
    method: str,
    arguments: dict,
    *,
    domains: dict | None = None,
    exact_arithmetic: bool = True,
) -> ExperimentSpec:
    return ExperimentSpec(
        target_claim="The declared bounded computation checks one precise claim.",
        reasoning_basis=(
            "A concrete intermediate conjecture has a bounded deterministic test."
        ),
        why_computation_is_needed=(
            "The check prevents a numerical premise from entering the proof unchecked."
        ),
        decision_if_confirmed=(
            "Retain the output only with its declared bounded evidence scope."
        ),
        decision_if_refuted="Reject the affected conjecture and repair its route.",
        noncomputational_alternative=(
            "Continue with an exact symbolic argument without using the experiment."
        ),
        method=method,
        arguments=arguments,
        domains=domains or {},
        exact_arithmetic=exact_arithmetic,
        max_cases=1000,
    )


@pytest.mark.parametrize(
    ("method", "arguments", "domains", "exact_arithmetic"),
    [
        ("sympy_simplify", {"expression": "x-x"}, {}, True),
        ("sympy_equivalent", {"lhs": "x+1", "rhs": "1+x"}, {}, True),
        ("polynomial_factor", {"expression": "x^2-1"}, {}, True),
        (
            "numeric_counterexample",
            {"lhs": "x^2", "rhs": "x", "ranges": {"x": [0, 2]}},
            {},
            False,
        ),
        (
            "modular_exhaustive",
            {"lhs": "x^2", "rhs": "x", "modulus": 2},
            {"x": {"min": 0, "max": 1}},
            True,
        ),
        (
            "bounded_integer_search",
            {"target": {"lhs": "x", "rhs": "x", "relation": "eq"}},
            {"x": {"min": 0, "max": 2}},
            True,
        ),
        (
            "graph_certificate",
            {
                "graph": {
                    "nodes": ["a"],
                    "edges": [],
                    "directed": False,
                },
                "property": "connected",
                "certificate": {},
            },
            {},
            True,
        ),
        (
            "recurrence_check",
            {
                "initial_values": [0, 1],
                "coefficients": [1, 1],
                "end_n": 8,
            },
            {},
            True,
        ),
        (
            "bounded_greedy_sequence",
            {
                "initial_values": [6],
                "length": 20,
                "rule": "gcd_overlap_all_prior",
                "claimed_values": list(range(6, 46, 2)),
            },
            {},
            True,
        ),
        (
            "candidate_period_check",
            {"values": [1, 2, 1, 2], "candidate_period": 2},
            {},
            True,
        ),
        (
            "exact_geometry",
            {
                "points": {"a": [0, 0], "b": [1, 1], "c": [2, 2]},
                "assertion": {
                    "kind": "collinear",
                    "points": ["a", "b", "c"],
                },
            },
            {},
            True,
        ),
        ("sandboxed_python", {"input": {"bound": 20}}, {}, True),
        ("lean_check", {"source": "example : True := by trivial"}, {}, True),
    ],
)
def test_every_registered_method_has_a_preexecution_contract(
    method: str,
    arguments: dict,
    domains: dict,
    exact_arithmetic: bool,
) -> None:
    valid = _spec(
        method,
        arguments,
        domains=domains,
        exact_arithmetic=exact_arithmetic,
    )
    invalid_payload = valid.model_dump(
        mode="json",
        exclude={"request_hash", "runtime_fingerprint"},
    )
    invalid_payload["arguments"] = {
        **invalid_payload["arguments"],
        "invented_control_field": True,
    }
    invalid_payload["request_hash"] = ""
    invalid_payload["runtime_fingerprint"] = {}
    invalid = ExperimentSpec.model_validate(invalid_payload)

    assert validate_experiment_contract(valid) == []
    assert validate_experiment_contract(invalid) == [
        "unsupported arguments: invented_control_field"
    ]


def test_batch_request_is_not_silently_reinterpreted_as_one_sequence() -> None:
    batch = _spec(
        "bounded_greedy_sequence",
        {
            "a1_values": [2, 3, 5, 6],
            "max_terms": 200,
            "description": "Generate each independent sequence and aggregate it.",
        },
    )

    issues = validate_experiment_contract(batch)

    assert "unsupported arguments: a1_values, description, max_terms" in issues
    assert "initial_values must be a non-empty list" in issues
    assert "length is required" in issues
    assert any("rule must be one of" in issue for issue in issues)


def test_sequence_discovery_and_assertion_contracts_are_distinct() -> None:
    discovery = ExperimentSpec(
        purpose="discover_pattern",
        target_claim="Generate an exact finite prefix for a scoped conjecture.",
        reasoning_basis="The deterministic prefix is useful exploratory evidence.",
        why_computation_is_needed="Manual least-candidate checks are error-prone.",
        decision_if_confirmed="Propose a separate candidate conjecture.",
        decision_if_refuted="Discard the proposed finite pattern.",
        noncomputational_alternative="Derive an invariant symbolically.",
        method="bounded_greedy_sequence",
        arguments={
            "initial_values": [6],
            "length": 12,
            "rule": "gcd_overlap_all_prior",
        },
        broad_search=True,
    )
    assertion_payload = discovery.model_dump(
        mode="json",
        exclude={"request_hash", "runtime_fingerprint", "execution_hash"},
    )
    assertion_payload.update(
        {
            "purpose": "check_derived_identity",
            "broad_search": False,
            "request_hash": "",
            "runtime_fingerprint": {},
            "execution_hash": "",
        }
    )
    assertion = ExperimentSpec.model_validate(assertion_payload)

    assert validate_experiment_contract(discovery) == []
    issues = validate_experiment_contract(assertion)
    assert any("assertion-mode" in issue for issue in issues)
    assert any("claimed_values" in issue for issue in issues)


def test_unclaimed_sequence_request_is_safely_downgraded_to_discovery(
    tmp_path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.computation.bounded_typed_probe_fast_path = True
    store = ArtifactStore(config.runtime.run_root, "normalize-discovery")
    broker = ToolBroker(config, store)
    spec = _spec(
        "bounded_greedy_sequence",
        {
            "initial_values": [6],
            "length": 12,
            "rule": "gcd_overlap_all_prior",
        },
    )

    decision = broker.decide(
        spec,
        ComputationContext(path_id="path-discovery", remaining_llm_calls=5),
    )

    assert spec.purpose.value == "discover_pattern"
    assert spec.broad_search is True
    assert decision.decision == ComputationDecisionStatus.ALLOW
    assert decision.rule_id == "fast_path.bounded_typed_probe"


def test_contract_repair_schema_cannot_carry_a_proof_delta() -> None:
    with pytest.raises(ValidationError, match="extra_forbidden"):
        ComputationContractRepair.model_validate(
            {
                "action": "abandon_as_unrepresentable",
                "repaired_spec": None,
                "reason": "No equivalent bounded request is available.",
                "semantic_equivalence": None,
                "delta": {"proof_complete": True},
            }
        )


@pytest.mark.asyncio
async def test_unrepresentable_typed_batch_can_repair_to_sandbox_without_execution(
    tmp_path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.computation.typed_tools_enabled = True
    config.computation.sandbox_image = (
        f"registry.example/mathproofmesh@sha256:{'a' * 64}"
    )
    config.computation.sandboxed_python_enabled = True
    store = ArtifactStore(config.runtime.run_root, "contract-repair")
    broker = ToolBroker(config, store)
    orchestrator = ProofMeshOrchestrator(config)
    original = ExperimentSpec(
        purpose="discover_pattern",
        target_claim=(
            "Across the declared initial values, count recurring prime-support sets."
        ),
        reasoning_basis=(
            "The route has reduced its next choice to a bounded multi-seed experiment."
        ),
        why_computation_is_needed=(
            "Generating and aggregating all declared prefixes is error-prone by hand."
        ),
        decision_if_confirmed=(
            "Use the table only to choose the next conjecture, never as an infinity proof."
        ),
        decision_if_refuted="Discard the proposed prime-support heuristic.",
        noncomputational_alternative=(
            "Search for a symbolic invariant for prime-support recurrence."
        ),
        method="bounded_greedy_sequence",
        arguments={
            "a1_values": [2, 3, 5, 6],
            "max_terms": 200,
            "description": "Generate independent prefixes and aggregate supports.",
        },
        broad_search=True,
        max_cases=2000,
    )
    problem = ProblemContract(
        exact_statement="Study the declared greedy sequences.",
        normalized_statement="Study the declared greedy sequences.",
        allowed_tools=["bounded_greedy_sequence", "sandboxed_python"],
    )
    context = ComputationContext(
        path_id="path-batch",
        stalled_rounds=1,
        meta_review_approved=True,
        remaining_llm_calls=10,
    )
    original.requested_by = "explorer-a"
    original.path_id = context.path_id
    original_decision = broker.decide(original, context)

    repaired_payload = original.model_dump(
        mode="json",
        exclude={"request_hash", "runtime_fingerprint"},
    )
    repaired_payload.update(
        {
            "method": "sandboxed_python",
            "arguments": {
                "input": {
                    "a1_values": [2, 3, 5, 6],
                    "max_terms": 200,
                }
            },
            "domains": {},
            "typed_tool_gap": (
                "The typed sequence tool accepts one prefix and has no batch map "
                "or prime-support aggregation contract."
            ),
            "runtime_fingerprint": {},
            "request_hash": "",
        }
    )
    repaired_spec = ExperimentSpec.model_validate(repaired_payload)
    repair = ComputationContractRepair(
        action="retry_with_repaired_spec",
        repaired_spec=repaired_spec,
        reason=(
            "Preserve the bounded batch and aggregation as one isolated custom check."
        ),
        semantic_equivalence=(
            "The same four initial values and 200-term bound are evaluated and aggregated."
        ),
    )
    repair_agent = SimpleNamespace(
        id="planner",
        config=SimpleNamespace(roles=["planner"], max_output_tokens=8192),
    )
    runner = SimpleNamespace(
        ledger=SimpleNamespace(remaining_calls=10),
        pool=SimpleNamespace(select=lambda *args, **kwargs: repair_agent),
    )
    fake_result = SimpleNamespace(
        value=repair,
        agent=repair_agent,
        raw_ref="artifact://raw/contract-repair.json",
    )
    monkeypatch.setattr(
        orchestrator,
        "_safe_call",
        AsyncMock(return_value=fake_result),
    )
    author = SimpleNamespace(
        id="explorer-a",
        config=SimpleNamespace(roles=["explorer"], max_output_tokens=96000),
    )

    decision, compiled = await orchestrator._repair_computation_contract(
        problem,
        original,
        original_decision,
        author,
        context=context,
        runner=runner,
        prompts=PromptFactory(computation_enabled=True),
        tools=broker,
        budget_bucket="breadth",
    )

    assert decision.decision == ComputationDecisionStatus.ALLOW
    assert decision.contract_repair_status == ComputationContractRepairStatus.SUCCEEDED
    assert compiled is not None
    assert compiled.method.value == "sandboxed_python"
    assert compiled.arguments["input"]["a1_values"] == [2, 3, 5, 6]
    assert compiled.arguments["input"]["max_terms"] == 200
    assert compiled.experiment_id == original.experiment_id
    assert broker.ledger.count_for_path("path-batch") == 0
    assert (
        store.root / "experiments" / original.request_hash / "contract_repair.json"
    ).exists()
    assert (
        store.root / "experiments" / compiled.request_hash / "contract_repair.json"
    ).exists()
