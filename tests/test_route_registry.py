from __future__ import annotations

import pytest

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.schemas import RouteRole, RouteStatus

from v07_helpers import PROBLEM_HASH, make_strategy, make_v07_config


def test_route_registry_caps_neighbors_and_restores_state(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.cross_route.max_neighbors_per_route = 1
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    for index in range(3):
        registry.register_route(make_strategy(index), route_id=f"r{index}")
    registry.set_neighbors("r0", ["r1", "r2", "r1", "r0"])
    registry.assign_member("r0", "author", RouteRole.PROVER, 0)
    assert registry.neighbors("r0") == ["r1"]

    registry.mark_cooling("r1", 2, "failed verification")
    assert registry.get("r1").status == RouteStatus.COOLING
    restored = RouteRegistry.from_state(registry.export_state(), config)
    assert restored.owns_agent("r0", "author", RouteRole.PROVER)
    assert restored.active_routes(2)[1].status == RouteStatus.ACTIVE


def test_route_merge_records_direction_without_deleting_source(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    registry.register_route(make_strategy(0), route_id="source")
    registry.register_route(make_strategy(1), route_id="target")
    registry.merge_routes("source", "target")
    assert registry.get("source").status == RouteStatus.MERGED
    assert registry.get("source").merged_into_route_id == "target"
    assert "mechanism-0" in registry.get("target").mechanism_signature


def test_stable_and_semantic_route_dedup_preserve_existing_state(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    original_strategy = make_strategy(0)
    original = registry.register_route(original_strategy)
    registry.mark_cooling(
        original.route_id,
        9,
        "shared premise was refuted",
        requires_revision=True,
    )

    same_id = registry.register_route(original_strategy)
    semantic_alias = registry.register_route(
        original_strategy.model_copy(update={"strategy_id": "semantic-alias"})
    )

    assert same_id.route_id == original.route_id
    assert semantic_alias.route_id == original.route_id
    assert registry.route_for_strategy("semantic-alias") == original
    assert original.cooldown_until_round == 9
    assert original.requires_revision is True
    assert len(registry.routes) == 1


def test_new_distinct_route_recomputes_sparse_neighbors(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    first = registry.register_route(make_strategy(0))
    second = registry.register_route(make_strategy(1))

    assert registry.neighbors(first.route_id) == [second.route_id]
    assert registry.neighbors(second.route_id) == [first.route_id]


def test_counterexample_cooled_route_requires_an_explicit_revision(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    route = registry.register_route(make_strategy(0))
    registry.mark_cooling(
        route.route_id,
        4,
        "a shared premise was refuted",
        requires_revision=True,
    )

    with pytest.raises(ValueError, match="explicit revision"):
        registry.reactivate(route.route_id, revision_summary="")

    registry.reactivate(
        route.route_id,
        revision_summary="Replace the refuted finite-state premise with a direct invariant.",
    )
    assert route.status == RouteStatus.ACTIVE
    assert route.requires_revision is False
    assert route.revision_summary is not None
