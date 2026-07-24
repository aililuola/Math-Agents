from __future__ import annotations

import pytest

from scripts.directed_computation_smoke import run_smoke


@pytest.mark.asyncio
async def test_directed_computation_smoke_creates_and_completes_one_node(
    tmp_path,
) -> None:
    summary = await run_smoke(tmp_path / "runs")

    assert summary["decision_rule"] == "fast_path.bounded_typed_probe"
    assert summary["computation_node"] == "computation:smoke-greedy-prefix-12"
    assert summary["outcome"] == "not_refuted"
    assert summary["evidence_strength"] == "bounded_evidence"
    assert summary["values"] == [6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28]
    assert summary["pattern_completion_calls"] == 1
    assert len(summary["candidate_conjectures"]) == 1
    candidate = summary["candidate_conjectures"][0]
    assert "a_n = 2n + 4" in candidate["statement"]
    assert candidate["supporting_experiment_ids"] == ["smoke-greedy-prefix-12"]
    assert candidate["scope_limitations"]
    assert candidate["proof_obligations"]
    assert len(candidate["evidence_refs"]) == 3
    assert summary["claim_memory_count"] == 0
    usage = summary["attempt_usage"]
    assert usage["total_tokens"] == usage["input_tokens"] + usage["output_tokens"]
