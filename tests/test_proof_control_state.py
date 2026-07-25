from __future__ import annotations

from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GoalRelation,
    ProofRole,
    ScopeRelation,
)
from mathproofmesh.proof_control.state import ProofControlState


def _link(subject_id: str) -> ClaimGoalLink:
    return ClaimGoalLink(
        link_id=f"link-{subject_id}",
        subject_id=subject_id,
        subject_kind="claim",
        target_obligation_id="main-goal",
        relation=GoalRelation.SUFFICIENT,
        scope_relation=ScopeRelation.SAME,
        implication_outline=["claim", "main goal"],
        alignment_confidence=0.9,
    )


def test_state_round_trip_is_stable_and_sorted() -> None:
    state = ProofControlState()
    state.goal_links["z"] = _link("z")
    state.goal_links["a"] = _link("a")
    state.proof_roles["z"] = ProofRole.CORE_BRIDGE
    state.core_debt_history["route-z"] = [3.0, 2.0]

    exported = state.export_state()
    restored = ProofControlState.from_state(exported)

    assert list(exported["goal_links"]) == ["a", "z"]
    assert restored.export_state() == exported


def test_unknown_old_record_is_skipped_with_migration_event() -> None:
    restored = ProofControlState.from_state(
        {
            "schema_version": "0.7",
            "goal_links": {
                "bad": {
                    "subject_id": "claim",
                    "unknown_legacy_field": True,
                }
            },
            "proof_roles": {"bad": "future_role"},
        }
    )

    assert restored.goal_links == {}
    assert restored.proof_roles == {}
    assert len(restored.events) == 2
    assert all(
        event["event_type"] == "proof_control_migration_record_skipped"
        for event in restored.events
    )


def test_missing_payload_initializes_empty_state() -> None:
    restored = ProofControlState.from_state(None)

    assert restored.export_state()["schema_version"] == "0.8"
    assert restored.goal_links == {}
    assert restored.events == []
