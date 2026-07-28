from __future__ import annotations

from mathproofmesh.proof_control.models import (
    BroadcastDecision,
    BroadcastDecisionRecord,
    ResumeDecision,
    ResumeDecisionKind,
)
from mathproofmesh.proof_control.state import ProofControlState


def test_v081_checkpoint_migrates_with_empty_v082_sidecars() -> None:
    restored = ProofControlState.from_state(
        {
            "schema_version": "0.8.1",
            "goal_links": {},
            "events": [],
        }
    )

    assert restored.schema_version == "0.8.2"
    assert restored.strategy_blueprints == {}
    assert restored.strategy_lineage == {}
    assert restored.original_strategy_archive == {}
    assert restored.executable_tasks == {}
    assert restored.meta_pivot_outcomes == {}
    assert restored.resume_decisions == {}
    assert any(
        item["event_type"] == "checkpoint_migrated_to_v0_8_2"
        for item in restored.events
    )


def test_v082_sidecars_roundtrip_exactly_once() -> None:
    state = ProofControlState()
    resume = ResumeDecision(
        decision_id="resume-a",
        decision=ResumeDecisionKind.NO_RESUMABLE_WORK,
        state_hash="state-a",
        terminal_stagnation_signature="stall-a",
        reason="No pending or wakeable work.",
    )
    broadcast = BroadcastDecisionRecord(
        decision_id="broadcast-a",
        message_id="message-a",
        decision=BroadcastDecision.KEEP_LOCAL,
        reason="zero_expected_cross_route_utility",
    )
    state.resume_decisions[resume.decision_id] = resume
    state.broadcast_decisions[broadcast.decision_id] = broadcast

    restored = ProofControlState.from_state(state.export_state())

    assert restored.resume_decisions == {resume.decision_id: resume}
    assert restored.broadcast_decisions == {broadcast.decision_id: broadcast}


def test_legacy_self_implication_is_quarantined_not_deleted() -> None:
    restored = ProofControlState.from_state(
        {
            "schema_version": "0.8.1",
            "legacy_bridge_obligations": [
                {
                    "obligation_id": "bridge-self",
                    "source_statement": "Goal G",
                    "target_statement": "Goal G",
                }
            ],
        }
    )

    assert restored.semantic_quarantine["bridge-self"].is_self_implication
    assert not restored.semantic_quarantine["bridge-self"].eligible_for_core_debt
