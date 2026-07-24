from __future__ import annotations

from mathproofmesh.agents import CallLedger
from mathproofmesh.budget import SoftBudgetAllocator
from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.inspiration_phase import admit_inspiration_tasks
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.memory import TypedMemory
from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    InspirationMechanism,
    InspirationProposal,
    InspirationReview,
    InspirationTask,
    NoveltySignature,
    ProblemContract,
)

from v07_helpers import make_strategy, make_v07_config


def _engine(tmp_path, *, require_referee: bool = True, max_routes: int = 1):
    config = make_v07_config(tmp_path / "runs")
    config.topology.inspiration.require_inspiration_referee = require_referee
    config.topology.inspiration.max_new_routes_per_trigger = max_routes
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )
    graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    registry.register_route(make_strategy(0), route_id="route-a")
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=graph,
        typed_memory=TypedMemory(None, config),
        route_registry=registry,
        project_root=tmp_path,
    )
    snapshot = InspirationSnapshot(
        round_index=2,
        active_route_ids=["route-a"],
        remaining_calls=40,
        current_path_count=1,
        max_paths=8,
    )
    return config, engine, registry, snapshot


def _proposal(identifier: str) -> InspirationProposal:
    return InspirationProposal(
        proposal_id=identifier,
        trigger_id="shared-trigger",
        mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        source_agent_id=f"author-{identifier}",
        statement=f"Use representation {identifier}.",
        rationale_summary="A distinct reversible representation may expose the gap.",
        target_route_ids=["route-a"],
        generated_obligations=[f"Prove representation {identifier} is sound."],
        novelty_signature=NoveltySignature(
            representation_tags=[identifier],
            mechanism_tags=["representation_switch"],
        ),
        novelty_score=0.9,
        expected_information_gain=0.8,
        estimated_cost=1,
    )


def _review(proposal: InspirationProposal) -> InspirationReview:
    return InspirationReview(
        proposal_id=proposal.proposal_id,
        reviewer_agent_id=f"referee-{proposal.proposal_id}",
        semantically_distinct=True,
        relevant_to_open_obligation=True,
        internally_coherent=True,
        recommendation="create_new_route",
        confidence=0.9,
    )


def test_referee_switch_fails_closed_and_per_trigger_route_cap_is_enforced(
    tmp_path,
) -> None:
    _, engine, registry, snapshot = _engine(tmp_path, max_routes=1)
    first = _proposal("first")
    missing_review = _review(first)
    engine.proposals[first.proposal_id] = first
    decision = engine.materialize([missing_review], snapshot)[0]
    assert decision.action == "stored_insight"
    assert len(registry.routes) == 1

    _, engine, registry, snapshot = _engine(tmp_path / "capped", max_routes=1)
    first, second = _proposal("first"), _proposal("second")
    reviews = [_review(first), _review(second)]
    engine.proposals = {item.proposal_id: item for item in (first, second)}
    engine.reviews = {item.proposal_id: item for item in reviews}
    decisions = engine.materialize(reviews, snapshot)
    assert [item.action for item in decisions] == ["route_created", "stored_insight"]
    assert len(registry.routes) == 2

    _, engine, registry, snapshot = _engine(
        tmp_path / "referee-disabled",
        require_referee=False,
        max_routes=1,
    )
    local_only = _proposal("local-only")
    local_review = _review(local_only)
    engine.proposals[local_only.proposal_id] = local_only
    decision = engine.materialize([local_review], snapshot)[0]
    assert decision.action == "route_created"
    assert len(registry.routes) == 2


def test_inspiration_tasks_are_admitted_before_calls_and_respect_budget(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    pool = AgentPool(config, mock_responders=demo_responders(config))
    ledger = CallLedger(config, pool)
    allocator = SoftBudgetAllocator(config, ledger)
    tasks = [
        InspirationTask(
            task_id="task-a",
            trigger_id="trigger-a",
            mechanism=InspirationMechanism.STRUCTURAL_ANALOGY,
            reason="test an admitted structural analogy",
        )
    ]
    admitted = admit_inspiration_tasks(
        tasks,
        allocator,
        current_path_count=1,
        has_candidate=False,
        task_call_breakdowns={
            "task-a": allocator.inspiration_call_breakdown(
                proposer_calls=1,
                review_candidates=1,
            )
        },
    )
    assert [item.task_id for item in admitted.admitted_tasks] == ["task-a"]
    assert admitted.decision.candidates[0].estimated_calls == 6

    ledger.calls_started = config.budget.max_total_calls
    blocked = admit_inspiration_tasks(
        tasks,
        allocator,
        current_path_count=1,
        has_candidate=False,
    )
    assert blocked.admitted_tasks == []
    assert "task-a" in blocked.rejected
