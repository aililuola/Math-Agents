from __future__ import annotations

from types import SimpleNamespace

import pytest

from mathproofmesh.proof_control.claim_lifecycle import ClaimLifecycleController
from mathproofmesh.proof_control.models import (
    ClaimRefereeDisposition,
    ClaimRefereeRecord,
    ClaimVerificationState,
)
from mathproofmesh.schemas import BrokerDecision, ClaimCard, ClaimStatus
from mathproofmesh.teams.route_team import RouteTeam


def _claim() -> ClaimCard:
    return ClaimCard(
        claim_id="claim-a",
        statement="The auxiliary relation holds.",
        conclusion="The auxiliary relation holds.",
        status=ClaimStatus.PROPOSED,
        source_attempt_id="attempt-a",
        source_delta_id="delta-a",
        source_agent_id="author-a",
    )


def _accepted_record() -> ClaimRefereeRecord:
    return ClaimRefereeRecord(
        review_id="review-a",
        referee_agent_id="referee-b",
        source_attempt_id="attempt-a",
        source_delta_id="delta-a",
        claim_id="claim-a",
        disposition=ClaimRefereeDisposition.ACCEPT,
        dependencies_valid=True,
        scope_valid=True,
        quantifiers_valid=True,
        evidence_type_valid=True,
        reason="The claim and its local dependencies passed independent review.",
    )


def test_referee_acceptance_updates_claim_ledger() -> None:
    claim = _claim()
    controller = ClaimLifecycleController({claim.claim_id: claim})
    controller.record_checkpoint_verification(
        claim.claim_id,
        report_ids=["local-report", "independent-report"],
        confidence=0.95,
        independent=True,
    )

    entry = controller.apply_referee_record(_accepted_record())

    assert entry.state == ClaimVerificationState.REFEREE_ACCEPTED
    assert entry.referee_review_ids == ["review-a"]
    assert entry.referee_agent_ids == ["referee-b"]


def test_referee_rejection_blocks_fact_candidate() -> None:
    claim = _claim()
    controller = ClaimLifecycleController({claim.claim_id: claim})
    controller.record_checkpoint_verification(
        claim.claim_id,
        report_ids=["independent-report"],
        confidence=0.9,
        independent=True,
    )
    rejected = _accepted_record().model_copy(
        update={
            "review_id": "review-reject",
            "disposition": ClaimRefereeDisposition.REJECT,
            "reason": "A dependency does not establish the conclusion.",
        }
    )

    controller.apply_referee_record(rejected)

    with pytest.raises(ValueError, match="referee"):
        controller.promote_fact_candidate(claim.claim_id)


def test_fact_candidate_requires_recorded_referee_review() -> None:
    claim = _claim()
    controller = ClaimLifecycleController({claim.claim_id: claim})
    controller.record_checkpoint_verification(
        claim.claim_id,
        report_ids=["independent-report"],
        confidence=0.9,
        independent=True,
    )

    with pytest.raises(ValueError, match="referee"):
        controller.promote_fact_candidate(claim.claim_id)


def test_referee_record_is_exactly_once() -> None:
    claim = _claim()
    controller = ClaimLifecycleController({claim.claim_id: claim})
    controller.record_checkpoint_verification(
        claim.claim_id,
        report_ids=["independent-report"],
        confidence=0.9,
        independent=True,
    )
    record = _accepted_record()

    controller.apply_referee_record(record)
    entry = controller.apply_referee_record(record)

    assert entry.referee_review_ids == [record.review_id]


def test_delta_level_acceptance_requires_claim_mapping() -> None:
    claim = _claim()
    plan = SimpleNamespace(
        route_id="route-a",
        referee=SimpleNamespace(agent_id="referee-b"),
    )
    decision = BrokerDecision(message_id="artifact-a", accepted=True)

    records = RouteTeam._map_claim_dispositions(
        plan,
        SimpleNamespace(new_claims=[claim], proposed_lemmas=[]),
        decision,
    )

    assert records[0].disposition == ClaimRefereeDisposition.DEFER


def test_explicit_claim_acceptance_maps_to_referee_record() -> None:
    claim = _claim()
    plan = SimpleNamespace(
        route_id="route-a",
        referee=SimpleNamespace(agent_id="referee-b"),
    )
    decision = BrokerDecision(
        message_id="artifact-a",
        accepted=True,
        accepted_claim_ids=[claim.claim_id],
    )

    records = RouteTeam._map_claim_dispositions(
        plan,
        SimpleNamespace(new_claims=[claim], proposed_lemmas=[]),
        decision,
    )

    assert records[0].disposition == ClaimRefereeDisposition.ACCEPT
    assert records[0].dependencies_valid
