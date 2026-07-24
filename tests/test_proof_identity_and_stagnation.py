from __future__ import annotations

import httpx
import pytest
from pydantic import ValidationError

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.deep_exploration import ExplorationSignature
from mathproofmesh.deep_exploration import (
    DeepExplorationRegistry,
    ExplorationEvidence,
    ExplorationOutcome,
)
from mathproofmesh.llm.pool import (
    ProviderCircuitBreaker,
    ProviderCircuitOpenError,
)
from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.proof_identity import (
    attempt_content_fingerprint,
    canonical_obligation_statement,
)
from mathproofmesh.schemas import (
    AttemptStatus,
    BlindVerificationReport,
    EvidenceStrength,
    ExperimentOutcome,
    FailureLevel,
    ObligationKind,
    ProofAttempt,
    ProofObligation,
    ProofStep,
    RouteStatus,
    StrategyCard,
    VerificationVerdict,
)
from mathproofmesh.store import ArtifactStore

from v07_helpers import PROBLEM_HASH, make_v07_config


def _strategy(identifier: str = "strategy-a") -> StrategyCard:
    return StrategyCard(
        strategy_id=identifier,
        title="Descent",
        core_idea="Use a strict descent.",
        independence_basis="Minimal counterexample.",
        expected_lemmas=["The descent is strict."],
        bottleneck="Every nonminimal solution has a smaller solution.",
        falsification_test="Search the first 100 boundary cases.",
        estimated_success=0.6,
    )


def _attempt(identifier: str, *, round_index: int = 0) -> ProofAttempt:
    return ProofAttempt(
        attempt_id=identifier,
        problem_hash=PROBLEM_HASH,
        strategy_id="strategy-a",
        agent_id=f"agent-{round_index}",
        round_index=round_index,
        status=AttemptStatus.PARTIAL,
        proof_steps=[
            ProofStep(
                step_id=f"step-{round_index}",
                statement="Assume a minimal counterexample exists.",
                justification="Well-ordering.",
            )
        ],
        unresolved_gaps=[
            (
                "[proof_obligation][STATUS:open][SOURCE:attempt:attempt-old]"
                "[PREMISE_ELIGIBLE:false] Unresolved gap: prove strict descent"
            )
        ],
    )


def test_feedback_wrappers_and_attempt_ids_do_not_change_proof_identity() -> None:
    nested = (
        "[proof_obligation][STATUS:open][SOURCE:attempt:a]"
        "[PREMISE_ELIGIBLE:false] Unresolved gap: "
        "[proof_obligation][STATUS:open][SOURCE:attempt:b]"
        "[PREMISE_ELIGIBLE:false] Unresolved gap: prove strict descent"
    )
    assert canonical_obligation_statement(nested) == "prove strict descent"
    assert attempt_content_fingerprint(_attempt("a", round_index=0)) == (
        attempt_content_fingerprint(_attempt("b", round_index=1))
    )


def test_obligation_store_collapses_nested_provenance_duplicates(tmp_path) -> None:
    graph = ProofGraphStore(
        make_v07_config(tmp_path / "runs"), problem_hash=PROBLEM_HASH
    )
    first = graph.add_obligation(
        ProofObligation(
            obligation_id="old-a",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.SUBGOAL,
            statement="Unresolved gap: prove strict descent",
            normalized_statement="Unresolved gap: prove strict descent",
        )
    )
    second = graph.add_obligation(
        ProofObligation(
            obligation_id="old-b",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.SUBGOAL,
            statement=(
                "[proof_obligation][STATUS:open][SOURCE:attempt:new]"
                "[PREMISE_ELIGIBLE:false] Unresolved gap: prove strict descent"
            ),
            normalized_statement=(
                "[proof_obligation][STATUS:open][SOURCE:attempt:new]"
                "[PREMISE_ELIGIBLE:false] Unresolved gap: prove strict descent"
            ),
        )
    )

    assert first.obligation_id == second.obligation_id
    assert len(graph.obligations) == 1
    assert graph.get_obligation("old-b").route_ids == ["route-a"]


def test_exploration_signature_ignores_checkpoint_and_obligation_ids() -> None:
    common = {
        "problem_hash": PROBLEM_HASH,
        "verified_checkpoint_hash": "same-mathematical-state",
        "target_statement": "Unresolved gap: prove strict descent",
        "mechanism_tags": ["descent"],
        "assumptions": ["n is positive"],
    }
    first = ExplorationSignature(
        **common,
        verified_checkpoint_id="checkpoint-a",
        target_obligation_id="obligation-a",
        route_id="route-a",
    )
    second = ExplorationSignature(
        **common,
        verified_checkpoint_id="checkpoint-b",
        target_obligation_id="obligation-b",
        route_id="route-b",
    )
    assert first.signature_hash == second.signature_hash


def test_low_tier_partial_also_gets_only_one_bounded_repair(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    registry = DeepExplorationRegistry(
        config.deep_exploration_policy, problem_hash=PROBLEM_HASH
    )
    signature = ExplorationSignature(
        problem_hash=PROBLEM_HASH,
        verified_checkpoint_id="checkpoint-a",
        verified_checkpoint_hash="same-mathematical-state",
        target_statement="prove strict descent",
        mechanism_tags=["descent"],
    )
    evidence = ExplorationEvidence(
        has_verified_checkpoint=True,
        explicit_critical_target=True,
        meta_approved=True,
        final_reserve_available=True,
        novelty_review_passed=True,
    )
    normal = registry.admit(
        signature,
        route_id="route-a",
        round_index=1,
        evidence=evidence,
        requested_tier=0,
    )
    assert normal.lease_id is not None
    registry.finish(normal.lease_id, ExplorationOutcome.USABLE_PARTIAL)
    repair = registry.admit(
        signature,
        route_id="route-a",
        round_index=2,
        evidence=evidence,
        requested_tier=0,
    )
    assert repair.allowed and repair.recovery_only
    assert repair.lease_id is not None
    registry.finish(repair.lease_id, ExplorationOutcome.NO_VERIFIED_PROGRESS)
    exhausted = registry.admit(
        signature,
        route_id="route-a",
        round_index=3,
        evidence=evidence,
        requested_tier=0,
    )
    assert not exhausted.allowed


def test_goal_integrity_failure_cannot_pass_schema() -> None:
    with pytest.raises(ValidationError, match="hard gate"):
        BlindVerificationReport(
            problem_integrity_ok=False,
            verdict=VerificationVerdict.PASS,
            issues=[],
            failure_level=FailureLevel.NONE,
            confidence=0.99,
            concise_feedback="Looks plausible.",
        )


def test_duplicate_attempt_and_global_plateau_freeze_route(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    orchestrator = ProofMeshOrchestrator(config)
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    route = registry.register_route(_strategy())
    store = ArtifactStore(tmp_path / "runs", "stagnation")
    state = SolveState(
        triage=None,
        strategies=[_strategy()],
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
    )
    assert len(orchestrator._record_attempts(state, [_attempt("a")], store)) == 1
    assert not orchestrator._record_attempts(
        state, [_attempt("b", round_index=1)], store
    )
    assert route.status == RouteStatus.REPAIR_ONCE

    state.last_progress_signature = orchestrator._global_progress_signature(state)
    assert not orchestrator._apply_global_progress_gate(
        state, round_index=1, store=store
    )
    assert not orchestrator._apply_global_progress_gate(
        state, round_index=2, store=store
    )
    assert state.global_meta_pivot_used
    assert orchestrator._apply_global_progress_gate(state, round_index=3, store=store)
    assert route.status == RouteStatus.FROZEN_STALLED


def test_confirmed_counterexample_refutes_required_critical_claim(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    orchestrator = ProofMeshOrchestrator(config)
    strategy = _strategy()
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    route = registry.register_route(strategy)
    store = ArtifactStore(tmp_path / "runs", "counterexample")
    state = SolveState(
        triage=None,
        strategies=[strategy],
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
    )
    orchestrator._apply_confirmed_counterexample_impact(
        state,
        strategy,
        route_id=route.route_id,
        impact=FailureLevel.PLAN,
        experiment_results=[
            {
                "experiment_id": "experiment-a",
                "target_claim": strategy.critical_claims[0].statement,
                "outcome": ExperimentOutcome.COUNTEREXAMPLE_FOUND.value,
                "evidence_strength": EvidenceStrength.COUNTEREXAMPLE.value,
                "independently_verified": True,
                "result_hash": "counterexample-hash",
            }
        ],
        current_round=1,
        store=store,
    )
    assert strategy.critical_claims[0].status == "refuted"
    assert route.status == RouteStatus.REFUTED
    assert state.certified_counterexample_hashes == ["counterexample-hash"]


def _http_error(status: int) -> httpx.HTTPStatusError:
    request = httpx.Request("POST", "https://provider.invalid/chat")
    response = httpx.Response(status, request=request)
    return httpx.HTTPStatusError("provider error", request=request, response=response)


def test_provider_http_circuit_distinguishes_terminal_auth_and_rate_limit(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    terminal = ProviderCircuitBreaker(config)
    with pytest.raises(ProviderCircuitOpenError):
        terminal.record_http_failure("provider", "key-a", 402, _http_error(402))
    restored = ProviderCircuitBreaker(config)
    restored.restore_state(terminal.export_state())
    with pytest.raises(ProviderCircuitOpenError):
        restored.assert_available("provider")

    shared_auth = ProviderCircuitBreaker(config)
    shared_auth.record_http_failure("provider", "key-a", 401, _http_error(401))
    with pytest.raises(ProviderCircuitOpenError):
        shared_auth.record_http_failure("provider", "key-b", 403, _http_error(403))

    rate_limit = ProviderCircuitBreaker(config)
    rate_limit.record_http_failure("provider", "key-a", 429, _http_error(429))
    rate_limit.assert_available("provider")
