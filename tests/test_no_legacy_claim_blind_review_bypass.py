from __future__ import annotations

from mathproofmesh.schemas import (
    ClaimCard,
    ClaimStatus,
    FinalProof,
    ProblemContract,
    ProofStep,
)
from mathproofmesh.synthesis_phase import build_blind_review_packet

from v07_helpers import make_broker_runtime, make_v07_config


MARKER = "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE"


def test_hierarchical_blind_packet_quarantines_legacy_claims(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    store, _, typed_memory, _, broker = make_broker_runtime(config, tmp_path)
    legacy_memory = typed_memory.lemma_memory
    assert legacy_memory is not None
    legacy_memory.add_many(
        [
            ClaimCard(
                claim_id="legacy-marker",
                statement=MARKER,
                conclusion=MARKER,
                status=ClaimStatus.VERIFIED,
                verification_confidence=0.99,
            )
        ]
    )
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )
    proof = FinalProof(
        problem_hash=problem.integrity_hash,
        answer="The target identity holds.",
        proof_steps=[
            ProofStep(
                step_id="f1",
                statement="Expand both sides.",
                justification="The expressions agree term by term.",
            )
        ],
        confidence=0.9,
    )

    hierarchical_packet = build_blind_review_packet(
        problem,
        proof,
        legacy_memory,
        topology_mode="hierarchical_sparse",
        typed_memory=typed_memory,
        message_broker=broker,
    )
    legacy_packet = build_blind_review_packet(
        problem,
        proof,
        legacy_memory,
        topology_mode="legacy_sparse",
        typed_memory=typed_memory,
        message_broker=broker,
    )

    assert MARKER in {claim.statement for claim in legacy_memory.verified()}
    assert not typed_memory.facts
    assert MARKER not in hierarchical_packet.model_dump_json()
    assert MARKER in legacy_packet.model_dump_json()
