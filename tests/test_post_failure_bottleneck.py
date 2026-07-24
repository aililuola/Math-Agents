from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from mathproofmesh.agents import (
    AgentFailoverExhausted,
    ReasoningBudgetExhaustedError,
    ReasoningOnlyStallError,
    StructuredAgentRunner,
)
from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.llm.base import LLMClient, LLMResponse, Message
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.memory import LemmaMemory, TypedMemory
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    InspirationTriggerType,
    PostFailureBottleneckDiagnostic,
    ProblemContract,
    ProofCheckpoint,
    ProofObligation,
    ProofStep,
    StrategyCard,
)
from mathproofmesh.stall_recovery import (
    PostFailureBottleneckExtractor,
    classify_no_artifact_failure,
)
from mathproofmesh.store import ArtifactStore
from mathproofmesh.tools import ToolBroker
from mathproofmesh.topology import SparseTopologyRouter


class _BottleneckClient(LLMClient):
    def __init__(self) -> None:
        super().__init__("bottleneck")
        self.calls = 0
        self.messages: list[Message] = []
        self.max_output_tokens = 0

    async def complete(
        self,
        messages: list[Message],
        *,
        temperature: float,
        max_output_tokens: int,
        json_mode: bool = False,
        schema_name: str | None = None,
        schema: dict[str, Any] | None = None,
    ) -> LLMResponse:
        self.calls += 1
        self.messages = messages
        self.max_output_tokens = max_output_tokens
        payload = {
            "smallest_blocked_claim": "Prove the induction transition P(k) implies P(k+1).",
            "blocked_claim_source": "checkpoint_current_goal",
            "attempted_mechanism": "Direct induction on k.",
            "why_blocked_from_public_state": (
                "The verified checkpoint states the transition as a goal but contains "
                "no lemma controlling the new term."
            ),
            "related_obligation_ids": ["obl-valid", "obl-invented"],
            "preserved_verified_step_ids": ["s1", "s-invented"],
            "preserved_fact_message_ids": ["fact-invented"],
            "alternative_mechanism_tags": [
                "reverse_goal_analysis",
                "auxiliary_construction",
            ],
            "confidence": 0.76,
            "requires_inspiration": True,
            "exact_failed_internal_step_known": False,
            "private_reasoning_recovered": False,
        }
        return LLMResponse(
            text=json.dumps(payload),
            model=self.model,
            provider="mock",
            output_tokens=250,
            raw={"finish_reason": "stop"},
        )


def _problem_strategy_checkpoint() -> tuple[
    ProblemContract, StrategyCard, ProofCheckpoint
]:
    problem = ProblemContract(
        exact_statement="Prove P(n) for every positive integer n.",
        normalized_statement="prove p(n) for every positive integer n",
    )
    strategy = StrategyCard(
        strategy_id="strategy-induction",
        title="Induction route",
        core_idea="Use induction on n.",
        independence_basis="Inductive structure.",
        expected_lemmas=["Transition lemma"],
        bottleneck="Control the new term.",
        falsification_test="Check the transition at the first boundary.",
        estimated_success=0.6,
        tags=["induction"],
    )
    checkpoint = ProofCheckpoint(
        checkpoint_id="checkpoint-public",
        problem_hash=problem.integrity_hash,
        path_id="path-induction",
        strategy_id=strategy.strategy_id,
        segment_index=1,
        verified_steps=[
            ProofStep(
                step_id="s1",
                statement="P(1) holds.",
                justification="Verified base case.",
            )
        ],
        remaining_subgoals=["Prove P(k) implies P(k+1)."],
        current_goal="Prove P(k) implies P(k+1).",
    )
    return problem, strategy, checkpoint


def test_only_explicit_no_artifact_failures_are_classified() -> None:
    assert (
        classify_no_artifact_failure(ReasoningBudgetExhaustedError("empty"))
        == "reasoning_budget_exhausted"
    )
    assert (
        classify_no_artifact_failure(ReasoningOnlyStallError("empty"))
        == "reasoning_only_stall"
    )
    wrapped = AgentFailoverExhausted(
        "explorer",
        ["a", "b"],
        [
            "a:ReasoningOnlyStallError:no content",
            "b:StructuredOutputError:no JSON",
        ],
    )
    assert classify_no_artifact_failure(wrapped) == "reasoning_only_stall"
    assert classify_no_artifact_failure(RuntimeError("ordinary failure")) is None


@pytest.mark.asyncio
async def test_extractor_uses_public_checkpoint_once_and_filters_invented_ids(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation.enabled = True
    config.continuation.post_failure_bottleneck_enabled = True
    config.continuation.post_failure_bottleneck_max_output_tokens = 12000
    config.continuation.post_failure_bottleneck_once_per_checkpoint = True
    pool = AgentPool(config)
    client = _BottleneckClient()
    pool.get("planner").config.max_output_tokens = 96000
    pool.get("planner").config.provider_max_output_tokens = 384000
    pool.get("planner").client = client
    store = ArtifactStore(tmp_path / "runs", "post-failure")
    runner = StructuredAgentRunner(config, pool, store)
    extractor = PostFailureBottleneckExtractor(config, runner, PromptFactory(), store)
    problem, strategy, checkpoint = _problem_strategy_checkpoint()
    valid_obligation = ProofObligation(
        obligation_id="obl-valid",
        problem_hash=problem.integrity_hash,
        route_ids=["route-induction"],
        kind="subgoal",
        statement=checkpoint.current_goal or "transition",
        normalized_statement="prove p(k) implies p(k+1)",
    )
    secret = "SECRET PRIVATE REASONING MUST NOT ENTER THE DIAGNOSTIC PROMPT"
    reservation_id = "artifact-recovery:test"
    assert runner.ledger.reserve_capacity(
        reservation_id,
        calls=1,
        tokens=16000,
    )

    first = await extractor.extract(
        ReasoningBudgetExhaustedError(
            secret,
            progress={
                "artifact_recovery_tokens": 16000,
                "capacity_reservation_id": reservation_id,
            },
        ),
        problem=problem,
        strategy=strategy,
        checkpoint=checkpoint,
        route_id="route-induction",
        previous_working_checkpoint=None,
        typed_public_context={
            "fact_inbox": [],
            "insight_hints": [],
            "negative_memory": [],
            "open_obligations": [valid_obligation],
        },
        has_candidate=True,
    )
    second = await extractor.extract(
        ReasoningBudgetExhaustedError("another failure"),
        problem=problem,
        strategy=strategy,
        checkpoint=checkpoint,
        route_id="route-induction",
        previous_working_checkpoint=None,
        typed_public_context={"open_obligations": [valid_obligation]},
        has_candidate=True,
    )

    assert first is not None
    assert second is not None and second.reused is True
    assert first.diagnostic.diagnostic_id == second.diagnostic.diagnostic_id
    assert first.diagnostic.related_obligation_ids == ["obl-valid"]
    assert first.diagnostic.preserved_verified_step_ids == ["s1"]
    assert first.diagnostic.preserved_fact_message_ids == []
    assert first.diagnostic.private_reasoning_recovered is False
    assert first.diagnostic.exact_failed_internal_step_known is False
    assert client.calls == 1
    assert client.max_output_tokens == 16000
    assert runner.ledger.capacity_reservations == {}
    assert secret not in client.messages[1]["content"]
    assert (
        "cannot see, recover, summarize, or continue" in client.messages[1]["content"]
    )


@pytest.mark.asyncio
async def test_segmented_route_failure_invokes_bottleneck_recovery(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation.enabled = True
    config.continuation.segments_per_explore_call = 1
    config.continuation.post_failure_bottleneck_enabled = True
    pool = AgentPool(config)
    client = _BottleneckClient()
    pool.get("planner").config.max_output_tokens = 96000
    pool.get("planner").config.provider_max_output_tokens = 384000
    pool.get("planner").client = client
    store = ArtifactStore(tmp_path / "runs", "post-failure-wired")
    runner = StructuredAgentRunner(config, pool, store)
    problem, strategy, _checkpoint = _problem_strategy_checkpoint()

    async def fail_route(*_args: Any, **_kwargs: Any) -> Any:
        raise AgentFailoverExhausted(
            "explorer",
            ["explorer-a", "explorer-b"],
            [
                "explorer-a:ReasoningBudgetExhaustedError:no artifact",
                "explorer-b:ReasoningOnlyStallError:no artifact",
            ],
        )

    monkeypatch.setattr(runner, "call_with_failover", fail_route)
    orchestrator = ProofMeshOrchestrator(config)
    attempt = await orchestrator._explore_path_segmented(
        problem,
        strategy,
        pool.get("explorer-a"),
        state=None,
        round_index=1,
        runner=runner,
        prompts=PromptFactory(),
        router=SparseTopologyRouter(config, pool, store),
        memory=LemmaMemory(store),
        store=store,
        tools=ToolBroker(config, store),
        targeted_feedback=[],
        previous_attempt=None,
        budget_bucket="depth",
        computation_meta_approved=False,
        max_segments_this_call=1,
    )

    assert client.calls == 1
    assert attempt.segment_count == 0
    assert attempt.latest_checkpoint_id is not None
    assert any(
        "Route-local post-failure bottleneck" in gap
        and "private reasoning was not recovered" in gap
        for gap in attempt.unresolved_gaps
    )
    artifacts = list(
        (Path(store.root) / "structured").glob("post_failure_bottleneck_*.json")
    )
    assert artifacts


def test_diagnostic_becomes_route_local_obligation_and_manual_inspiration_trigger(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.continuation.post_failure_trigger_inspiration = True
    config.topology.inspiration.enabled = True
    config.topology.inspiration.mode = "active"
    problem, strategy, checkpoint = _problem_strategy_checkpoint()
    store = ArtifactStore(tmp_path / "runs", "post-failure-materialize")
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    route = registry.register_route(strategy, route_id="route-induction")
    graph = ProofGraphStore(config, store, problem_hash=problem.integrity_hash)
    typed_memory = TypedMemory(store, config)
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=graph,
        typed_memory=typed_memory,
        route_registry=registry,
        store=store,
    )
    state = SolveState(
        triage=None,
        strategies=[strategy],
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[checkpoint],
        route_registry=registry,
        proof_graph=graph,
        typed_memory=typed_memory,
        inspiration_engine=engine,
        current_round=2,
    )
    payload = json.loads(
        json.dumps(
            {
                "diagnostic_id": "bottleneck-test",
                "problem_hash": problem.integrity_hash,
                "path_id": checkpoint.path_id,
                "route_id": route.route_id,
                "strategy_id": strategy.strategy_id,
                "checkpoint_id": checkpoint.checkpoint_id,
                "failure_type": "reasoning_budget_exhausted",
                "failure_fingerprint": "a" * 64,
                "smallest_blocked_claim": checkpoint.current_goal,
                "blocked_claim_source": "checkpoint_current_goal",
                "attempted_mechanism": "Direct induction.",
                "why_blocked_from_public_state": "No transition lemma is verified.",
                "alternative_mechanism_tags": ["reverse_goal_analysis"],
                "requires_inspiration": True,
            }
        )
    )
    report = PostFailureBottleneckDiagnostic.model_validate(payload)
    orchestrator = ProofMeshOrchestrator(config)
    obligation = orchestrator._materialize_post_failure_bottleneck(state, report)
    snapshot = orchestrator._inspiration_snapshot(state, remaining_calls=20)

    assert obligation is not None
    assert obligation.status == "blocked"
    assert obligation.route_ids == [route.route_id]
    assert obligation.first_error_fingerprint.startswith("post_failure:")
    assert graph.claim_nodes == []
    assert typed_memory.facts == []
    assert route.requires_revision is True
    assert route.stagnation_rounds >= config.topology.inspiration.stagnation_rounds
    assert snapshot is not None and snapshot.manual_trigger is True
    triggers = engine.detect_triggers(snapshot)
    manual = [
        item for item in triggers if item.trigger_type == InspirationTriggerType.MANUAL
    ]
    assert len(manual) == 1
    assert manual[0].affected_route_ids == [route.route_id]
    assert manual[0].evidence_refs == [obligation.obligation_id]
