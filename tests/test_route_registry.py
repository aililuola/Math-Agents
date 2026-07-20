from __future__ import annotations

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
