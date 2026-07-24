from __future__ import annotations

from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
)

from v07_helpers import make_broker_runtime, make_message, make_v07_config


def test_bounded_computation_cannot_close_fact_gate(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    _, _, memory, _, broker = make_broker_runtime(config, tmp_path)
    bounded = make_message(
        message_id="bounded",
        route_id="route-a",
        agent_id="author-a",
        message_type=MessageType.COMPUTATION_CERTIFICATE,
        evidence_type=EvidenceType.BOUNDED_EXPERIMENT,
        memory_tier=MemoryTier.FACT,
        status=ClaimStatus.VERIFIED,
        confidence=1.0,
    )
    decision = broker.publish(bounded, referee_agent_id="referee-a", current_round=1)
    assert not decision.accepted
    assert "evidence type" in (decision.rejection_reason or "")
    assert memory.facts == []


def test_formal_kernel_certificate_can_pass_fact_gate(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    _, _, memory, _, broker = make_broker_runtime(config, tmp_path)
    formal = make_message(
        message_id="formal",
        route_id="route-a",
        agent_id="author-a",
        message_type=MessageType.FORMAL_CERTIFICATE,
        evidence_type=EvidenceType.FORMAL_KERNEL_CERTIFICATE,
        memory_tier=MemoryTier.FACT,
        status=ClaimStatus.VERIFIED,
        confidence=1.0,
    )
    decision = broker.publish(formal, referee_agent_id="referee-a", current_round=1)
    assert decision.accepted
    assert [item.message_id for item in memory.facts] == ["formal"]
