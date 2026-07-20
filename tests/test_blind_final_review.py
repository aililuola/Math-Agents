from __future__ import annotations

from mathproofmesh.prompts import (
    BLIND_REVIEW_FORBIDDEN_TOKENS,
    PromptFactory,
    assert_blind_prompt_safe,
)
from mathproofmesh.schemas import BlindReviewPacket, ProblemContract


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
