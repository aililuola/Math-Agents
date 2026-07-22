from __future__ import annotations

import pytest

from mathproofmesh.inspiration.cross_run_learning import CrossRunLearningStore
from mathproofmesh.inspiration.outcomes import InspirationOutcomeLedger
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.schemas import (
    InspirationMechanism,
    InspirationOutcome,
    InspirationTrigger,
    InspirationTriggerType,
    NegativeAnalogyRecord,
    VerifiedExperienceRecord,
)

from v07_helpers import make_v07_config


def _experience(*, cited: bool) -> VerifiedExperienceRecord:
    return VerifiedExperienceRecord(
        record_id="experience-1",
        source_proposal_id="proposal-1",
        problem_hash="a" * 64,
        problem_skeleton="integer divisibility target",
        obligation_graph_motif=["lemma:closed:deps=1"],
        obligation_kinds=["lemma"],
        mechanism_chain=["finite_state", "pigeonhole"],
        key_construction="encode residues as a finite state",
        transferable_lemmas=["a repeated state gives a valid period"],
        non_transferable_conditions=["the transition must be deterministic"],
        cited_by_final_proof=cited,
    )


def _negative() -> NegativeAnalogyRecord:
    return NegativeAnalogyRecord(
        record_id="negative-1",
        proposal_id="proposal-bad",
        problem_hash="b" * 64,
        mechanism=InspirationMechanism.STRUCTURAL_ANALOGY,
        failure_reason="the source recurrence is reversible but the target is not",
        distinguishing_conditions=["target transition loses state information"],
        round_index=3,
    )


def _outcome() -> InspirationOutcome:
    return InspirationOutcome(
        proposal_id="proposal-1",
        problem_hash="a" * 64,
        mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        trigger_type=InspirationTriggerType.STAGNATION,
        round_created=2,
        proposer_calls=1,
        review_calls=2,
        route_calls=1,
        materialized=True,
        materialization_action="route_created",
        verified_fact_gain=1,
        cited_by_final_proof=True,
        reward=5.0,
    )


def _store(tmp_path) -> CrossRunLearningStore:
    config = make_v07_config(tmp_path / "runs")
    config.topology.inspiration.cross_run_learning_enabled = True
    config.topology.inspiration.cross_run_learning_path = ".learning"
    return CrossRunLearningStore(
        config.topology.inspiration,
        project_root=tmp_path,
    )


def test_cross_run_store_admits_only_cited_verified_positive_experience(
    tmp_path,
) -> None:
    store = _store(tmp_path)

    first = store.persist(
        experiences=[_experience(cited=False)],
        negatives=[_negative()],
        outcomes=[_outcome()],
        run_verified=True,
    )

    assert first == {"experiences": 0, "negatives": 1, "outcomes": 1}
    assert store.load_experiences() == []
    assert [item.record_id for item in store.load_negatives()] == ["negative-1"]
    assert [item.proposal_id for item in store.load_outcomes()] == ["proposal-1"]

    second = store.persist(
        experiences=[_experience(cited=True)],
        negatives=[_negative()],
        outcomes=[_outcome()],
        run_verified=True,
    )

    assert second == {"experiences": 1, "negatives": 0, "outcomes": 0}
    assert [item.record_id for item in store.load_experiences()] == ["experience-1"]


def test_unverified_run_never_persists_positive_experience(tmp_path) -> None:
    store = _store(tmp_path)

    store.persist(
        experiences=[_experience(cited=True)],
        negatives=[],
        outcomes=[],
        run_verified=False,
    )

    assert store.load_experiences() == []


def test_cross_run_store_rejects_paths_outside_project(tmp_path) -> None:
    project = tmp_path / "project"
    project.mkdir()
    config = make_v07_config(project / "runs")
    config.topology.inspiration.cross_run_learning_enabled = True
    config.topology.inspiration.cross_run_learning_path = str(tmp_path / "outside")

    with pytest.raises(ValueError, match="inside project_root"):
        CrossRunLearningStore(
            config.topology.inspiration,
            project_root=project,
        )


def test_historical_outcomes_feed_adaptive_mechanism_selection(tmp_path) -> None:
    store = _store(tmp_path)
    store.persist(
        experiences=[],
        negatives=[],
        outcomes=[_outcome()],
        run_verified=True,
    )
    ledger = InspirationOutcomeLedger(store.config)
    ledger.load_historical(store.load_outcomes())
    trigger = InspirationTrigger(
        trigger_id="trigger-stagnation",
        trigger_type=InspirationTriggerType.STAGNATION,
        round_index=4,
        affected_route_ids=["route-a"],
        reason="no verified gain",
    )
    snapshot = InspirationSnapshot(
        round_index=4,
        problem_hash="c" * 64,
        domain="unknown",
        open_obligation_ids=["goal"],
        obligation_kinds={"goal": "lemma"},
        remaining_calls=20,
        current_path_count=1,
        max_paths=8,
    )

    profiles = ledger.selection_profiles([trigger], snapshot)
    key = ledger.profile_key(
        InspirationTriggerType.STAGNATION.value,
        InspirationMechanism.REPRESENTATION_SWITCH.value,
    )

    assert profiles[key]["observations"] == 1
    assert profiles[key]["mean_reward"] == 5.0
