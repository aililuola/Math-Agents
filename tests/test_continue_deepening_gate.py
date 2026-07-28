from __future__ import annotations

from mathproofmesh.config import ContinueGateControlConfig
from mathproofmesh.proof_control.gates import ContinueDeepeningGate
from mathproofmesh.proof_control.models import GateVerdict


def test_two_same_error_segments_without_core_progress_block_active_deepening() -> None:
    gate = ContinueDeepeningGate(
        ContinueGateControlConfig(
            mode="active",
            no_core_progress_segments=2,
        )
    )

    first = gate.evaluate(
        route_id="route-a",
        segment_index=1,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=False,
        first_error_fingerprint="same-error",
    )
    second = gate.evaluate(
        route_id="route-a",
        segment_index=2,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=False,
        first_error_fingerprint="same-error",
    )

    assert first.verdict == GateVerdict.PASS
    assert second.verdict == GateVerdict.BLOCK
    assert second.consecutive_no_core_progress == 2
    assert ContinueDeepeningGate.blocks_deepening(second)


def test_changed_first_error_or_verified_bridge_resets_stagnation() -> None:
    gate = ContinueDeepeningGate(
        ContinueGateControlConfig(mode="active", no_core_progress_segments=2)
    )
    gate.evaluate(
        route_id="route-a",
        segment_index=1,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=False,
        first_error_fingerprint="error-a",
    )
    changed = gate.evaluate(
        route_id="route-a",
        segment_index=2,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=False,
        first_error_fingerprint="error-b",
    )
    bridge = gate.evaluate(
        route_id="route-a",
        segment_index=3,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=True,
        first_error_fingerprint="error-b",
    )

    assert changed.verdict == GateVerdict.PASS
    assert changed.consecutive_no_core_progress == 0
    assert bridge.verdict == GateVerdict.PASS
    assert bridge.consecutive_no_core_progress == 0


def test_shadow_continue_gate_reports_shadow_block_only() -> None:
    gate = ContinueDeepeningGate(
        ContinueGateControlConfig(mode="shadow", no_core_progress_segments=1)
    )
    record = gate.evaluate(
        route_id="route-shadow",
        segment_index=1,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=False,
        first_error_changed=False,
    )

    assert record.verdict == GateVerdict.SHADOW_BLOCK
    assert ContinueDeepeningGate.blocks_deepening(record) is False
