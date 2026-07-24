from __future__ import annotations

from mathproofmesh.memory import LemmaMemory, TypedMemory
from mathproofmesh.schemas import (
    FinalProof,
    InspirationMechanism,
    InspirationProposal,
    NoveltySignature,
    ProblemContract,
    ProofStep,
)
from mathproofmesh.store import ArtifactStore
from mathproofmesh.synthesis_phase import build_blind_review_packet


def test_rejected_inspiration_proposal_has_stable_identity_free_blind_packet(
    tmp_path,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "blind-negative-inspiration")
    legacy = LemmaMemory(store)
    typed_memory = TypedMemory(store, lemma_memory=legacy)
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )
    proposal = InspirationProposal(
        proposal_id="rejected-inspiration",
        trigger_id="trigger-stagnation",
        mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        source_agent_id="private-inspiration-author",
        target_route_ids=["private-route-a", "private-route-b"],
        statement="Encode the recurrence as a finite-state transition system.",
        rationale_summary="The representation could expose a missing transition lemma.",
        generated_obligations=["Prove that the encoding preserves the target."],
        novelty_signature=NoveltySignature(
            representation_tags=["finite_state"],
            mechanism_tags=["representation_switch"],
            targeted_obligation_ids=["obligation-transition"],
        ),
        novelty_score=0.88,
        expected_information_gain=0.74,
        estimated_cost=1,
    )
    typed_memory.add_negative(
        proposal,
        reason="independent inspiration referee rejected the proposal",
    )
    proof = FinalProof(
        problem_hash=problem.integrity_hash,
        answer="The target follows by the displayed identity.",
        proof_steps=[
            ProofStep(
                step_id="final-step",
                statement="Apply the displayed identity.",
                justification="Direct expansion proves it.",
            )
        ],
        confidence=0.9,
    )

    packet = build_blind_review_packet(
        problem,
        proof,
        legacy,
        typed_memory=typed_memory,
    )

    assert packet.negative_evidence_packets
    negative = packet.negative_evidence_packets[0]
    assert negative["item_id"] == proposal.proposal_id
    assert negative["proposal_kind"] == proposal.mechanism.value
    assert negative["statement"] == proposal.statement
    assert negative["generated_obligations"] == proposal.generated_obligations
    assert negative["evidence_type"] == "unverified_idea"
    assert negative["novelty_hash"] == proposal.novelty_signature.normalized_hash
    assert set(negative) == {
        "item_id",
        "proposal_kind",
        "statement",
        "rationale_summary",
        "generated_obligations",
        "expected_information_gain",
        "estimated_cost",
        "evidence_type",
        "novelty_score",
        "novelty_hash",
        "representation",
        "analogy",
        "construction",
        "invariant",
        "reverse_goal",
    }

    serialized = packet.model_dump_json()
    assert proposal.source_agent_id not in serialized
    for route_id in proposal.target_route_ids:
        assert route_id not in serialized
    assert proposal.statement in packet.forbidden_claims
