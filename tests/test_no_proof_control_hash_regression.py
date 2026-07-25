from __future__ import annotations

from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GoalRelation,
    ScopeRelation,
)
from mathproofmesh.proof_control.state import ProofControlState
from mathproofmesh.schemas import (
    ClaimCard,
    ObligationKind,
    ProofObligation,
    ProofStep,
)

from v07_helpers import PROBLEM_HASH, make_message


def test_sidecar_registration_does_not_change_math_object_hashes() -> None:
    message = make_message(
        message_id="message-hash",
        route_id="route-a",
        agent_id="agent-a",
        statement="For every n, P(n) holds.",
    )
    claim = ClaimCard(
        claim_id="claim-hash",
        statement="For every n, P(n) holds.",
        conclusion="P(n)",
    )
    obligation = ProofObligation(
        obligation_id="obligation-hash",
        problem_hash=PROBLEM_HASH,
        route_ids=["route-a"],
        kind=ObligationKind.MAIN_GOAL,
        statement="For every n, P(n) holds.",
        normalized_statement="for every n, p(n) holds.",
    )
    step = ProofStep(
        step_id="step-hash",
        statement="P(n)",
        justification="By the verified induction hypothesis.",
    )
    before = (
        message.content_hash,
        claim.content_hash,
        obligation.content_hash,
        step.checkpoint_payload(),
    )

    state = ProofControlState()
    state.goal_links["claim-hash"] = ClaimGoalLink(
        subject_id=claim.claim_id,
        subject_kind="claim",
        target_obligation_id=obligation.obligation_id,
        relation=GoalRelation.SUFFICIENT,
        scope_relation=ScopeRelation.SAME,
        implication_outline=["claim-hash", "obligation-hash"],
        alignment_confidence=0.95,
    )
    state.export_state()

    assert (
        message.content_hash,
        claim.content_hash,
        obligation.content_hash,
        step.checkpoint_payload(),
    ) == before
