from __future__ import annotations

from collections.abc import Mapping
from typing import Any, TypeVar

from pydantic import BaseModel

from .models import (
    AbstractStructureProposal,
    BlueprintRewriteRequest,
    BottleneckCluster,
    ClaimVerificationLedgerEntry,
    ClaimGoalLink,
    ControlActionRecord,
    CountermodelTaskRecord,
    ContinueGateRecord,
    CriticalAssumption,
    FailureClassificationRecord,
    GoalAlignmentContractResult,
    InductionBlueprintNode,
    InductionMeasureProposal,
    InferenceRiskRecord,
    MinimalBridgeProposal,
    MessageUsageReceipt,
    MessageUtilityContract,
    NearMissRecord,
    NegativePatternRecord,
    PremiseClosureRecord,
    ProofRole,
    RealizerCandidate,
    RealizerRepairTask,
    RouteAdmissionRecord,
    RouteTargetBinding,
    ScopeSignature,
    SynthesisReadinessRecord,
)

T = TypeVar("T", bound=BaseModel)


class ProofControlState:
    """Stable sidecar state that never participates in mathematical hashes."""

    schema_version = "0.8.1"

    def __init__(self) -> None:
        self.goal_links: dict[str, ClaimGoalLink] = {}
        self.control_actions: dict[str, ControlActionRecord] = {}
        self.route_target_bindings: dict[str, RouteTargetBinding] = {}
        self.goal_alignment_contracts: dict[str, GoalAlignmentContractResult] = {}
        self.claim_verification_ledger: dict[str, ClaimVerificationLedgerEntry] = {}
        self.premise_closure_records: dict[str, PremiseClosureRecord] = {}
        self.countermodel_tasks: dict[str, CountermodelTaskRecord] = {}
        self.negative_patterns: dict[str, NegativePatternRecord] = {}
        self.scope_signatures: dict[str, ScopeSignature] = {}
        self.proof_roles: dict[str, ProofRole] = {}
        self.inference_risks: dict[str, InferenceRiskRecord] = {}
        self.minimal_bridge_proposals: dict[str, MinimalBridgeProposal] = {}
        self.abstract_structures: dict[str, AbstractStructureProposal] = {}
        self.realizer_candidates: dict[str, RealizerCandidate] = {}
        self.realizer_repair_tasks: dict[str, RealizerRepairTask] = {}
        self.induction_measures: dict[str, InductionMeasureProposal] = {}
        self.induction_blueprints: dict[str, InductionBlueprintNode] = {}
        self.failure_records: dict[str, FailureClassificationRecord] = {}
        self.blueprint_rewrites: dict[str, BlueprintRewriteRequest] = {}
        self.bottleneck_clusters: dict[str, BottleneckCluster] = {}
        self.critical_assumptions: dict[str, CriticalAssumption] = {}
        self.utility_contracts: dict[str, MessageUtilityContract] = {}
        self.usage_receipts: dict[str, MessageUsageReceipt] = {}
        self.near_misses: dict[str, NearMissRecord] = {}
        self.route_admissions: dict[str, RouteAdmissionRecord] = {}
        self.continue_gate_records: list[ContinueGateRecord] = []
        self.synthesis_readiness_records: list[SynthesisReadinessRecord] = []
        self.core_debt_history: dict[str, list[float]] = {}
        self.fast_lane_outcomes: dict[str, str] = {}
        self.events: list[dict[str, Any]] = []

    @staticmethod
    def _dump_models(values: Mapping[str, BaseModel]) -> dict[str, Any]:
        return {key: values[key].model_dump(mode="json") for key in sorted(values)}

    def export_state(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "goal_links": self._dump_models(self.goal_links),
            "control_actions": self._dump_models(self.control_actions),
            "route_target_bindings": self._dump_models(self.route_target_bindings),
            "goal_alignment_contracts": self._dump_models(
                self.goal_alignment_contracts
            ),
            "claim_verification_ledger": self._dump_models(
                self.claim_verification_ledger
            ),
            "premise_closure_records": self._dump_models(self.premise_closure_records),
            "countermodel_tasks": self._dump_models(self.countermodel_tasks),
            "negative_patterns": self._dump_models(self.negative_patterns),
            "scope_signatures": self._dump_models(self.scope_signatures),
            "proof_roles": {
                key: self.proof_roles[key].value for key in sorted(self.proof_roles)
            },
            "inference_risks": self._dump_models(self.inference_risks),
            "minimal_bridge_proposals": self._dump_models(
                self.minimal_bridge_proposals
            ),
            "abstract_structures": self._dump_models(self.abstract_structures),
            "realizer_candidates": self._dump_models(self.realizer_candidates),
            "realizer_repair_tasks": self._dump_models(self.realizer_repair_tasks),
            "induction_measures": self._dump_models(self.induction_measures),
            "induction_blueprints": self._dump_models(self.induction_blueprints),
            "failure_records": self._dump_models(self.failure_records),
            "blueprint_rewrites": self._dump_models(self.blueprint_rewrites),
            "bottleneck_clusters": self._dump_models(self.bottleneck_clusters),
            "critical_assumptions": self._dump_models(self.critical_assumptions),
            "utility_contracts": self._dump_models(self.utility_contracts),
            "usage_receipts": self._dump_models(self.usage_receipts),
            "near_misses": self._dump_models(self.near_misses),
            "route_admissions": self._dump_models(self.route_admissions),
            "continue_gate_records": [
                item.model_dump(mode="json") for item in self.continue_gate_records
            ],
            "synthesis_readiness_records": [
                item.model_dump(mode="json")
                for item in self.synthesis_readiness_records
            ],
            "core_debt_history": {
                key: [float(item) for item in self.core_debt_history[key]]
                for key in sorted(self.core_debt_history)
            },
            "fast_lane_outcomes": {
                key: self.fast_lane_outcomes[key]
                for key in sorted(self.fast_lane_outcomes)
            },
            "events": [dict(item) for item in self.events],
        }

    @classmethod
    def from_state(cls, state: Mapping[str, Any] | None) -> "ProofControlState":
        restored = cls()
        if not isinstance(state, Mapping):
            if state is not None:
                restored._migration_event("proof_control", "payload is not a mapping")
            return restored

        restored.events = [
            dict(item) for item in state.get("events", []) if isinstance(item, Mapping)
        ]
        model_fields: tuple[tuple[str, type[BaseModel]], ...] = (
            ("goal_links", ClaimGoalLink),
            ("control_actions", ControlActionRecord),
            ("route_target_bindings", RouteTargetBinding),
            ("goal_alignment_contracts", GoalAlignmentContractResult),
            ("claim_verification_ledger", ClaimVerificationLedgerEntry),
            ("premise_closure_records", PremiseClosureRecord),
            ("countermodel_tasks", CountermodelTaskRecord),
            ("negative_patterns", NegativePatternRecord),
            ("scope_signatures", ScopeSignature),
            ("inference_risks", InferenceRiskRecord),
            ("minimal_bridge_proposals", MinimalBridgeProposal),
            ("abstract_structures", AbstractStructureProposal),
            ("realizer_candidates", RealizerCandidate),
            ("realizer_repair_tasks", RealizerRepairTask),
            ("induction_measures", InductionMeasureProposal),
            ("induction_blueprints", InductionBlueprintNode),
            ("failure_records", FailureClassificationRecord),
            ("blueprint_rewrites", BlueprintRewriteRequest),
            ("bottleneck_clusters", BottleneckCluster),
            ("critical_assumptions", CriticalAssumption),
            ("utility_contracts", MessageUtilityContract),
            ("usage_receipts", MessageUsageReceipt),
            ("near_misses", NearMissRecord),
            ("route_admissions", RouteAdmissionRecord),
        )
        for field_name, model_type in model_fields:
            target = getattr(restored, field_name)
            raw_values = state.get(field_name, {})
            if not isinstance(raw_values, Mapping):
                restored._migration_event(field_name, "expected mapping")
                continue
            for key in sorted(raw_values, key=str):
                try:
                    target[str(key)] = model_type.model_validate(raw_values[key])
                except (TypeError, ValueError) as exc:
                    restored._migration_event(field_name, type(exc).__name__, str(key))

        raw_roles = state.get("proof_roles", {})
        if isinstance(raw_roles, Mapping):
            for key in sorted(raw_roles, key=str):
                try:
                    restored.proof_roles[str(key)] = ProofRole(raw_roles[key])
                except (TypeError, ValueError) as exc:
                    restored._migration_event(
                        "proof_roles", type(exc).__name__, str(key)
                    )
        else:
            restored._migration_event("proof_roles", "expected mapping")

        restored.continue_gate_records = restored._restore_list(
            state.get("continue_gate_records", []),
            ContinueGateRecord,
            "continue_gate_records",
        )
        restored.synthesis_readiness_records = restored._restore_list(
            state.get("synthesis_readiness_records", []),
            SynthesisReadinessRecord,
            "synthesis_readiness_records",
        )

        raw_history = state.get("core_debt_history", {})
        if isinstance(raw_history, Mapping):
            for key in sorted(raw_history, key=str):
                try:
                    values = raw_history[key]
                    if not isinstance(values, list):
                        raise TypeError("history must be a list")
                    restored.core_debt_history[str(key)] = [
                        float(item) for item in values
                    ]
                except (TypeError, ValueError) as exc:
                    restored._migration_event(
                        "core_debt_history", type(exc).__name__, str(key)
                    )
        else:
            restored._migration_event("core_debt_history", "expected mapping")

        raw_fast_lane = state.get("fast_lane_outcomes", {})
        if isinstance(raw_fast_lane, Mapping):
            restored.fast_lane_outcomes = {
                str(key): str(value)
                for key, value in sorted(
                    raw_fast_lane.items(), key=lambda item: str(item[0])
                )
            }
        else:
            restored._migration_event("fast_lane_outcomes", "expected mapping")
        return restored

    def _restore_list(
        self,
        raw_values: Any,
        model_type: type[T],
        field_name: str,
    ) -> list[T]:
        if not isinstance(raw_values, list):
            self._migration_event(field_name, "expected list")
            return []
        restored: list[T] = []
        for index, value in enumerate(raw_values):
            try:
                restored.append(model_type.model_validate(value))
            except (TypeError, ValueError) as exc:
                self._migration_event(field_name, type(exc).__name__, str(index))
        return restored

    def _migration_event(
        self, field_name: str, reason: str, record_key: str | None = None
    ) -> None:
        payload: dict[str, Any] = {
            "event_type": "proof_control_migration_record_skipped",
            "field": field_name,
            "reason": reason,
        }
        if record_key is not None:
            payload["record_key"] = record_key
        self.events.append(payload)
