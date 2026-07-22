from __future__ import annotations

from mathproofmesh.agents import CallLedger
from mathproofmesh.budget import SoftBudgetAllocator
from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.memory import TypedMemory
from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    InspirationMechanism,
    InspirationTask,
    ProblemContract,
)

from v07_helpers import make_v07_config


def _runtime(tmp_path):
    config = make_v07_config(tmp_path / "runs")
    problem = ProblemContract(
        exact_statement="Prove the target.",
        normalized_statement="prove target",
    )
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=ProofGraphStore(config, problem_hash=problem.integrity_hash),
        typed_memory=TypedMemory(None, config),
        route_registry=RouteRegistry(config, problem_hash=problem.integrity_hash),
        project_root=tmp_path,
    )
    pool = AgentPool(config, mock_responders=demo_responders(config))
    ledger = CallLedger(config, pool)
    allocator = SoftBudgetAllocator(config, ledger)
    return config, engine, ledger, allocator


def test_active_inspiration_reserves_the_complete_first_cycle(tmp_path) -> None:
    config, engine, ledger, allocator = _runtime(tmp_path)
    breakdown = allocator.inspiration_call_breakdown()
    assert breakdown == {
        "proposer_calls": 3,
        "referee_calls": 2,
        "skeptic_calls": 2,
        "route_attempt_calls": 3,
    }
    task = InspirationTask(
        task_id="surprise-task",
        trigger_id="surprise-trigger",
        mechanism=InspirationMechanism.SURPRISE_EXPLORATION,
        reason="all ordinary mechanisms are stalled",
    )
    engine.tasks[task.task_id] = task
    snapshot = InspirationSnapshot(
        round_index=3,
        remaining_calls=40,
        current_path_count=1,
        max_paths=8,
    )

    reservation, reason = engine.reserve_task_calls(
        task,
        snapshot=snapshot,
        **breakdown,
    )

    assert reason == "reserved"
    assert reservation is not None
    assert reservation.reserved_calls == 10
    assert engine.surprise_explorer.state.reserved_calls == 10

    for _ in range(7):
        ledger.start(
            "inspiration-test",
            "breadth",
            reservation_id=reservation.reservation_id,
        )
    engine.record_reserved_calls(
        task.task_id,
        ledger.reservation_calls[reservation.reservation_id],
        phase="proposal_review_pipeline",
    )
    engine.finish_task_reservation(task.task_id)

    completed = engine.call_reservations[task.task_id]
    assert completed.status == "completed"
    assert completed.consumed_calls == 7
    assert completed.released_calls == 3
    assert completed.overrun_calls == 0
    assert engine.surprise_explorer.state.used_calls == 7
    assert engine.surprise_explorer.state.reserved_calls == 0


def test_resume_reconciles_charged_calls_and_releases_orphaned_reserve(
    tmp_path,
) -> None:
    _config, engine, _ledger, allocator = _runtime(tmp_path)
    task = InspirationTask(
        task_id="resume-task",
        trigger_id="resume-trigger",
        mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        reason="resume an interrupted candidate population",
    )
    engine.tasks[task.task_id] = task
    reservation, _ = engine.reserve_task_calls(
        task,
        snapshot=InspirationSnapshot(
            round_index=2,
            remaining_calls=40,
            current_path_count=1,
            max_paths=8,
        ),
        **allocator.inspiration_call_breakdown(),
    )
    assert reservation is not None

    engine.reconcile_call_reservations({reservation.reservation_id: 4})

    restored = engine.call_reservations[task.task_id]
    assert restored.status == "interrupted"
    assert restored.consumed_calls == 4
    assert restored.released_calls == 6
