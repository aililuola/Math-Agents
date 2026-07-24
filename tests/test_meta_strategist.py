from __future__ import annotations

from mathproofmesh.inspiration.meta_strategist import PersistentMetaStrategist
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.schemas import InspirationMechanism

from v07_helpers import make_v07_config


def test_meta_strategist_explains_decision_from_observable_metrics(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    strategist = PersistentMetaStrategist(config.topology.inspiration)
    snapshot = InspirationSnapshot(
        round_index=3,
        active_route_ids=["a", "b"],
        route_redundancy=0.95,
        remaining_calls=20,
        finalization_reserve_calls=8,
    )
    decision = strategist.decide(snapshot)
    assert decision.action == "switch_representation"
    assert decision.selected_mechanism == InspirationMechanism.REPRESENTATION_SWITCH
    assert decision.observable_metrics["route_redundancy"] == 0.95
    assert decision.reason


def test_cooled_mechanism_is_not_selected_consecutively(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    strategist = PersistentMetaStrategist(config.topology.inspiration)
    strategist.cool(
        InspirationMechanism.REPRESENTATION_SWITCH, current_round=2, rounds=3
    )
    decision = strategist.decide(
        InspirationSnapshot(
            round_index=3,
            active_route_ids=["a", "b"],
            route_redundancy=0.95,
        )
    )
    assert decision.selected_mechanism != InspirationMechanism.REPRESENTATION_SWITCH
    restored = PersistentMetaStrategist.from_state(
        strategist.export_state(), config=config.topology.inspiration
    )
    assert len(restored.history) == 1
