from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from mathproofmesh.config import (
    ContinueGateControlConfig,
    RouteAdmissionControlConfig,
    SynthesisReadinessControlConfig,
)
from mathproofmesh.proof_control.bottleneck import BottleneckCompressor
from mathproofmesh.proof_control.common_mode import CriticalAssumptionMatrix
from mathproofmesh.proof_control.gates import (
    ContinueDeepeningGate,
    RouteAdmissionGate,
    SynthesisReadinessGate,
)
from mathproofmesh.proof_control.goal_alignment import MinimalSufficiencyAnalyzer
from mathproofmesh.proof_control.induction import InductionMeasureSelector
from mathproofmesh.proof_control.inference_risk import InferenceRiskScanner
from mathproofmesh.proof_control.message_utility import MessageUtilityController
from mathproofmesh.proof_control.near_miss import NearMissLedger
from mathproofmesh.proof_control.falsification import classify_falsification_result
from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GoalRelation,
    IndexScope,
    MessageExpectedEffect,
    ObjectScope,
    RealizerFailureType,
    ScopeRelation,
    ScopeSignature,
)
from mathproofmesh.proof_control.realizer import AbstractRealizerController
from mathproofmesh.proof_control.scope_guard import ScopeGuard
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentResult,
    FailureLevel,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    ObligationKind,
    ProofObligation,
    RouteDescriptor,
    RouteRole,
    Severity,
    StrategyCard,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)


PROBLEM_HASH = "8" * 64
CASE_FILES = (
    "overstrong_target.json",
    "bounded_gap_nonperiodic.json",
    "eventual_to_global.json",
    "projection_information_loss.json",
    "image_inclusion_not_surjective.json",
    "abstract_realizer_repair.json",
    "occurrence_induction.json",
    "common_mode_assumption.json",
    "obligation_compression.json",
    "message_without_use.json",
)


def _load_cases(root: Path) -> dict[str, dict[str, Any]]:
    cases = {
        name: json.loads((root / name).read_text(encoding="utf-8"))
        for name in CASE_FILES
    }
    expected_ids = {name.removesuffix(".json") for name in CASE_FILES}
    actual_ids = {str(item.get("case_id")) for item in cases.values()}
    if actual_ids != expected_ids:
        raise ValueError("proof-control benchmark case IDs do not match filenames")
    return cases


def _strategy(index: int, *, prerequisite: str = "") -> StrategyCard:
    return StrategyCard(
        strategy_id=f"strategy-{index}",
        title=f"Route {index}",
        core_idea=f"Use independent mechanism {index}.",
        independence_basis=f"mechanism-{index}",
        expected_lemmas=[f"bridge-{index}"],
        bottleneck=f"close bridge-{index}",
        prerequisites=[prerequisite] if prerequisite else [],
        falsification_test=f"check boundary case {index}",
        estimated_success=0.5,
        estimated_cost=0.4,
        tags=[f"mechanism-{index}"],
    )


def _message(statement: str) -> MessageEnvelope:
    return MessageEnvelope(
        message_id="benchmark-message",
        problem_hash=PROBLEM_HASH,
        source_agent_id="author-a",
        source_route_id="route-a",
        source_role=RouteRole.PROVER,
        target_route_ids=["route-b"],
        message_type=MessageType.CLAIM_PROPOSAL,
        statement=statement,
        normalized_statement=" ".join(statement.casefold().split()),
        conclusion=statement,
        evidence_type=EvidenceType.UNVERIFIED_IDEA,
        memory_tier=MemoryTier.INSIGHT,
        verification_status=ClaimStatus.PROPOSED,
        verification_confidence=0.0,
        normalization_confidence=1.0,
        round_created=0,
    )


def _overstrong_contract(case: dict[str, Any]) -> bool:
    target = ProofObligation(
        obligation_id="main-goal",
        problem_hash=PROBLEM_HASH,
        route_ids=[],
        kind=ObligationKind.MAIN_GOAL,
        statement=case["goal"],
        normalized_statement=str(case["goal"]).casefold(),
    )
    strong = ClaimGoalLink(
        link_id="strong-link",
        subject_id=case["strong_target"],
        subject_kind="claim",
        target_obligation_id=target.obligation_id,
        relation=GoalRelation.SUFFICIENT,
        scope_relation=ScopeRelation.CLAIM_STRONGER,
        minimality_score=case["strong_minimality_score"],
        alignment_confidence=1.0,
    )
    minimal = ClaimGoalLink(
        link_id="minimal-link",
        subject_id=case["minimal_target"],
        subject_kind="claim",
        target_obligation_id=target.obligation_id,
        relation=GoalRelation.SUFFICIENT,
        scope_relation=ScopeRelation.SAME,
        minimality_score=case["minimal_minimality_score"],
        alignment_confidence=1.0,
    )
    analyzer = MinimalSufficiencyAnalyzer()
    preferred = analyzer.compare_targets(strong, minimal)
    admission = RouteAdmissionGate(
        RouteAdmissionControlConfig(mode="active", min_goal_alignment=0.0)
    ).evaluate(
        _strategy(0),
        goal_link=strong.model_copy(update={"subject_id": "strategy-0"}),
        target_obligations=[target],
        core_obligation_ids=[target.obligation_id],
    )
    return (
        preferred is minimal
        and analyzer.dominated_strong_targets([strong, minimal]) == [strong]
        and preferred.subject_id == case["expected_preferred_target"]
        and admission.verdict.value == case["expected_active_action"]
    )


def _bounded_gap_contract(case: dict[str, Any]) -> bool:
    thue_morse = [index.bit_count() % 2 for index in range(64)]
    gaps = [1 + item for item in thue_morse]
    sequence = [0]
    for gap in gaps:
        sequence.append(sequence[-1] + gap)
    no_short_period = all(
        any(
            thue_morse[index] != thue_morse[index + period]
            for index in range(64 - period)
        )
        for period in range(1, 17)
    )
    risks = InferenceRiskScanner().deterministic_risks(
        subject_id=case["case_id"],
        premise_texts=[case["premise"]],
        conclusion_text=case["invalid_conclusion"],
    )
    return (
        all(left < right for left, right in zip(sequence, sequence[1:]))
        and sorted(set(gaps)) == case["gap_values"]
        and no_short_period
        and case["expected_risk"] in {item.risk_type.value for item in risks}
        and all(item.status == "open" for item in risks)
    )


def _eventual_contract(case: dict[str, Any]) -> bool:
    premise = ScopeSignature(
        subject_id="eventual",
        index_scope=IndexScope(case["premise_scope"]),
        normalization_confidence=1.0,
    )
    target = ScopeSignature(
        subject_id="global",
        index_scope=IndexScope(case["target_scope"]),
        normalization_confidence=1.0,
    )
    risks = InferenceRiskScanner().deterministic_risks(
        subject_id=case["case_id"],
        premise_scopes=[premise],
        conclusion_scope=target,
    )
    return ScopeGuard().can_close_obligation(premise, target) is case[
        "may_close_target"
    ] and case["expected_risk"] in {item.risk_type.value for item in risks}


def _projection_contract(case: dict[str, Any]) -> bool:
    coordinate = int(case["projection_coordinate"])
    left = {tuple(item) for item in case["left_object"]}
    right = {tuple(item) for item in case["right_object"]}
    left_projection = {item[coordinate] for item in left}
    right_projection = {item[coordinate] for item in right}
    premise = ScopeSignature(
        subject_id="projection",
        object_scope=ObjectScope.PROJECTION,
        normalization_confidence=1.0,
    )
    target = ScopeSignature(
        subject_id="full-object",
        object_scope=ObjectScope.FULL_OBJECT,
        normalization_confidence=1.0,
    )
    risks = InferenceRiskScanner().deterministic_risks(
        subject_id=case["case_id"],
        premise_scopes=[premise],
        conclusion_scope=target,
    )
    return (
        left != right
        and left_projection == right_projection
        and ScopeGuard().can_close_obligation(premise, target)
        is case["may_close_target"]
        and case["expected_risk"] in {item.risk_type.value for item in risks}
    )


def _image_contract(case: dict[str, Any]) -> bool:
    mapping = {int(key): int(value) for key, value in case["mapping"].items()}
    image = {mapping[item] for item in case["domain"]}
    codomain = set(case["codomain"])
    risks = InferenceRiskScanner().deterministic_risks(
        subject_id=case["case_id"],
        premise_texts=[case["premise"]],
        conclusion_text=case["invalid_conclusion"],
    )
    return (
        image <= codomain
        and image != codomain
        and case["counterexample_target"] not in image
        and case["expected_risk"] in {item.risk_type.value for item in risks}
    )


def _realizer_contract(case: dict[str, Any]) -> bool:
    controller = AbstractRealizerController()
    structure = controller.extract_structure(
        route_id="route-a",
        source_subject_id=case["case_id"],
        representation_kind=case["representation_kind"],
        components=["class", "representative"],
        proposed_reduction=case["abstract_reduction"],
        removable_components=["representative"],
        preserved_constraints=["class invariant", "strict descent"],
        target_obligation_ids=["main-goal"],
    )
    first = controller.register_realizer(
        structure_id=structure.structure_id,
        route_id="route-a",
        construction=case["first_realizer"],
        admissibility_conditions=["representative belongs to the class"],
        boundary_conditions=["the lower bound is respected"],
        descent_measure="class complexity in N",
        expected_strict_decrease="complexity decreases by at least one",
        falsification_tests=["test the minimal boundary"],
    )
    controller.record_realizer_failure(
        first.candidate_id,
        RealizerFailureType.LOWER_BOUND,
        case["first_failure"],
    )
    repaired = controller.repair_realizer(
        structure_id=structure.structure_id,
        failed_candidate_id=first.candidate_id,
        repair_operator=case["repair_operator"],
        required_constraints=["respect the lower bound"],
        targeted_obligation_ids=["main-goal"],
        construction=case["second_realizer"],
        admissibility_conditions=["representative belongs to the class"],
        boundary_conditions=["choose above the lower boundary"],
        descent_measure="class complexity in N",
        expected_strict_decrease="complexity decreases by at least one",
        falsification_tests=["test the repaired boundary"],
    )
    controller.record_realizer_success(repaired.candidate.candidate_id)
    return (
        first.status == "failed"
        and repaired.candidate.structure_id == structure.structure_id
        and structure.status == case["expected_structure_status"]
    )


def _occurrence_contract(case: dict[str, Any]) -> bool:
    selector = InductionMeasureSelector()
    triggers = selector.detect_trigger(*case["trigger_text"])
    proposals = selector.propose_candidates(
        route_id="route-a",
        target_obligation_ids=["main-goal"],
        trigger_features=triggers,
        hints=[case["hint"]],
    )
    occurrence = next(
        (item for item in proposals if item.measure_name == case["expected_measure"]),
        None,
    )
    return bool(
        occurrence is not None
        and selector.validate_well_foundedness(occurrence)
        is case["requires_well_foundedness"]
        and occurrence.why_natural_index_is_insufficient
    )


def _common_mode_contract(case: dict[str, Any]) -> bool:
    routes: list[RouteDescriptor] = []
    strategies: list[StrategyCard] = []
    for index, assumption in enumerate(case["route_assumptions"]):
        strategy = _strategy(index, prerequisite=assumption)
        strategies.append(strategy)
        routes.append(
            RouteDescriptor(
                route_id=f"route-{index}",
                strategy_id=strategy.strategy_id,
                mechanism_signature=[f"mechanism-{index}"],
            )
        )
    matrix = CriticalAssumptionMatrix()
    assumptions = matrix.build(routes, strategies)
    shared = next(
        item
        for item in assumptions.values()
        if item.normalized_statement == case["expected_canonical_assumption"]
    )
    task = matrix.challenger_task(shared)
    return (
        len(shared.route_ids) == case["expected_min_routes"]
        and shared.verification_status != ClaimStatus.VERIFIED
        and case["votes_are_evidence"] is False
        and task["premise_eligible"] is False
    )


def _compression_contract(case: dict[str, Any]) -> bool:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    for index in range(case["obligation_count"]):
        graph.add_obligation(
            ProofObligation(
                obligation_id=f"obligation-{index}",
                problem_hash=PROBLEM_HASH,
                route_ids=[f"route-{index}"],
                kind=ObligationKind.LEMMA,
                statement=case["statement"],
                normalized_statement=str(case["statement"]).casefold(),
                assumptions=case["assumptions"],
                centrality=0.5 + index / 100,
                first_error_fingerprint=case["first_error_fingerprint"],
            )
        )
    compressor = BottleneckCompressor()
    clusters = compressor.materialize_clusters(
        graph, compressor.deterministic_clusters(graph)
    )
    return (
        len(clusters) == case["expected_cluster_count"]
        and len(graph.obligations) == case["obligation_count"]
        and len(clusters[0].member_obligation_ids) == case["obligation_count"]
        and case["preserve_original_nodes"]
    )


def _message_no_use_contract(case: dict[str, Any]) -> bool:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    graph.add_obligation(
        ProofObligation(
            obligation_id=case["target_obligation_id"],
            problem_hash=PROBLEM_HASH,
            route_ids=["route-b"],
            kind=ObligationKind.LEMMA,
            statement="Prove bridge B.",
            normalized_statement="prove bridge b.",
        )
    )
    controller = MessageUtilityController(proof_graph=graph)
    message = _message(case["message"])
    controller.register_contract(
        message,
        target_obligation_ids=[case["target_obligation_id"]],
        expected_effect=MessageExpectedEffect.CLOSE,
        current_round=0,
    )
    receipt = controller.record_usage(
        message_id=message.message_id,
        consumer_route_id="route-b",
        referenced_step_ids=[case["claimed_step_id"]],
        verified_step_ids=case["verified_step_ids"],
    )
    return (
        receipt.verified_use is case["expected_verified_use"]
        and receipt.utility_score == case["expected_utility"]
    )


def _near_miss_contract() -> bool:
    report = VerificationReport(
        report_id="near-miss-report",
        target_id="candidate-a",
        target_type="proof_delta",
        agent_id="route-referee",
        stage=VerificationStage.DETAILED,
        problem_integrity_ok=True,
        verdict=VerificationVerdict.FAIL,
        first_error_step="realizer-boundary",
        issues=[
            VerificationIssue(
                phase="admissibility",
                severity=Severity.ERROR,
                step_id="realizer-boundary",
                description="the concrete realizer violates the lower bound",
                repair_hint="replace_realizer_preserve_structure",
            )
        ],
        failure_level=FailureLevel.EXECUTION,
        confidence=0.95,
        concise_feedback="Preserve the abstract quotient and replace the realizer.",
    )
    record = NearMissLedger().extract_deterministic(
        report,
        route_id="route-a",
        target_obligation_id="main-goal",
        abstract_idea="descend through quotient classes",
        concrete_candidate="choose the first representative",
        preserved_properties=["quotient invariant"],
        salvageable_components=["quotient descent"],
        suggested_repair_operators=["replace_realizer_preserve_structure"],
    )
    return bool(
        record
        and record.abstract_idea == "descend through quotient classes"
        and "replace_realizer_preserve_structure" in record.suggested_repair_operators
    )


def _fast_lane_contract() -> bool:
    counterexample = ExperimentResult(
        experiment_id="fast-counterexample",
        request_hash="request-counterexample",
        target_claim="Every residue modulo 5 is idempotent.",
        method="modular_exhaustive",
        outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
        evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
        counterexample={"x": 2},
        exact_arithmetic=True,
        cases_checked=3,
        tool_name="modular_exhaustive",
        tool_version="benchmark",
        independently_verified=True,
    )
    bounded = ExperimentResult(
        experiment_id="fast-bounded",
        request_hash="request-bounded",
        target_claim="No tested residue violates the bridge.",
        method="modular_exhaustive",
        outcome=ExperimentOutcome.NOT_REFUTED,
        evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
        exact_arithmetic=True,
        cases_checked=5,
        tool_name="modular_exhaustive",
        tool_version="benchmark",
    )
    refuted = classify_falsification_result(counterexample)
    inconclusive = classify_falsification_result(bounded)
    return (
        refuted.memory_tier == MemoryTier.NEGATIVE
        and refuted.conclusive_refutation
        and inconclusive.memory_tier == MemoryTier.INSIGHT
        and not inconclusive.conclusive_refutation
        and MemoryTier.FACT not in {refuted.memory_tier, inconclusive.memory_tier}
    )


def _component_contracts(cases: dict[str, dict[str, Any]]) -> dict[str, bool]:
    return {
        "overstrong_target": _overstrong_contract(cases["overstrong_target.json"]),
        "bounded_gap_nonperiodic": _bounded_gap_contract(
            cases["bounded_gap_nonperiodic.json"]
        ),
        "eventual_to_global": _eventual_contract(cases["eventual_to_global.json"]),
        "projection_information_loss": _projection_contract(
            cases["projection_information_loss.json"]
        ),
        "image_inclusion_not_surjective": _image_contract(
            cases["image_inclusion_not_surjective.json"]
        ),
        "abstract_realizer_repair": _realizer_contract(
            cases["abstract_realizer_repair.json"]
        ),
        "occurrence_induction": _occurrence_contract(
            cases["occurrence_induction.json"]
        ),
        "common_mode_assumption": _common_mode_contract(
            cases["common_mode_assumption.json"]
        ),
        "obligation_compression": _compression_contract(
            cases["obligation_compression.json"]
        ),
        "message_without_use": _message_no_use_contract(
            cases["message_without_use.json"]
        ),
        "near_miss_repair": _near_miss_contract(),
        "falsification_fast_lane": _fast_lane_contract(),
    }


def _gate_variant(mode: str) -> dict[str, Any]:
    target = ProofObligation(
        obligation_id="main-goal",
        problem_hash=PROBLEM_HASH,
        route_ids=["route-a"],
        kind=ObligationKind.MAIN_GOAL,
        statement="Prove G.",
        normalized_statement="prove g.",
        dependency_ids=["core-bridge"],
    )
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    graph.add_obligation(
        ProofObligation(
            obligation_id="core-bridge",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.LEMMA,
            statement="Prove bridge B.",
            normalized_statement="prove bridge b.",
        )
    )
    graph.add_obligation(target)
    strategy = _strategy(0)
    link = ClaimGoalLink(
        subject_id=strategy.strategy_id,
        subject_kind="strategy",
        target_obligation_id=target.obligation_id,
        relation=GoalRelation.SUFFICIENT,
        scope_relation=ScopeRelation.CLAIM_STRONGER,
        alignment_confidence=1.0,
    )
    route = RouteAdmissionGate(RouteAdmissionControlConfig(mode=mode)).evaluate(
        strategy,
        goal_link=link,
        target_obligations=[target],
        core_obligation_ids=[target.obligation_id],
    )
    continuation_gate = ContinueDeepeningGate(
        ContinueGateControlConfig(mode=mode, no_core_progress_segments=2)
    )
    continuation_gate.evaluate(
        route_id="route-a",
        segment_index=1,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=False,
        first_error_fingerprint="same-error",
    )
    continuation = continuation_gate.evaluate(
        route_id="route-a",
        segment_index=2,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=False,
        first_error_fingerprint="same-error",
    )
    synthesis = SynthesisReadinessGate(
        SynthesisReadinessControlConfig(mode=mode)
    ).evaluate(graph)
    runtime_blocks = sum(
        (
            RouteAdmissionGate.blocks_runtime(route),
            ContinueDeepeningGate.blocks_deepening(continuation),
            SynthesisReadinessGate.blocks_synthesis(synthesis),
        )
    )
    return {
        "variant": f"proof_control_{mode}",
        "route_admission": route.verdict.value,
        "continue_gate": continuation.verdict.value,
        "synthesis_readiness": synthesis.verdict.value,
        "runtime_blocks": runtime_blocks,
    }


def run_mock_benchmark() -> dict[str, Any]:
    cases = _load_cases(Path(__file__).parent)
    contracts = _component_contracts(cases)
    variants = [_gate_variant(mode) for mode in ("off", "shadow", "active")]
    active = next(
        item for item in variants if item["variant"] == "proof_control_active"
    )
    contracts.update(
        {
            "continue_deepening_gate": active["continue_gate"] == "block",
            "synthesis_readiness_gate": active["synthesis_readiness"] == "block",
        }
    )
    failed = [name for name, passed in contracts.items() if not passed]
    if failed:
        raise RuntimeError(f"proof-control component contract failure: {failed}")
    return {
        "benchmark": "mathproofmesh_v0_8_proof_control_mock",
        "provider_calls": 0,
        "provider_cost_measured": False,
        "case_count": len(cases),
        "contract_count": len(contracts),
        "component_contracts": contracts,
        "variants": variants,
        "notes": [
            "All checks are deterministic and offline.",
            "Off preserves v0.7 behavior; shadow records without runtime blocks.",
            "Active applies route, continuation, and synthesis decisions.",
            "No token limit, segment length, or Deep Exploration tier is varied.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = run_mock_benchmark()
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
