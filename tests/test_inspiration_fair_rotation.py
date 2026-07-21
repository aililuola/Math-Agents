from __future__ import annotations

from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot, TriggerPolicy
from mathproofmesh.schemas import InspirationMechanism

from v07_helpers import make_v07_config


def test_stagnation_rotates_through_every_enabled_inspiration_mechanism(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    policy = TriggerPolicy(config.topology.inspiration)
    history: dict[str, dict[str, int]] = {}
    selected: list[InspirationMechanism] = []

    for round_index in range(4):
        snapshot = InspirationSnapshot(
            round_index=round_index + 2,
            active_route_ids=["route-a"],
            stagnation_rounds_by_route={"route-a": round_index + 2},
            verified_fact_gain_recent=0,
            remaining_calls=40,
            current_path_count=1,
            max_paths=8,
        )
        tasks = policy.select_tasks(policy.detect(snapshot), snapshot, history)
        for task in tasks:
            selected.append(task.mechanism)
            stat = history.setdefault(
                task.mechanism.value,
                {
                    "selected_count": 0,
                    "consecutive_no_verified_gain": 0,
                    "last_selected_round": -1,
                },
            )
            stat["selected_count"] += 1
            stat["consecutive_no_verified_gain"] += 1
            stat["last_selected_round"] = snapshot.round_index

    assert set(selected) == set(InspirationMechanism)
    assert len(selected) == len(set(selected))
