from __future__ import annotations

from typing import Any

from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.computation.policy import ComputationContext
from mathproofmesh.config import SystemConfig
from mathproofmesh.proof_control.falsification import (
    classify_falsification_result,
)
from mathproofmesh.schemas import (
    ComputationDecisionStatus,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentResult,
    ExperimentSpec,
    MemoryTier,
    MessageType,
)

from v07_helpers import make_v07_config


def _active_config(tmp_path) -> SystemConfig:
    payload = make_v07_config(tmp_path).model_dump(mode="python")
    payload["computation"].update(
        {
            "enabled": True,
            "soft_experiments_per_path": 0,
            "hard_experiments_per_path": 4,
        }
    )
    payload["topology"]["proof_control"].update({"enabled": True, "mode": "active"})
    return SystemConfig.model_validate(payload)


def _spec(**updates: Any) -> ExperimentSpec:
    payload: dict[str, Any] = {
        "purpose": "falsify_claim",
        "target_claim": "Every residue x modulo 5 satisfies x squared equals x.",
        "assumptions": ["x is an integer residue modulo 5"],
        "reasoning_basis": (
            "This universal modular claim is a required bridge and a counterexample "
            "would invalidate the route."
        ),
        "why_computation_is_needed": (
            "Exact finite enumeration is less error-prone than manual substitution."
        ),
        "decision_if_confirmed": (
            "Keep the bridge pending and continue with a mathematical derivation."
        ),
        "decision_if_refuted": (
            "Reject the bridge and repair every proof step that depends on it."
        ),
        "noncomputational_alternative": (
            "Check all five residues manually and record each exact equality."
        ),
        "method": "modular_exhaustive",
        "domains": {"x": {"min": 0, "max": 4}},
        "arguments": {"lhs": "x^2", "rhs": "x", "modulus": 5},
        "exact_arithmetic": True,
        "broad_search": False,
        "max_cases": 5,
    }
    payload.update(updates)
    return ExperimentSpec.model_validate(payload)


def test_exact_targeted_falsification_bypasses_soft_meta_review_only(
    tmp_path,
) -> None:
    config = _active_config(tmp_path)
    broker = ToolBroker(config, store=_artifact_store(tmp_path))
    spec = _spec()

    decision = broker.decide(
        spec,
        ComputationContext(
            path_id="route-a",
            remaining_llm_calls=2,
            proof_control_fast_lane=True,
            target_obligation_id="obl-main",
        ),
    )

    assert decision.decision == ComputationDecisionStatus.ALLOW
    assert decision.rule_id == "fast_path.proof_control_falsification"
    assert decision.requires_meta_review is False


def test_fast_lane_requires_explicit_target_and_respects_resource_caps(
    tmp_path,
) -> None:
    config = _active_config(tmp_path)
    broker = ToolBroker(config, store=_artifact_store(tmp_path))
    spec = _spec()

    missing_target = broker.decide(
        spec,
        ComputationContext(
            path_id="route-a",
            remaining_llm_calls=2,
            proof_control_fast_lane=True,
        ),
    )
    over_runtime = broker.decide(
        spec,
        ComputationContext(
            path_id="route-b",
            remaining_llm_calls=2,
            proof_control_fast_lane=True,
            target_claim_id="claim-a",
            requested_runtime_seconds=10.1,
        ),
    )

    assert missing_target.rule_id == "budget.path_soft_limit"
    assert over_runtime.rule_id == "budget.path_soft_limit"


def test_fast_lane_result_policy_never_promotes_a_fact() -> None:
    counterexample = ExperimentResult(
        experiment_id="exp-counterexample",
        request_hash="request-a",
        target_claim="Every residue x modulo 5 is idempotent.",
        method="modular_exhaustive",
        outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
        evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
        counterexample={"x": 2},
        exact_arithmetic=True,
        cases_checked=3,
        tool_name="modular_exhaustive",
        tool_version="test",
        independently_verified=True,
    )
    bounded = ExperimentResult(
        experiment_id="exp-bounded",
        request_hash="request-b",
        target_claim="No tested residue violates the bridge.",
        method="modular_exhaustive",
        outcome=ExperimentOutcome.NOT_REFUTED,
        evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
        exact_arithmetic=True,
        cases_checked=5,
        tool_name="modular_exhaustive",
        tool_version="test",
    )

    refuted = classify_falsification_result(counterexample)
    inconclusive = classify_falsification_result(bounded)

    assert refuted.memory_tier == MemoryTier.NEGATIVE
    assert refuted.message_type == MessageType.COUNTEREXAMPLE
    assert refuted.claim_status_changed is False
    assert inconclusive.memory_tier == MemoryTier.INSIGHT
    assert inconclusive.conclusive_refutation is False
    assert MemoryTier.FACT not in {
        refuted.memory_tier,
        inconclusive.memory_tier,
    }


def _artifact_store(tmp_path):
    from mathproofmesh.store import ArtifactStore

    return ArtifactStore(tmp_path / "runs", "fast-lane")
