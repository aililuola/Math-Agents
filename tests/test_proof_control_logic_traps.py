from __future__ import annotations

from benchmarks.proof_control.run_mock_benchmark import (
    CASE_FILES,
    run_mock_benchmark,
)


def test_all_proof_control_logic_traps_pass_offline_contracts() -> None:
    result = run_mock_benchmark()

    assert result["provider_calls"] == 0
    assert result["case_count"] == len(CASE_FILES) == 10
    assert result["contract_count"] == 14
    assert all(result["component_contracts"].values())


def test_off_shadow_active_gate_semantics_are_distinct() -> None:
    variants = {item["variant"]: item for item in run_mock_benchmark()["variants"]}

    assert variants["proof_control_off"]["runtime_blocks"] == 0
    assert variants["proof_control_shadow"]["runtime_blocks"] == 0
    assert variants["proof_control_shadow"]["continue_gate"] == "shadow_block"
    assert variants["proof_control_active"]["route_admission"] == "rewrite"
    assert variants["proof_control_active"]["continue_gate"] == "block"
    assert variants["proof_control_active"]["synthesis_readiness"] == "block"
    assert variants["proof_control_active"]["runtime_blocks"] == 3
