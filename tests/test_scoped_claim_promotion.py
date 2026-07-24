from __future__ import annotations

from mathproofmesh.activity import ActivityStream
from mathproofmesh.memory import LemmaMemory
from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.schemas import (
    AttemptStatus,
    ClaimCard,
    ClaimStatus,
    ProblemContract,
    ProofAttempt,
    RouteRole,
    StrategyCard,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)
from mathproofmesh.store import ArtifactStore

from v07_helpers import make_v07_config


def test_accepted_delta_claim_enters_fact_gate_when_whole_attempt_is_incomplete(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    store = ArtifactStore(config.runtime.run_root, "scoped-claim-promotion")
    activity = ActivityStream(store, persist=False)
    memory = LemmaMemory(store)
    problem = ProblemContract(
        exact_statement="Prove the target statement.",
        normalized_statement="Prove the target statement.",
    )
    strategy = StrategyCard(
        strategy_id="strategy-local-lemma",
        title="Build a conditional lemma first",
        core_idea="Prove a reusable conditional lemma before the main existence step.",
        independence_basis="Conditional divisibility propagation",
        expected_lemmas=["Conditional propagation lemma"],
        bottleneck="Prove the remaining existence statement.",
        falsification_test="Check the conditional lemma independently.",
        estimated_success=0.5,
    )
    claim = ClaimCard(
        claim_id="claim-local-verified",
        statement="If a_n is a power of p, then p divides every later term.",
        assumptions=["a_n is a positive power of the prime p"],
        conclusion="p divides every a_m with m > n",
        scope_limitations=["This is conditional on a_n being a prime power."],
        status=ClaimStatus.VERIFIED,
        source_delta_id="delta-local-verified",
        source_attempt_id="attempt-incomplete",
        source_agent_id="prover-a",
        verification_confidence=0.97,
    )
    attempt = ProofAttempt(
        attempt_id="attempt-incomplete",
        problem_hash=problem.integrity_hash,
        strategy_id=strategy.strategy_id,
        agent_id="prover-a",
        round_index=0,
        status=AttemptStatus.PARTIAL,
        proposed_lemmas=[claim],
        unresolved_gaps=["Prove that some term is a prime power."],
    )
    failure = VerificationReport(
        target_id=attempt.attempt_id,
        target_type="attempt",
        agent_id="final-verifier",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        issues=[
            VerificationIssue(
                phase="completeness",
                severity="error",
                step_id="remaining-existence-step",
                description="The route did not prove the remaining existence step.",
            )
        ],
        first_error_step="remaining-existence-step",
        confidence=1.0,
        concise_feedback="The full proof is incomplete.",
    )
    state = SolveState(
        triage=None,
        strategies=[strategy],
        attempts=[attempt],
        reports=[failure],
        aggregate_reports={attempt.attempt_id: failure},
        meta_reviews=[],
        checkpoints=[],
        proof_debt_history={},
        route_team_reviews={
            attempt.attempt_id: [
                {
                    "delta_id": "delta-local-verified",
                    "referee_agent_id": "referee-a",
                    "global_share_allowed": True,
                    "validation_passed": True,
                },
                {
                    "delta_id": "different-rejected-delta",
                    "referee_agent_id": "referee-b",
                    "global_share_allowed": False,
                    "validation_passed": False,
                },
            ]
        },
    )
    orchestrator = ProofMeshOrchestrator(config)
    orchestrator._initialize_hierarchical_runtime(
        state,
        problem=problem,
        store=store,
        activity=activity,
        memory=memory,
    )
    assert state.route_registry is not None
    route = state.route_registry.register_route(strategy)
    state.route_registry.assign_member(route.route_id, "prover-a", RouteRole.PROVER, 0)
    memory.add_many([claim])
    memory.mark_attempt_verified(attempt.attempt_id, failure)

    orchestrator._sync_hierarchical_artifacts(
        state,
        problem=problem,
        memory=memory,
        current_round=0,
        store=store,
    )

    assert claim.status == ClaimStatus.VERIFIED
    assert state.typed_memory is not None
    assert [fact.statement for fact in state.typed_memory.facts] == [claim.statement]
