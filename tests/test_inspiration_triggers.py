from __future__ import annotations

from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot, TriggerPolicy
from mathproofmesh.schemas import InspirationMechanism, InspirationTriggerType

from v07_helpers import make_v07_config


def test_verified_progress_suppresses_stagnation_but_not_shared_bottleneck(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    policy = TriggerPolicy(config.topology.inspiration)
    snapshot = InspirationSnapshot(
        round_index=3,
        active_route_ids=["a", "b"],
        stagnation_rounds_by_route={"a": 3, "b": 3},
        verified_fact_gain_recent=1,
        shared_bottleneck_ids=["shared"],
    )
    triggers = policy.detect(snapshot)
    assert InspirationTriggerType.STAGNATION not in {
        item.trigger_type for item in triggers
    }
    assert InspirationTriggerType.SHARED_BOTTLENECK in {
        item.trigger_type for item in triggers
    }
    tasks = policy.select_tasks(triggers, snapshot)
    assert tasks[0].mechanism == InspirationMechanism.REVERSE_GOAL_ANALYSIS


def test_all_failed_and_final_repair_failure_choose_mechanism_changes(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    policy = TriggerPolicy(config.topology.inspiration)
    snapshot = InspirationSnapshot(
        round_index=4,
        active_route_ids=["a"],
        failed_route_ids=["a"],
        final_repair_failed=True,
        remaining_calls=20,
        current_path_count=1,
        max_paths=8,
    )
    triggers = policy.detect(snapshot)
    kinds = {item.trigger_type for item in triggers}
    assert InspirationTriggerType.ALL_ROUTES_FAILED in kinds
    assert InspirationTriggerType.FINAL_REPAIR_FAILED in kinds
    mechanisms = {item.mechanism for item in policy.select_tasks(triggers, snapshot)}
    assert InspirationMechanism.REPRESENTATION_SWITCH in mechanisms
    assert InspirationMechanism.SURPRISE_EXPLORATION in mechanisms
