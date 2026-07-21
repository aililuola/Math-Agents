from __future__ import annotations

from pathlib import Path

from mathproofmesh.computation.handlers.sequence import (
    run_bounded_greedy_sequence,
    run_candidate_period_check,
)
from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import (
    ComputationMethod,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentSpec,
    StrategyCard,
    ToolRequest,
)
from mathproofmesh.store import ArtifactStore


def _spec(method: ComputationMethod, arguments: dict) -> ExperimentSpec:
    return ExperimentSpec(
        target_claim="The declared finite sequence behavior is correct.",
        reasoning_basis="An abstract route produced one precise finite prediction.",
        why_computation_is_needed="Directly replay the deterministic finite claim.",
        decision_if_confirmed="Retain it only as bounded evidence.",
        decision_if_refuted="Reject the numerical premise.",
        noncomputational_alternative="Continue a symbolic derivation without this hint.",
        method=method,
        arguments=arguments,
        max_cases=1000,
    )


def test_bounded_greedy_sequence_never_promotes_a_finite_prefix_to_proof() -> None:
    evidence = run_bounded_greedy_sequence(
        _spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            {
                "initial_values": [0],
                "length": 4,
                "candidate_min": 0,
                "candidate_max": 20,
                "rule": "avoid_forbidden_differences",
                "forbidden_differences": [1],
            },
        )
    )

    assert evidence.outcome == ExperimentOutcome.NOT_REFUTED
    assert evidence.evidence_strength == EvidenceStrength.BOUNDED_EVIDENCE
    assert evidence.certificate["values"] == [0, 2, 4, 6]


def test_typed_sequence_tools_independently_recheck_counterexamples() -> None:
    greedy = run_bounded_greedy_sequence(
        _spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            {
                "initial_values": [0],
                "length": 4,
                "candidate_min": 0,
                "candidate_max": 20,
                "rule": "avoid_forbidden_differences",
                "forbidden_differences": [1],
                "claimed_values": [0, 2, 5, 7],
            },
        )
    )
    period = run_candidate_period_check(
        _spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            {"values": [1, 2, 1, 2, 1, 3], "candidate_period": 2},
        )
    )

    assert greedy.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert greedy.independently_verified is True
    assert period.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
    assert period.independently_verified is True
    assert period.counterexample["index"] == 5


def test_matching_candidate_period_is_only_bounded_not_refuted() -> None:
    evidence = run_candidate_period_check(
        _spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            {"values": [1, 2, 1, 2, 1, 2], "candidate_period": 2},
        )
    )
    assert evidence.outcome == ExperimentOutcome.NOT_REFUTED
    assert evidence.evidence_strength == EvidenceStrength.BOUNDED_EVIDENCE


def test_planner_numerical_language_becomes_an_inert_computation_hint(
    tmp_path: Path,
) -> None:
    orchestrator = ProofMeshOrchestrator(build_demo_config(str(tmp_path / "runs")))
    strategy = StrategyCard(
        strategy_id="period-route",
        title="Period check",
        core_idea="First derive a recurrence, then test a period on a finite prefix.",
        independence_basis="The recurrence is the primary symbolic mechanism.",
        bottleneck="Prove the candidate period for all indices.",
        falsification_test="Check whether period 3 fails on the computed prefix.",
        estimated_success=0.4,
    )

    enriched = orchestrator._attach_planner_computation_hints([strategy])

    assert len(enriched[0].computation_hints) == 1
    hint = enriched[0].computation_hints[0]
    assert hint.suggested_method == ComputationMethod.CANDIDATE_PERIOD_CHECK
    assert hint.broad_search is False


def test_reviewer_can_execute_the_new_typed_sequence_requests(tmp_path: Path) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.computation.typed_tools_enabled = True
    store = ArtifactStore(config.runtime.run_root, "sequence-reviewer")
    broker = ToolBroker(config, store)

    results = broker.execute_many(
        [
            ToolRequest(
                kind="bounded_greedy_sequence",
                purpose="Check the claimed deterministic finite prefix.",
                arguments={
                    "initial_values": [0],
                    "length": 4,
                    "candidate_min": 0,
                    "candidate_max": 20,
                    "rule": "avoid_forbidden_differences",
                    "forbidden_differences": [1],
                    "claimed_values": [0, 2, 5, 7],
                },
            ),
            ToolRequest(
                kind="candidate_period_check",
                purpose="Check whether the declared finite list has period two.",
                arguments={
                    "values": [1, 2, 1, 2, 1, 3],
                    "candidate_period": 2,
                },
            ),
        ]
    )

    assert all(result.ok for result in results)
    assert all(
        result.result["outcome"] == ExperimentOutcome.COUNTEREXAMPLE_FOUND.value
        for result in results
    )
    assert all(result.result["independently_verified"] is True for result in results)


def test_new_sequence_tools_are_advertised_to_reviewers_when_typed_tools_are_on(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.computation.typed_tools_enabled = True
    orchestrator = ProofMeshOrchestrator(config)

    assert "bounded_greedy_sequence" in orchestrator._allowed_tools()
    assert "candidate_period_check" in orchestrator._allowed_tools()
