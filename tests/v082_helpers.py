from __future__ import annotations

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.schemas import ObligationKind, ProofObligation, StrategyCard

from v07_helpers import PROBLEM_HASH, make_broker_runtime, make_proof_control_config


def make_domain_strategy(
    *,
    strategy_id: str = "strategy-domain",
    expected_lemmas: list[str] | None = None,
) -> StrategyCard:
    lemmas = expected_lemmas or [
        "Every admissible object has a canonical decomposition.",
        "The canonical decomposition satisfies the target relation.",
    ]
    return StrategyCard(
        strategy_id=strategy_id,
        title="Canonical decomposition route",
        core_idea=(
            "Construct a canonical decomposition, prove its compatibility "
            "relation, and transfer that relation to the target."
        ),
        independence_basis="Uses a decomposition mechanism rather than direct search.",
        expected_lemmas=lemmas,
        bottleneck=lemmas[-1],
        prerequisites=["The input belongs to the declared ambient structure."],
        key_original_step="Choose a minimal compatible decomposition.",
        falsification_test="check x in [0, 4]: x + 0 == x",
        estimated_success=0.72,
        tags=["canonical-decomposition", "compatibility-transfer"],
    )


def make_main_goal(
    *,
    obligation_id: str = "goal-g",
    statement: str = "Every admissible object satisfies the target relation.",
) -> ProofObligation:
    return ProofObligation(
        obligation_id=obligation_id,
        problem_hash=PROBLEM_HASH,
        route_ids=[],
        kind=ObligationKind.MAIN_GOAL,
        statement=statement,
        normalized_statement=statement.casefold(),
        priority=1.0,
        centrality=1.0,
    )


def make_control_runtime(tmp_path, *, mode: str = "active"):
    config = make_proof_control_config(tmp_path / "runs", mode=mode)
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    main_goal = graph.add_obligation(make_main_goal())
    control = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )
    return config, store, registry, memory, graph, broker, control, main_goal
