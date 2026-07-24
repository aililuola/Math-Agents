from __future__ import annotations

from pathlib import Path

from mathproofmesh.computation.handlers.sequence import (
    run_bounded_greedy_sequence,
    run_candidate_period_check,
)
from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.computation.policy import ComputationContext
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    ComputationDecisionStatus,
    ComputationMethod,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentSpec,
    ProblemContract,
    StrategyCard,
    ToolRequest,
)
from mathproofmesh.store import ArtifactStore


def _spec(
    method: ComputationMethod,
    arguments: dict,
    *,
    domains: dict | None = None,
) -> ExperimentSpec:
    return ExperimentSpec(
        target_claim="The declared finite sequence behavior is correct.",
        reasoning_basis="An abstract route produced one precise finite prediction.",
        why_computation_is_needed="Directly replay the deterministic finite claim.",
        decision_if_confirmed="Retain it only as bounded evidence.",
        decision_if_refuted="Reject the numerical premise.",
        noncomputational_alternative="Continue a symbolic derivation without this hint.",
        method=method,
        domains=domains or {},
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


def test_bounded_greedy_sequence_supports_gcd_overlap_with_every_prior_term() -> None:
    evidence = run_bounded_greedy_sequence(
        _spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            {
                "initial_values": [6],
                "length": 5,
                "candidate_min": 2,
                "candidate_max": 30,
                "rule": "gcd_overlap_all_prior",
            },
        )
    )

    assert evidence.outcome == ExperimentOutcome.NOT_REFUTED
    assert evidence.certificate["values"] == [6, 8, 10, 12, 14]
    assert evidence.certificate["rule"] == "gcd_overlap_all_prior"


def test_malformed_bounded_greedy_request_is_rejected_before_execution(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.computation.typed_tools_enabled = True
    store = ArtifactStore(config.runtime.run_root, "bad-sequence-contract")
    broker = ToolBroker(config, store)
    spec = _spec(
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        {"a1": 6, "max_n": 2000},
    )

    decision = broker.decide(
        spec,
        ComputationContext(path_id="path-bad", remaining_llm_calls=5),
    )

    assert decision.decision == ComputationDecisionStatus.REJECT
    assert decision.rule_id == "request.invalid_tool_contract"
    assert "unsupported arguments: a1, max_n" in decision.reason
    assert "initial_values must be a non-empty list" in decision.reason
    assert "length is required" in decision.reason
    assert broker.ledger.count_for_path("path-bad") == 0


def test_bounded_greedy_rejects_domain_sweeps_and_control_aliases(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.computation.enabled = True
    config.computation.typed_tools_enabled = True
    store = ArtifactStore(config.runtime.run_root, "bad-sequence-sweep")
    broker = ToolBroker(config, store)
    spec = _spec(
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        {
            "check_early_dn_one": True,
            "max_terms": 1000,
            "report_dn": True,
        },
        domains={"a1_min": 2, "a1_max": 100},
    )

    decision = broker.decide(
        spec,
        ComputationContext(path_id="path-sweep", remaining_llm_calls=5),
    )

    assert decision.decision == ComputationDecisionStatus.REJECT
    assert decision.rule_id == "request.invalid_tool_contract"
    assert "does not accept domains" in decision.reason
    assert "check_early_dn_one" in decision.reason


def test_explorer_receives_exact_bounded_greedy_argument_contract() -> None:
    problem = ProblemContract(
        exact_statement="Generate a bounded prefix of the declared greedy sequence.",
        normalized_statement="generate a bounded greedy sequence prefix",
        allowed_tools=["bounded_greedy_sequence"],
    )

    bundle = PromptFactory(computation_enabled=True).explore(
        problem,
        strategy={},
        agent_id="explorer-a",
        round_index=0,
        verified_claims=[],
    )

    assert "REGISTERED TYPED COMPUTATION CONTRACTS" in bundle.user
    assert '"initial_values"' in bundle.user
    assert '"gcd_overlap_all_prior"' in bundle.user
    assert "Unknown aliases such as a1, max_n, max_terms" in bundle.user


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
