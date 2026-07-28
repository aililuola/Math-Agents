from __future__ import annotations

import pytest

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.near_miss import NearMissLedger
from mathproofmesh.schemas import (
    FailureLevel,
    ObligationKind,
    ProofObligation,
    Severity,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
)


def _report(
    *,
    phase: str,
    description: str,
    repair_hint: str | None = None,
    step_id: str | None = "step-2",
) -> VerificationReport:
    return VerificationReport(
        report_id=f"report-{phase}",
        target_id="attempt-a",
        target_type="attempt",
        agent_id="referee-a",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        first_error_step=step_id,
        issues=[
            VerificationIssue(
                phase=phase,
                severity=Severity.ERROR,
                step_id=step_id,
                description=description,
                repair_hint=repair_hint,
            )
        ],
        failure_level=FailureLevel.EXECUTION,
        confidence=0.9,
        concise_feedback=description,
    )


def _extract(ledger: NearMissLedger, report: VerificationReport):
    return ledger.extract_deterministic(
        report,
        route_id="route-a",
        source_target_id="attempt-a",
        target_obligation_id="target-a",
        abstract_idea="Reduce the target through a preserved quotient invariant.",
        concrete_candidate="Choose the minimal admissible representative.",
        preserved_properties=["the quotient invariant remains unchanged"],
        failed_constraints=[item.description for item in report.issues],
        salvageable_components=["quotient reduction"],
    )


def test_incomplete_attempt_alone_does_not_create_near_miss() -> None:
    report = _report(
        phase="finalization",
        description="The entire proof is incomplete and the final answer is empty.",
        step_id=None,
    )

    assert _extract(NearMissLedger(), report) is None


def test_checkpoint_policy_failure_is_process_diagnostic() -> None:
    ledger = NearMissLedger()
    report = _report(
        phase="checkpoint_policy",
        description="The completed_subgoal checkpoint format is invalid.",
        step_id=None,
    )

    record = _extract(ledger, report)
    diagnostic = ledger.process_diagnostic(
        report,
        route_id="route-a",
        target_obligation_id="target-a",
    )

    assert record is None
    assert diagnostic is not None
    assert diagnostic.domain == "process"
    assert diagnostic.source_report_id == report.report_id


def test_mathematical_candidate_failure_creates_near_miss() -> None:
    report = _report(
        phase="admissibility",
        description="The concrete representative violates the boundary condition.",
    )

    record = _extract(NearMissLedger(), report)

    assert record is not None
    assert record.target_obligation_id == "target-a"
    assert record.authoritative is False
    assert record.salvageable_components == [
        "the quotient invariant remains unchanged",
        "quotient reduction",
    ]


@pytest.mark.parametrize(
    ("phase", "description", "expected_module"),
    [
        (
            "admissibility",
            "The candidate violates a lower-bound degeneracy constraint.",
            "realizer_repair",
        ),
        (
            "structural_recurrence",
            "The first-occurrence step fails on a repeated feature.",
            "induction_selector",
        ),
        (
            "logical_gap",
            "A missing implication remains between the lemma and target.",
            "minimal_bridge",
        ),
        (
            "scope",
            "The proposed conclusion has a scope mismatch with the target.",
            "scope_goal_rewrite",
        ),
    ],
)
def test_near_miss_routes_to_correct_repair_module(
    phase: str,
    description: str,
    expected_module: str,
) -> None:
    record = _extract(
        NearMissLedger(),
        _report(phase=phase, description=description),
    )

    assert record is not None
    assert record.repair_module == expected_module
    assert record.suggested_repair_operators


def test_near_miss_enters_route_prompt_as_non_authoritative_hint(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    target = graph.add_obligation(
        ProofObligation(
            obligation_id="target-near-miss",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.LEMMA,
            statement="Prove the route-local bridge.",
            normalized_statement="prove the route-local bridge",
        )
    )
    control = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )
    record = control.near_misses.extract_deterministic(
        _report(
            phase="logical_gap",
            description="A missing implication remains at the route bridge.",
        ),
        route_id="route-a",
        target_obligation_id=target.obligation_id,
        abstract_idea="Use the verified reduction before the bridge.",
        concrete_candidate="Apply the candidate bridge implication.",
        preserved_properties=["the verified reduction"],
        salvageable_components=["the reduction step"],
    )
    assert record is not None
    control.state.near_misses[record.near_miss_id] = record
    control.near_misses.records[record.near_miss_id] = record

    hints = control.route_hints("route-a")

    assert hints["authority"] == "non_authoritative_control_hints"
    assert hints["near_miss_repairs"][0]["authoritative"] is False
    assert hints["near_miss_repairs"][0]["target_obligation_id"] == target.obligation_id
