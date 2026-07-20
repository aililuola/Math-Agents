from __future__ import annotations

from v07_helpers import make_broker_runtime, make_fact, make_v07_config


def test_initial_isolation_and_per_round_delivery_cap(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.cross_route.max_messages_per_route_per_round = 1
    _, _, _, _, broker = make_broker_runtime(config, tmp_path)
    for index in range(2):
        fact = make_fact(
            message_id=f"fact-{index}",
            statement=f"identity {index}",
            target_routes=["route-b"],
            ttl_rounds=4,
        )
        broker.publish(fact, referee_agent_id="referee-a", current_round=0)

    assert broker.inbox("route-b", current_round=0) == []
    first_round = broker.inbox("route-b", current_round=1)
    assert len(first_round) == 1
    assert broker.inbox("route-b", current_round=1) == []
    second_round = broker.inbox("route-b", current_round=2)
    assert len(second_round) == 1
    assert {first_round[0].message_id, second_round[0].message_id} == {
        "fact-0",
        "fact-1",
    }


def test_neighbor_cap_is_configuration_driven(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.cross_route.max_neighbors_per_route = 1
    _, registry, _, _, broker = make_broker_runtime(config, tmp_path)
    decision = broker.publish(
        make_fact(
            message_id="capped",
            target_routes=["route-b", "route-c"],
        ),
        referee_agent_id="referee-a",
        current_round=1,
    )
    assert registry.neighbors("route-a", 1) == ["route-b"]
    assert len(decision.selected_targets) == 1
    assert len(decision.rejected_targets) == 1
    assert "neighbor" in next(iter(decision.rejected_targets.values()))
