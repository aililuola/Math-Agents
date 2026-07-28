from __future__ import annotations

import asyncio

from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    MetaPivotEffect,
    MetaPivotStatus,
)

from v082_helpers import make_control_runtime


def test_empty_pivot_not_marked_effective(tmp_path) -> None:
    *_runtime, control, _goal = make_control_runtime(tmp_path)
    pivot = control.request_meta_pivot(
        source_stagnation_signature="stagnation-a",
        trigger_round=2,
        requested_mechanisms=["structural_analogy", "representation_switch"],
    )

    async def execute(_pivot):
        return {
            "attempts": [
                {
                    "mechanism": "structural_analogy",
                    "effect": "empty",
                    "reason": "No structurally compatible source was available.",
                },
                {
                    "mechanism": "representation_switch",
                    "effect": "empty",
                    "reason": "No concrete representation was materialized.",
                },
            ]
        }

    result = asyncio.run(
        control.execute_pending_meta_pivot(current_round=3, executor=execute)
    )

    assert result.status != MetaPivotStatus.EXECUTED
    outcome = control.state.meta_pivot_outcomes[pivot.pivot_id]
    assert outcome.effect == MetaPivotEffect.EMPTY
    assert outcome.attempted_mechanisms == [
        "structural_analogy",
        "representation_switch",
    ]
    assert (
        control.state.control_actions[pivot.action_id].status
        == ControlActionStatus.FAILED
    )


def test_pivot_falls_through_to_next_mechanism(tmp_path) -> None:
    *_runtime, control, _goal = make_control_runtime(tmp_path)
    pivot = control.request_meta_pivot(
        source_stagnation_signature="stagnation-b",
        trigger_round=2,
        requested_mechanisms=["structural_analogy", "representation_switch"],
    )
    calls: list[str] = []

    async def execute(_pivot, mechanism):
        calls.append(mechanism)
        if mechanism == "structural_analogy":
            return {"effect": "empty", "reason": "No candidate."}
        return {
            "effect": "effective",
            "new_task_ids": ["task-representation-review"],
            "reason": "A concrete representation-review task was created.",
        }

    result = asyncio.run(
        control.execute_pending_meta_pivot(current_round=3, executor=execute)
    )

    assert calls == ["structural_analogy", "representation_switch"]
    assert result.status == MetaPivotStatus.EXECUTED
    outcome = control.state.meta_pivot_outcomes[pivot.pivot_id]
    assert outcome.effect == MetaPivotEffect.EFFECTIVE
    assert outcome.completed_mechanisms == ["representation_switch"]
    assert outcome.new_task_ids == ["task-representation-review"]


def test_pivot_effect_requires_material_state_change(tmp_path) -> None:
    *_runtime, control, _goal = make_control_runtime(tmp_path)
    pivot = control.request_meta_pivot(
        source_stagnation_signature="stagnation-c",
        trigger_round=2,
        requested_mechanisms=["meta_replan"],
    )

    async def execute(_pivot, _mechanism):
        return {
            "effect": "effective",
            "new_route_ids": [],
            "revised_strategy_ids": [],
            "new_obligation_ids": [],
            "new_task_ids": [],
            "new_fact_ids": [],
            "new_counterexample_ids": [],
            "changed_route_ids": [],
            "reason": "Only a narrative summary was produced.",
        }

    result = asyncio.run(
        control.execute_pending_meta_pivot(current_round=3, executor=execute)
    )

    assert result.status != MetaPivotStatus.EXECUTED
    assert (
        control.state.meta_pivot_outcomes[pivot.pivot_id].effect
        == MetaPivotEffect.EMPTY
    )


def test_deferred_pivot_waits_for_wake_condition(tmp_path) -> None:
    *_runtime, control, _goal = make_control_runtime(tmp_path)
    pivot = control.request_meta_pivot(
        source_stagnation_signature="stagnation-d",
        trigger_round=2,
        requested_mechanisms=["surprise_exploration"],
    )

    async def execute(_pivot, _mechanism):
        return {
            "effect": "deferred",
            "new_task_ids": ["task-pivot-wait"],
            "wake_condition_ids": ["wake-provider"],
            "reason": "The independent pivot reviewer is cooling down.",
        }

    result = asyncio.run(
        control.execute_pending_meta_pivot(current_round=3, executor=execute)
    )

    assert result.status == MetaPivotStatus.ADMITTED
    assert (
        control.state.meta_pivot_outcomes[pivot.pivot_id].effect
        == MetaPivotEffect.DEFERRED
    )
    assert control.meta_pivot_blocks_stagnation_stop()
