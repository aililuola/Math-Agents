from __future__ import annotations

from mathproofmesh.inspiration.surprise_budget import SurpriseBudgetExplorer
from mathproofmesh.schemas import (
    InspirationMechanism,
    InspirationProposal,
    NoveltySignature,
)

from v07_helpers import make_v07_config


def _proposal(novelty: float) -> InspirationProposal:
    return InspirationProposal(
        trigger_id="trigger",
        mechanism=InspirationMechanism.SURPRISE_EXPLORATION,
        source_agent_id="author",
        target_route_ids=[],
        statement="test a genuinely different finite-state encoding",
        rationale_summary="existing mechanisms are exhausted",
        generated_obligations=["open-goal"],
        novelty_signature=NoveltySignature(
            representation_tags=["finite_state"],
            mechanism_tags=["surprise"],
            targeted_obligation_ids=["open-goal"],
        ),
        novelty_score=novelty,
        expected_information_gain=0.8,
        estimated_cost=1,
    )


def test_surprise_budget_protects_finalization_and_path_caps(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    explorer = SurpriseBudgetExplorer(
        config.topology.inspiration,
        max_total_calls=40,
        finalization_reserve_calls=10,
    )
    allowed, _ = explorer.admit(
        _proposal(0.9),
        current_round=1,
        remaining_calls=10,
        current_path_count=2,
        max_paths=8,
    )
    assert not allowed
    allowed, reason = explorer.can_explore(
        current_round=1,
        remaining_calls=30,
        current_path_count=8,
        max_paths=8,
    )
    assert not allowed and reason == "max_paths has been reached"


def test_low_novelty_rejections_enter_cooldown(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    explorer = SurpriseBudgetExplorer(
        config.topology.inspiration,
        max_total_calls=40,
        finalization_reserve_calls=10,
    )
    for _ in range(config.topology.inspiration.max_consecutive_surprise_rejections):
        accepted, _ = explorer.admit(
            _proposal(0.1),
            current_round=2,
            remaining_calls=30,
            current_path_count=1,
            max_paths=8,
        )
        assert not accepted
    allowed, reason = explorer.can_explore(
        current_round=2,
        remaining_calls=30,
        current_path_count=1,
        max_paths=8,
    )
    assert not allowed and "cooling" in reason
