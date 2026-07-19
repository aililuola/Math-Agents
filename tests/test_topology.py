from __future__ import annotations

from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.schemas import ClaimCard, ClaimStatus, StrategyCard
from mathproofmesh.topology import SparseTopologyRouter


def _strategy(title: str, idea: str, success: float) -> StrategyCard:
    return StrategyCard(
        title=title,
        core_idea=idea,
        independence_basis=idea,
        bottleneck="b",
        falsification_test="test",
        estimated_success=success,
        estimated_cost=0.2,
    )


def test_diverse_strategy_selection_avoids_near_duplicates(
    demo_config, artifact_store
) -> None:
    pool = AgentPool(demo_config)
    router = SparseTopologyRouter(demo_config, pool, artifact_store)
    strategies = [
        _strategy("Induction A", "induction recurrence squares", 0.95),
        _strategy("Induction B", "induction recurrence square identity", 0.94),
        _strategy("Telescoping", "finite differences telescope", 0.88),
    ]
    selected = router.select_diverse_strategies(strategies, 2)
    titles = {s.title for s in selected}
    assert "Telescoping" in titles
    assert len(titles) == 2


def test_claim_transfer_is_sparse_over_source_paths(
    demo_config, artifact_store
) -> None:
    pool = AgentPool(demo_config)
    router = SparseTopologyRouter(demo_config, pool, artifact_store)
    claims = []
    for source, tag in [
        ("path-1", "algebra"),
        ("path-2", "geometry"),
        ("path-3", "number"),
    ]:
        for i in range(2):
            claims.append(
                ClaimCard(
                    statement=f"{tag} lemma {i}",
                    conclusion=f"{tag} result {i}",
                    source_attempt_id=source,
                    status=ClaimStatus.VERIFIED,
                    verification_confidence=0.9,
                    tags=[tag],
                )
            )
    selected = router.relevant_claims(
        claims,
        _strategy("mixed", "algebra geometry number", 0.8),
    )
    sources = {c.source_attempt_id for c in selected}
    assert len(sources) <= demo_config.topology.neighbor_k
    assert len(selected) <= demo_config.topology.max_verified_claims_per_context
