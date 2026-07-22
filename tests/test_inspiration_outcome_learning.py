from __future__ import annotations

from mathproofmesh.inspiration.outcomes import InspirationOutcomeLedger
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.schemas import (
    InspirationContextMode,
    InspirationMechanism,
    InspirationProposal,
    InspirationTrigger,
    InspirationTriggerType,
    NoveltySignature,
    ObligationKind,
)

from v07_helpers import make_v07_config


def _proposal(identifier: str, mechanism: InspirationMechanism) -> InspirationProposal:
    return InspirationProposal(
        proposal_id=identifier,
        task_id=f"task-{identifier}",
        trigger_id="trigger-stalled",
        mechanism=mechanism,
        source_agent_id=f"agent-{identifier}",
        target_route_ids=["route-a"],
        statement=f"proposal {identifier}",
        rationale_summary="test a structurally distinct mechanism",
        generated_obligations=["goal"],
        novelty_signature=NoveltySignature(
            mechanism_tags=[mechanism.value],
            targeted_obligation_ids=["goal"],
        ),
        novelty_score=1.0,
        expected_information_gain=0.8,
        estimated_cost=1,
        context_mode=InspirationContextMode.WARM,
    )


def test_outcome_reward_guides_selection_without_becoming_evidence(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.inspiration.adaptive_min_observations = 0
    ledger = InspirationOutcomeLedger(config.topology.inspiration)
    trigger = InspirationTrigger(
        trigger_id="trigger-stalled",
        trigger_type=InspirationTriggerType.STAGNATION,
        round_index=3,
        affected_route_ids=["route-a"],
        reason="route stalled",
    )
    snapshot = InspirationSnapshot(
        round_index=3,
        domain="proof",
        active_route_ids=["route-a"],
        remaining_calls=30,
        open_obligation_ids=["goal"],
        obligation_kinds={"goal": ObligationKind.LEMMA.value},
    )
    strong = _proposal("strong", InspirationMechanism.REPRESENTATION_SWITCH)
    weak = _proposal("weak", InspirationMechanism.AUXILIARY_CONSTRUCTION)
    for proposal in (strong, weak):
        ledger.register(
            proposal,
            snapshot=snapshot,
            trigger=trigger,
            obligation_kinds=[ObligationKind.LEMMA],
            proof_debt_before=4.0,
        )
        ledger.record_usage(proposal.proposal_id, phase="proposer", tokens=1000)
    ledger.record_materialization("strong", action="route_created", refuted=False)
    ledger.record_verified_gain(
        "strong",
        round_index=4,
        proof_debt_after=1.0,
        obligations_closed=["goal"],
    )
    ledger.record_materialization("weak", action="rejected", refuted=True)

    profiles = ledger.selection_profiles([trigger], snapshot)
    strong_key = ledger.profile_key(
        InspirationTriggerType.STAGNATION.value,
        InspirationMechanism.REPRESENTATION_SWITCH.value,
    )
    weak_key = ledger.profile_key(
        InspirationTriggerType.STAGNATION.value,
        InspirationMechanism.AUXILIARY_CONSTRUCTION.value,
    )

    assert ledger.outcomes["strong"].reward > 0
    assert ledger.outcomes["weak"].reward < 0
    assert profiles[strong_key]["ucb_score"] > profiles[weak_key]["ucb_score"]
    assert ledger.outcomes["strong"].verified_fact_gain == 1


def test_minimum_exploration_keeps_untried_mechanisms_eligible(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    ledger = InspirationOutcomeLedger(config.topology.inspiration)
    trigger = InspirationTrigger(
        trigger_id="trigger-stalled",
        trigger_type=InspirationTriggerType.STAGNATION,
        round_index=3,
        affected_route_ids=["route-a"],
        reason="route stalled",
    )
    snapshot = InspirationSnapshot(round_index=3, remaining_calls=20)
    profiles = ledger.selection_profiles([trigger], snapshot)

    assert all(bool(item["force_exploration"]) for item in profiles.values())
