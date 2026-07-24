from __future__ import annotations

from mathproofmesh.continuation import make_genesis_checkpoint
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    Difficulty,
    ProblemContract,
    ProblemKind,
    StrategyCard,
    TriageResult,
)


def _problem() -> ProblemContract:
    return ProblemContract(
        exact_statement="Prove the sequence is eventually periodic.",
        normalized_statement="Prove the sequence is eventually periodic.",
    )


def _strategy() -> StrategyCard:
    return StrategyCard(
        title="GCD stabilization",
        core_idea="Stabilize a gcd invariant before deriving a recurrence.",
        independence_basis="Arithmetic invariant",
        expected_lemmas=["The gcd stabilizes", "Exclude d=1"],
        bottleneck="Prove d is greater than one.",
        falsification_test="Search for a valid d=1 construction.",
        estimated_success=0.6,
    )


def test_strategy_feedback_is_explicitly_open_and_not_premise_eligible() -> None:
    bundle = PromptFactory().strategies(
        _problem(),
        TriageResult(
            problem_kind=ProblemKind.PROOF,
            difficulty=Difficulty.HARD,
            rationale="The target needs several structural lemmas.",
            confidence=0.8,
        ),
        2,
        regulator_feedback=["严格排除 d=1 并导出 a_{n+1}=a_n+d 当 n 充分大。"],
    )

    assert "NON-AUTHORITATIVE REGULATOR DIRECTIVES" in bundle.user
    assert '"status": "open"' in bundle.user
    assert '"premise_eligible": false' in bundle.user
    assert "means that X/Y/Z still requires proof" in bundle.user


def test_path_feedback_cannot_extend_a_verified_checkpoint() -> None:
    problem = _problem()
    strategy = _strategy()
    checkpoint = make_genesis_checkpoint(problem, strategy)
    bundle = PromptFactory().continue_proof(
        problem,
        strategy.model_dump(mode="json"),
        checkpoint,
        agent_id="explorer-a",
        round_index=1,
        segment_index=1,
        verified_claims=[],
        targeted_feedback=["严格排除 d=1。"],
    )

    assert "NON-AUTHORITATIVE TARGETED REVIEW DIRECTIVES" in bundle.user
    assert '"status": "open_or_rejected"' in bundle.user
    assert '"premise_eligible": false' in bundle.user
    assert "d=1 remains OPEN" in bundle.user


def test_feedback_source_tag_is_machine_readable() -> None:
    tagged = ProofMeshOrchestrator._feedback_directive(
        "  严格排除   d=1。 ",
        kind="required_action",
        status="open",
        source="meta_review:3",
    )

    assert tagged == (
        "[required_action][STATUS:open][SOURCE:meta_review:3]"
        "[PREMISE_ELIGIBLE:false] 严格排除 d=1。"
    )


def test_continuation_preserves_exact_tagged_feedback_authority() -> None:
    problem = _problem()
    strategy = _strategy()
    checkpoint = make_genesis_checkpoint(problem, strategy)
    bundle = PromptFactory().continue_proof(
        problem,
        strategy.model_dump(mode="json"),
        checkpoint,
        agent_id="explorer-a",
        round_index=2,
        segment_index=1,
        verified_claims=[],
        targeted_feedback=[
            "[required_action][STATUS:open][SOURCE:meta_review:3]"
            "[PREMISE_ELIGIBLE:false] 严格排除 d=1。"
        ],
    )

    assert '"source": "meta_review:3"' in bundle.user
    assert '"kind": "required_action"' in bundle.user
    assert '"status": "open"' in bundle.user
    assert '"text": "严格排除 d=1。"' in bundle.user
