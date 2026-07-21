from __future__ import annotations

from mathproofmesh.prompts import (
    BLIND_REVIEW_FORBIDDEN_TOKENS,
    PromptFactory,
    assert_blind_prompt_safe,
)
from mathproofmesh.schemas import BlindReviewPacket, ProblemContract
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
)
from mathproofmesh.memory import LemmaMemory
from mathproofmesh.synthesis_phase import build_blind_review_packet

from v07_helpers import make_broker_runtime, make_message, make_v07_config


def _packet() -> BlindReviewPacket:
    problem = ProblemContract(
        exact_statement="Prove that the displayed identity holds for every positive integer n.",
        normalized_statement="displayed identity for every positive integer n",
    )
    return BlindReviewPacket(
        problem=problem,
        final_proof_text="Step 1 proves the difference identity. Step 2 telescopes it.",
        cited_fact_packets=[
            {"statement": "the finite telescoping identity", "content_hash": "abc"}
        ],
        forbidden_claims=["a previously refuted shortcut"],
    )


def test_final_judge_prompts_contain_no_identity_ranking_or_social_metadata() -> None:
    prompts = PromptFactory()
    bundles = [
        prompts.blind_structural_review(_packet()),
        prompts.blind_detailed_review(
            _packet(),
            tool_results=[{"ok": True, "result": "exact identity"}],
            experiment_results=[{"outcome": "not_refuted"}],
        ),
    ]
    for bundle in bundles:
        assert_blind_prompt_safe(bundle)
        payload = (bundle.system + "\n" + bundle.user).casefold()
        schema = str(bundle.response_model.model_json_schema()).casefold()
        for forbidden in BLIND_REVIEW_FORBIDDEN_TOKENS:
            assert forbidden not in payload
            assert forbidden not in schema


def test_blind_detailed_prompt_receives_no_prior_assessment() -> None:
    bundle = PromptFactory().blind_detailed_review(_packet())
    assert "STRUCTURAL REPORT" not in bundle.user
    assert "assessment made outside this packet" in bundle.user


def test_blind_packet_includes_typed_fact_and_negative_evidence_without_identity(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    from mathproofmesh.schemas import FinalProof, ProofStep

    store, _, typed, _, broker = make_broker_runtime(config, tmp_path)
    legacy = LemmaMemory(store)
    fact = make_message(
        message_id="typed-fact",
        route_id="route-a",
        agent_id="author-a",
        message_type=MessageType.VERIFIED_LEMMA,
        evidence_type=EvidenceType.NATURAL_PROOF_AUDITED,
        memory_tier=MemoryTier.FACT,
        status=ClaimStatus.VERIFIED,
        confidence=0.95,
        normalization_confidence=0.95,
    )
    decision = broker.publish(
        fact,
        referee_agent_id="independent-referee",
        current_round=1,
    )
    assert decision.accepted
    negative = make_message(
        message_id="typed-negative",
        route_id="route-b",
        agent_id="hunter-b",
        statement="the shortcut fails at n=2",
        message_type=MessageType.COUNTEREXAMPLE,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        status=ClaimStatus.REJECTED,
    )
    typed.add_negative(negative)
    proof = FinalProof(
        problem_hash=_packet().problem.integrity_hash,
        answer="The identity holds.",
        proof_steps=[
            ProofStep(
                step_id="s1",
                statement="Apply the exact identity.",
                justification="By direct expansion.",
                dependencies=[],
            )
        ],
        confidence=0.9,
    )
    packet = build_blind_review_packet(
        _packet().problem,
        proof,
        legacy,
        typed_memory=typed,
        message_broker=broker,
    )
    assert packet.cited_fact_packets[0]["evidence_type"] == "natural_proof_audited"
    provenance = packet.cited_fact_packets[0]["review_provenance"]
    assert provenance["independent_referee_recorded"]
    assert provenance["reviewer_count"] == 1
    assert len(provenance["reviewer_identity_hash"]) == 64
    assert packet.negative_evidence_packets[0]["evidence_type"] == "counterexample"
    serialized = packet.model_dump_json()
    assert "author-a" not in serialized
    assert "route-a" not in serialized
    assert "independent-referee" not in serialized
