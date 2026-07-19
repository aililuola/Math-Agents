from __future__ import annotations

from mathproofmesh.llm.pool import AgentPool


def test_agent_selection_sequence_is_reproducible(demo_config) -> None:
    pool_a = AgentPool(demo_config)
    pool_b = AgentPool(demo_config)
    seq_a = [pool_a.select("explorer").id for _ in range(8)]
    seq_b = [pool_b.select("explorer").id for _ in range(8)]
    assert seq_a == seq_b
