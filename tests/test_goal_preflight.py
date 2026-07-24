from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from mathproofmesh.activity import ActivityStream
from mathproofmesh.goal_preflight import deterministic_goal_precheck
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    GoalClarificationDecision,
    GoalNormalizationAssessment,
    ProblemContract,
    stable_hash,
)
from mathproofmesh.store import ArtifactStore


def test_clear_goal_stays_on_zero_api_fast_path() -> None:
    result = deterministic_goal_precheck("证明存在无穷多个模4余1的素数。")

    assert result.status == "clear"
    assert result.rule_ids == []


def test_missing_congruence_modulus_requires_model_review() -> None:
    result = deterministic_goal_precheck("证明存在无穷多个与4同余的素数。")

    assert result.status == "model_review_required"
    assert result.rule_ids == ["congruence_missing_modulus"]


def test_problem_contract_freezes_canonical_goal_without_losing_original() -> None:
    original = "证明存在无穷多个与4同余的素数。"
    canonical = "证明存在无穷多个模4余1的素数。"
    problem = ProblemContract(
        exact_statement=canonical,
        normalized_statement=canonical,
        original_statement=original,
        canonical_statement=canonical,
        interpretation_source="user_confirmed",
        interpretation_confidence=0.9,
    )

    assert problem.original_statement == original
    assert problem.exact_statement == canonical
    assert problem.goal_hash == stable_hash(canonical)
    assert problem.integrity_hash == problem.goal_hash


def test_goal_normalizer_is_small_and_non_thinking(demo_config) -> None:
    precheck = deterministic_goal_precheck("证明存在无穷多个与4同余的素数。")
    bundle = PromptFactory().goal_normalization(
        "证明存在无穷多个与4同余的素数。",
        precheck,
    )

    assert bundle.max_output_tokens == 4096
    assert demo_config.runtime.stage_thinking_modes["goal_normalization"] == "disabled"


@pytest.mark.asyncio
async def test_orchestrator_clear_goal_never_calls_normalizer(
    demo_config,
    tmp_path,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "clear-goal")
    activity = ActivityStream(store, persist=False)
    orchestrator = ProofMeshOrchestrator(demo_config)
    orchestrator._safe_call = AsyncMock()  # type: ignore[method-assign]
    root = activity.start_task("run", title="run")

    problem = await orchestrator._prepare_problem_contract(
        "Prove that 1 + 1 = 2.",
        runner=SimpleNamespace(),  # type: ignore[arg-type]
        prompts=PromptFactory(),
        store=store,
        activity=activity,
        parent_task_id=root,
    )

    assert problem.interpretation_source == "original"
    assert problem.original_statement == problem.canonical_statement
    orchestrator._safe_call.assert_not_awaited()  # type: ignore[attr-defined]
    event = activity.events[-1]
    assert event.task_id == "goal-preflight"
    assert event.metrics["api_call"] is False


@pytest.mark.asyncio
async def test_orchestrator_waits_for_user_before_freezing_ambiguous_goal(
    demo_config,
    tmp_path,
) -> None:
    original = "证明存在无穷多个与4同余的素数。"
    canonical = "证明存在无穷多个模4余1的素数。"
    assessment = GoalNormalizationAssessment(
        has_ambiguity=True,
        is_well_formed=False,
        ambiguity_reasons=["同余关系缺少模数。"],
        recommended_statement=canonical,
        recommendation_confidence=0.91,
        alternative_interpretations=[
            {
                "statement": "证明存在无穷多个模4余3的素数。",
                "confidence": 0.75,
                "rationale": "另一条常见的素数同余类命题。",
            }
        ],
        changes_mathematical_meaning=True,
        clarification_question="你要证明模4余1还是模4余3？",
    )
    seen_request = None

    async def clarify(request):
        nonlocal seen_request
        seen_request = request
        return GoalClarificationDecision(
            request_id=request.request_id,
            canonical_statement=canonical,
            source="user_confirmed",
            selected_candidate_index=0,
        )

    store = ArtifactStore(tmp_path / "runs", "ambiguous-goal")
    activity = ActivityStream(store, persist=False)
    orchestrator = ProofMeshOrchestrator(
        demo_config,
        clarification_resolver=clarify,
    )
    orchestrator._safe_call = AsyncMock(  # type: ignore[method-assign]
        return_value=SimpleNamespace(
            value=assessment,
            agent=SimpleNamespace(id="ds-planner"),
            raw_ref="raw:goal-normalization",
        )
    )
    root = activity.start_task("run", title="run")

    problem = await orchestrator._prepare_problem_contract(
        original,
        runner=SimpleNamespace(),  # type: ignore[arg-type]
        prompts=PromptFactory(),
        store=store,
        activity=activity,
        parent_task_id=root,
    )

    assert seen_request is not None
    assert problem.original_statement == original
    assert problem.canonical_statement == canonical
    assert problem.interpretation_source == "user_confirmed"
    assert problem.interpretation_agent_id == "ds-planner"
    assert problem.goal_hash == stable_hash(canonical)
    assert store.has_named_json("structured", "goal_clarification_decision")
