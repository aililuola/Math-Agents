from __future__ import annotations

import pytest

from mathproofmesh.schemas import (
    ContinuationTurn,
    ExperimentSpec,
    InitialExplorationTurn,
    ProofDelta,
)


def _spec_payload() -> dict:
    return {
        "purpose": "discover_pattern",
        "target_claim": "A finite sequence may exhibit a repeating block.",
        "reasoning_basis": "A finite check can suggest a candidate period.",
        "why_computation_is_needed": "The pattern must be checked exactly.",
        "decision_if_confirmed": "Record the observation as a conjecture only.",
        "decision_if_refuted": "Discard the candidate period.",
        "noncomputational_alternative": "Derive the period symbolically.",
        "method": "candidate_period_check",
        "arguments": {
            "values": [2, 5, 2, 5],
            "candidate_period": 2,
            "start_index": 0,
        },
    }


def _delta_payload(**overrides) -> dict:
    payload = {
        "problem_hash": "hash-1",
        "path_id": "path-1",
        "strategy_id": "strategy-1",
        "parent_checkpoint_id": "checkpoint-1",
        "agent_id": "explorer-a",
        "round_index": 1,
        "segment_index": 1,
        "new_steps": [
            {
                "step_id": "s1",
                "statement": "First step.",
                "justification": "Direct argument.",
            }
        ],
    }
    payload.update(overrides)
    return payload


def test_discover_pattern_spec_requires_explicit_broad_search() -> None:
    with pytest.raises(ValueError, match="must set broad_search=true"):
        ExperimentSpec.model_validate(_spec_payload())

    payload = _spec_payload()
    payload["broad_search"] = True
    spec = ExperimentSpec.model_validate(payload)
    assert spec.broad_search is True


def test_request_computation_turn_rejects_premature_impact() -> None:
    spec = {**_spec_payload(), "broad_search": True}
    with pytest.raises(ValueError, match="cannot classify an experiment"):
        ContinuationTurn.model_validate(
            {
                "action": "request_computation",
                "experiment_spec": spec,
                "experiment_impact": "plan",
            }
        )
    with pytest.raises(ValueError, match="cannot classify an experiment"):
        InitialExplorationTurn.model_validate(
            {
                "action": "request_computation",
                "experiment_spec": spec,
                "experiment_impact": "plan",
            }
        )


def test_request_computation_turn_does_not_discard_delta_evidence() -> None:
    spec = {**_spec_payload(), "broad_search": True}
    with pytest.raises(ValueError, match="requires only an experiment_spec"):
        ContinuationTurn.model_validate(
            {
                "action": "request_computation",
                "experiment_spec": spec,
                "delta": _delta_payload(
                    new_steps=[],
                    detected_conflicts=["The finite observation conflicts."],
                ),
            }
        )
    with pytest.raises(ValueError, match="requires only an experiment_spec"):
        ContinuationTurn.model_validate(
            {
                "action": "request_computation",
                "experiment_spec": spec,
                "delta": _delta_payload(),
            }
        )
    valid = ContinuationTurn.model_validate(
        {"action": "request_computation", "experiment_spec": spec}
    )
    assert valid.experiment_impact is None


def test_parent_rejects_unsupported_candidate_conjectures() -> None:
    supported = {
        "statement": "The finite values repeat with a candidate period.",
        "rationale": "The checked values repeat.",
        "supporting_experiment_ids": ["exp-1"],
        "scope_limitations": ["A finite prefix is not a proof."],
        "proof_obligations": ["Prove the periodicity for all indices."],
    }
    unsupported = {
        "statement": "The sequence is periodic for all indices.",
        "rationale": "This was not supported by an experiment.",
        "supporting_experiment_ids": [],
        "scope_limitations": ["No experiment supports this."],
        "proof_obligations": ["Prove the density claim."],
    }
    with pytest.raises(ValueError, match="at least one supporting experiment"):
        ProofDelta.model_validate(
            _delta_payload(candidate_conjectures=[supported, unsupported])
        )
    with pytest.raises(ValueError, match="at least one supporting experiment"):
        ContinuationTurn.model_validate(
            {
                "action": "submit_delta",
                "delta": _delta_payload(candidate_conjectures=[unsupported]),
            }
        )
