from __future__ import annotations

import asyncio
from types import SimpleNamespace

from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.inference_risk import InferenceRiskScanner
from mathproofmesh.proof_control.models import (
    AssumptionChallengeAction,
    AssumptionChallengeProposal,
    AssumptionChallengeReview,
    ControlActionStatus,
    ControlActionType,
    CountermodelOutcome,
    CountermodelResult,
    PropertyStrength,
    RelationSignature,
    TaskStatus,
)
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH, make_broker_runtime, make_proof_control_config


def _materialized_countermodel(tmp_path):
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config,
        tmp_path / "runtime",
    )
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            statement="Establish the total property.",
            normalized_statement="establish the total property",
            priority=1.0,
            centrality=1.0,
        )
    )
    control = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )
    [risk] = InferenceRiskScanner().deterministic_risks(
        subject_id="claim-risk",
        route_id="route-a",
        premise_relation_signatures=[
            RelationSignature(
                semantic_role="property",
                property_strength=PropertyStrength.PARTIAL,
            )
        ],
        conclusion_relation_signature=RelationSignature(
            semantic_role="property",
            property_strength=PropertyStrength.UNIVERSAL,
        ),
    )
    risk.explanation = "A partial property implies the total property."
    control._register_risks([risk])
    task = control.state.countermodel_tasks[risk.countermodel_task_id]
    return config, store, registry, memory, graph, broker, control, risk, task


def test_legacy_assigned_countermodel_executes_once_after_resume(tmp_path) -> None:
    (
        config,
        store,
        registry,
        memory,
        graph,
        broker,
        control,
        risk,
        task,
    ) = _materialized_countermodel(tmp_path)
    assert task.execution_action_id is not None
    control.state.control_actions.pop(task.execution_action_id)
    task.execution_action_id = None
    restored = ProofControlLayer.from_state(
        control.export_state(),
        config=config,
        store=store,
        activity=None,
        proof_graph=graph,
        typed_memory=memory,
        message_broker=broker,
        route_registry=registry,
    )
    calls = 0

    async def execute(current_task):
        nonlocal calls
        calls += 1
        return CountermodelResult(
            result_id="countermodel-result-inconclusive",
            task_id=current_task.task_id,
            outcome=CountermodelOutcome.INCONCLUSIVE,
            challenger_agent_id="challenger-agent",
            reviewer_agent_id="reviewer-agent",
            evidence_refs=["artifact:countermodel-proposal"],
            independent_review_refs=["artifact:countermodel-review"],
            detail="No exact counterexample was independently confirmed.",
            completed_round=2,
        )

    completed = asyncio.run(
        restored.execute_pending_countermodels(
            current_round=2,
            executor=execute,
        )
    )

    restored_task = restored.state.countermodel_tasks[task.task_id]
    assert calls == 1
    assert completed == [restored_task]
    assert restored_task.status == "inconclusive"
    assert restored_task.result_id == "countermodel-result-inconclusive"
    assert restored_task.execution_action_id is not None
    executable = restored.state.executable_tasks[restored_task.executable_task_id]
    assert executable.status == TaskStatus.INCONCLUSIVE
    execution_action = restored.state.control_actions[restored_task.execution_action_id]
    assert execution_action.action_type == ControlActionType.EXECUTE_COUNTERMODEL_TASK
    assert execution_action.status == ControlActionStatus.EXECUTED
    assert restored.state.inference_risks[risk.risk_id].status == "open"
    assert graph.get_obligation("main-goal").status == "open"
    assert memory.facts == []

    asyncio.run(
        restored.execute_pending_countermodels(
            current_round=3,
            executor=execute,
        )
    )
    assert calls == 1


def test_orchestrator_runs_countermodel_and_prevents_premature_no_action_stop(
    tmp_path,
    monkeypatch,
) -> None:
    (
        config,
        store,
        registry,
        memory,
        graph,
        broker,
        control,
        risk,
        task,
    ) = _materialized_countermodel(tmp_path)
    orchestrator = ProofMeshOrchestrator(config)
    state = SolveState(
        triage=None,
        strategies=[],
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
        typed_memory=memory,
        proof_graph=graph,
        message_broker=broker,
        proof_control=control,
        current_round=2,
    )

    assert orchestrator._proof_control_work_blocks_no_action_stop(state)
    assert not orchestrator._should_stop_after_adaptive_round(
        hard_stagnation_stop=False,
        no_action_rounds=2,
        control_work_blocks_stop=True,
    )

    challenger = SimpleNamespace(id="challenger-agent", provider="provider-a")
    reviewer = SimpleNamespace(id="reviewer-agent", provider="provider-b")

    class FakePool:
        def select(self, role, **_kwargs):
            return challenger if role == "counterexample_hunter" else reviewer

    runner = SimpleNamespace(
        ledger=SimpleNamespace(remaining_calls=8),
        pool=FakePool(),
    )
    calls: list[str] = []

    async def fake_safe_call(_runner, _role, bundle, **_kwargs):
        calls.append(bundle.stage)
        if bundle.stage == "proof_control_countermodel_search":
            return SimpleNamespace(
                value=AssumptionChallengeProposal(
                    proposal_id="countermodel-proposal",
                    action=AssumptionChallengeAction.REFUTE,
                    target_statement=risk.explanation,
                    concise_argument="One exact boundary instance violates the implication.",
                    counterexample="An exact scoped counterexample.",
                ),
                agent=challenger,
                raw_ref="raw:countermodel-proposal",
            )
        return SimpleNamespace(
            value=AssumptionChallengeReview(
                proposal_id="countermodel-proposal",
                verdict="pass",
                action_supported=True,
                exact_counterexample_confirmed=True,
                independence_confirmed=True,
                concise_feedback="The exact scoped counterexample passed review.",
            ),
            agent=reviewer,
            raw_ref="raw:countermodel-review",
        )

    monkeypatch.setattr(orchestrator, "_safe_call", fake_safe_call)
    attempted, performed = asyncio.run(
        orchestrator._execute_pending_countermodels(
            state,
            problem=SimpleNamespace(),
            store=store,
            runner=runner,
            prompts=SimpleNamespace(
                challenge_countermodel_task=lambda **_kwargs: SimpleNamespace(
                    stage="proof_control_countermodel_search"
                ),
                review_countermodel_task=lambda **_kwargs: SimpleNamespace(
                    stage="proof_control_countermodel_review"
                ),
            ),
            allocator=SimpleNamespace(minimum_finish_reserve=0),
        )
    )

    assert attempted is True
    assert performed is True
    assert calls == [
        "proof_control_countermodel_search",
        "proof_control_countermodel_review",
    ]
    assert task.status == "completed"
    assert control.state.executable_tasks[task.executable_task_id].status == (
        TaskStatus.COMPLETED
    )
    assert risk.status == "refuted"
    assert graph.get_obligation("main-goal").status == "open"
    assert memory.facts == []
    assert not orchestrator._proof_control_work_blocks_no_action_stop(state)
    assert orchestrator._should_stop_after_adaptive_round(
        hard_stagnation_stop=False,
        no_action_rounds=2,
        control_work_blocks_stop=False,
    )
