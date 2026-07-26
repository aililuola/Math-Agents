from __future__ import annotations

from types import SimpleNamespace

import pytest

from mathproofmesh.llm.pool import AgentPool, AgentRuntime
from mathproofmesh.orchestrator import ProofMeshOrchestrator


def test_agent_selection_sequence_is_reproducible(demo_config) -> None:
    pool_a = AgentPool(demo_config)
    pool_b = AgentPool(demo_config)
    seq_a = [pool_a.select("explorer").id for _ in range(8)]
    seq_b = [pool_b.select("explorer").id for _ in range(8)]
    assert seq_a == seq_b


def test_strict_exclusion_never_reuses_an_excluded_agent(demo_config) -> None:
    pool = AgentPool(demo_config)

    with pytest.raises(RuntimeError, match="strict exclusion"):
        pool.select(
            "final_verifier",
            exclude={agent.id for agent in pool.agents},
            strict_exclude=True,
        )


def test_final_review_excludes_every_winning_chain_author() -> None:
    proof = SimpleNamespace(source_attempt_ids=["attempt-winning"])
    state = SimpleNamespace(
        attempts=[
            SimpleNamespace(
                attempt_id="attempt-winning",
                agent_id="explorer-a",
                failover_chain=["explorer-b"],
                path_id="path-winning",
            ),
            SimpleNamespace(
                attempt_id="attempt-other",
                agent_id="explorer-c",
                failover_chain=[],
                path_id="path-other",
            ),
        ],
        checkpoints=[
            SimpleNamespace(
                path_id="path-winning",
                source_agent_id="explorer-d",
                failover_chain=["explorer-e"],
            )
        ],
    )

    excluded = ProofMeshOrchestrator._final_review_author_ids(
        proof,
        state,
        SimpleNamespace(id="synthesizer-a"),
    )

    assert excluded == {
        "synthesizer-a",
        "explorer-a",
        "explorer-b",
        "explorer-d",
        "explorer-e",
    }


def test_stream_retry_uses_public_prefix_but_not_private_reasoning() -> None:
    error = RuntimeError("stream disconnected")
    error.mathproofmesh_partial_content = '{"answer":"partial'
    error.mathproofmesh_partial_content_sha256 = "abc123"
    messages = [{"role": "user", "content": "Solve the task."}]

    retried = AgentRuntime._messages_for_retry(messages, error)

    assert retried[0] == messages[0]
    assert "PUBLIC_OUTPUT_PREFIX" in retried[-1]["content"]
    assert '{"answer":"partial' in retried[-1]["content"]
    assert "private reasoning" not in retried[-1]["content"]
