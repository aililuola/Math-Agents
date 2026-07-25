from __future__ import annotations

from collections.abc import Mapping
from typing import Any, TypeVar

from pydantic import BaseModel

from .models import (
    AbstractStructureProposal,
    BlueprintRewriteRequest,
    BottleneckCluster,
    ClaimGoalLink,
    ContinueGateRecord,
    CriticalAssumption,
    FailureClassificationRecord,
    InductionMeasureProposal,
    InferenceRiskRecord,
    MinimalBridgeProposal,
    MessageUsageReceipt,
    MessageUtilityContract,
    NearMissRecord,
    ProofRole,
    RealizerCandidate,
    RealizerRepairTask,
    RouteAdmissionRecord,
    ScopeSignature,
    SynthesisReadinessRecord,
)

T = TypeVar("T", bound=BaseModel)


class ProofControlState:
    """Stable sidecar state that never participates in mathematical hashes."""

    schema_version = "0.8"

    def __init__(self) -> None:
        self.goal_links: dict[str, ClaimGoalLink] = {}
        self.scope_signatures: dict[str, ScopeSignature] = {}
        self.proof_roles: dict[str, ProofRole] = {}
        self.inference_risks: dict[str, InferenceRiskRecord] = {}
        self.minimal_bridge_proposals: dict[str, MinimalBridgeProposal] = {}
        self.abstract_structures: dict[str, AbstractStructureProposal] = {}
        self.realizer_candidates: dict[str, RealizerCandidate] = {}
        self.realizer_repair_tasks: dict[str, RealizerRepairTask] = {}
        self.induction_measures: dict[str, InductionMeasureProposal] = {}
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
        self.events: list[dict[str, Any]] = []

    @staticmethod
    def _dump_models(values: Mapping[str, BaseModel]) -> dict[str, Any]:
        return {key: values[key].model_dump(mode="json") for key in sorted(values)}

    def export_state(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "goal_links": self._dump_models(self.goal_links),
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
            ("scope_signatures", ScopeSignature),
            ("inference_risks", InferenceRiskRecord),
            ("minimal_bridge_proposals", MinimalBridgeProposal),
            ("abstract_structures", AbstractStructureProposal),
            ("realizer_candidates", RealizerCandidate),
            ("realizer_repair_tasks", RealizerRepairTask),
            ("induction_measures", InductionMeasureProposal),
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
