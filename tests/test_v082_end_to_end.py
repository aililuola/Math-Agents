from __future__ import annotations

import pytest

from mathproofmesh.proof_control.dependencies import DependencyResolver
from mathproofmesh.proof_control.models import (
    ClaimRefereeDisposition,
    ClaimRefereeRecord,
    ClaimVerificationState,
    DependencyKind,
    DependencyRef,
    GateVerdict,
)
from mathproofmesh.schemas import ClaimCard, ClaimStatus, ProofStep

from v082_helpers import make_control_runtime, make_domain_strategy


@pytest.mark.parametrize("mode", ["shadow", "active"])
def test_blueprint_dependency_referee_pipeline(tmp_path, mode: str) -> None:
    *_runtime, control, main_goal = make_control_runtime(tmp_path, mode=mode)
    strategy = make_domain_strategy(strategy_id=f"strategy-{mode}")

    admitted, records = control.admit_routes([strategy])

    assert admitted == [strategy]
    assert records[0].verdict in {GateVerdict.PASS, GateVerdict.SHADOW_BLOCK}
    binding = next(
        item
        for item in control.state.route_target_bindings.values()
        if item.strategy_id == strategy.strategy_id
    )
    assert binding.direct_target_obligation_id != main_goal.obligation_id

    step = ProofStep(
        step_id="step-local",
        statement="The canonical decomposition satisfies the local relation.",
        justification="By the defining compatibility property.",
    )
    claim = ClaimCard(
        claim_id="claim-local",
        statement="The local relation transfers to the target relation.",
        conclusion="The local relation transfers to the target relation.",
        proof_steps=[step],
        dependencies=[step.step_id],
        dependency_refs=[
            DependencyRef(
                kind=DependencyKind.LOCAL_STEP,
                target_id=step.step_id,
                source_delta_id="delta-a",
            )
        ],
        source_attempt_id="attempt-a",
        source_delta_id="delta-a",
        source_agent_id="author-a",
        status=ClaimStatus.PROPOSED,
    )
    resolved = DependencyResolver(
        local_steps={step.step_id: step},
        structurally_verified_step_ids={step.step_id},
    ).resolve_all(claim.dependency_refs)
    assert resolved.resolved

    control.typed_memory.lemma_memory.add_many([claim])
    control.claim_lifecycle.register_claim(claim)
    control.claim_lifecycle.record_checkpoint_verification(
        claim.claim_id,
        report_ids=["local-review", "independent-review"],
        confidence=0.95,
        independent=True,
    )
    record = ClaimRefereeRecord(
        review_id="referee-review",
        referee_agent_id="referee-b",
        source_attempt_id="attempt-a",
        source_delta_id="delta-a",
        claim_id=claim.claim_id,
        disposition=ClaimRefereeDisposition.ACCEPT,
        dependencies_valid=True,
        scope_valid=True,
        quantifiers_valid=True,
        evidence_type_valid=True,
        reason="The typed local dependency and implication were accepted.",
    )
    control.claim_lifecycle.apply_referee_record(record)
    entry = control.claim_lifecycle.promote_fact_candidate(claim.claim_id)

    assert entry.state == ClaimVerificationState.FACT_CANDIDATE
    assert entry.referee_review_ids == [record.review_id]


def test_proof_control_off_keeps_legacy_path(tmp_path) -> None:
    *_runtime, control, _main_goal = make_control_runtime(tmp_path, mode="off")
    strategy = make_domain_strategy(strategy_id="strategy-off")

    admitted, _records = control.admit_routes([strategy])

    assert admitted == [strategy]
    assert control.state.strategy_blueprints == {}
    assert control.state.original_strategy_archive == {}
