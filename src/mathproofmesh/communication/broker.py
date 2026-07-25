from __future__ import annotations

from collections import defaultdict
import secrets
from typing import TYPE_CHECKING, Any

from ..activity import ActivityStream
from ..config import SystemConfig
from ..schemas import (
    BrokerDecision,
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageEnvelope,
    MessageReceipt,
    ReceiptStatus,
    stable_hash,
)
from ..store import ArtifactStore
from .policies import (
    cross_route_share_allowed,
    validate_artifact_refs,
    validate_evidence_tier,
)
from .route_registry import RouteRegistry

if TYPE_CHECKING:
    from ..memory import TypedMemory
    from ..proof_graph.store import ProofGraphStore


def delivery_key(message_id: str, target_route_id: str) -> str:
    return stable_hash((message_id, target_route_id))


class MessageBroker:
    """Single gate for typed cross-route artifacts and exactly-once delivery."""

    def __init__(
        self,
        config: SystemConfig,
        store: ArtifactStore | None,
        activity: ActivityStream | None,
        route_registry: RouteRegistry,
        proof_graph: "ProofGraphStore",
        typed_memory: "TypedMemory",
    ) -> None:
        self.config = config
        self.store = store
        self.activity = activity
        self.route_registry = route_registry
        self.proof_graph = proof_graph
        self.typed_memory = typed_memory
        self._messages: dict[str, MessageEnvelope] = {}
        self._dedup: dict[str, str] = {}
        self._decisions: list[BrokerDecision] = []
        self._deliveries: dict[str, dict[str, Any]] = {}
        self._route_queues: dict[str, list[str]] = defaultdict(list)
        self._receipts: dict[str, MessageReceipt] = {}
        self._utility_records: dict[str, dict[str, Any]] = {}
        self._review_provenance: dict[str, dict[str, Any]] = {}
        self._isolation_pending: dict[str, list[str]] = defaultdict(list)
        self._round_route_counts: dict[str, int] = defaultdict(int)
        self._round_global_counts: dict[int, int] = defaultdict(int)

    @property
    def decisions(self) -> list[BrokerDecision]:
        return list(self._decisions)

    @property
    def receipts(self) -> list[MessageReceipt]:
        return list(self._receipts.values())

    def delivery_record(
        self, message_id: str, target_route_id: str
    ) -> dict[str, Any] | None:
        delivery = self._deliveries.get(delivery_key(message_id, target_route_id))
        return dict(delivery) if delivery is not None else None

    def contains(
        self, message: MessageEnvelope, *, current_round: int | None = None
    ) -> bool:
        """Return whether the semantic delta has already passed through this broker."""

        message_id = (
            message.message_id
            if message.message_id in self._messages
            else self._dedup.get(self._dedup_key(message))
        )
        if message_id is None:
            return False
        if current_round is None:
            return True
        existing_targets = {
            str(delivery["target_route_id"])
            for delivery in self._deliveries.values()
            if delivery["message_id"] == message_id
        } | set(self._isolation_pending.get(message_id, []))
        desired = set(message.target_route_ids) or set(
            self.route_registry.neighbors(message.source_route_id, current_round)
        )
        return desired.issubset(existing_targets)

    def _dedup_key(self, message: MessageEnvelope) -> str:
        return stable_hash(
            (
                message.problem_hash,
                message.message_type.value,
                message.normalized_statement,
                tuple(sorted(message.assumptions)),
                tuple(sorted(message.dependencies)),
                message.evidence_type.value,
                message.memory_tier.value,
            )
        )

    def _reject(self, message: MessageEnvelope, reason: str) -> BrokerDecision:
        decision = BrokerDecision(
            message_id=message.message_id,
            accepted=False,
            rejection_reason=reason,
        )
        self._record_decision(decision, "message_rejected")
        return decision

    def _record_decision(self, decision: BrokerDecision, event_type: str) -> None:
        self._decisions.append(decision)
        if self.store is not None:
            self.store.append_event(event_type, decision)
            self.store.append_message_event(event_type, decision)
            self.store.write_json("communication", "broker_state", self.export_state())
        if self.activity is not None:
            self.activity.info(
                event_type,
                title=(
                    "Typed message accepted"
                    if decision.accepted
                    else "Typed message rejected"
                ),
                detail=decision.rejection_reason or "",
                stage="message_broker",
                metrics={
                    "message_id": decision.message_id,
                    "targets": decision.selected_targets,
                    "duplicate_of": decision.duplicate_of,
                },
            )

    def _emit_delivery_event(
        self, event_type: str, payload: MessageReceipt | dict[str, Any]
    ) -> None:
        if self.store is not None:
            self.store.append_event(event_type, payload)
            self.store.append_message_event(event_type, payload)
        if self.activity is not None:
            metrics = (
                payload.model_dump(mode="json")
                if isinstance(payload, MessageReceipt)
                else payload
            )
            self.activity.info(
                event_type,
                title=event_type.replace("_", " ").title(),
                detail=str(metrics.get("reason", "")),
                stage="message_broker",
                metrics=metrics,
            )

    def publish(
        self,
        message: MessageEnvelope,
        *,
        referee_agent_id: str | None,
        current_round: int,
    ) -> BrokerDecision:
        typed = self.config.topology.typed_communication
        cross = self.config.topology.cross_route

        # 1-6: schema, identity, limits, scope, and immutable hash.
        if message.schema_version != typed.schema_version:
            return self._reject(message, "unsupported message schema version")
        if not self.route_registry.problem_hash:
            self.route_registry.problem_hash = message.problem_hash
        if (
            typed.require_problem_hash
            and message.problem_hash != self.route_registry.problem_hash
        ):
            return self._reject(message, "problem_hash mismatch")
        if not self.route_registry.owns_agent(
            message.source_route_id, message.source_agent_id, message.source_role
        ):
            return self._reject(message, "source agent/role does not belong to route")
        if len(message.model_dump_json()) > typed.max_message_chars:
            return self._reject(message, "message exceeds max_message_chars")
        if len(message.assumptions) > typed.max_assumptions:
            return self._reject(message, "message exceeds max_assumptions")
        if len(message.dependencies) > typed.max_dependencies:
            return self._reject(message, "message exceeds max_dependencies")
        if typed.require_content_hash and (
            message.content_hash != message.expected_content_hash()
        ):
            return self._reject(message, "content_hash mismatch")
        artifact_gate = validate_artifact_refs(
            [*message.artifact_refs]
            + ([message.raw_source_ref] if message.raw_source_ref else [])
        )
        if not artifact_gate.accepted:
            return self._reject(message, artifact_gate.reason)
        if self.store is not None:
            try:
                for ref in [*message.artifact_refs] + (
                    [message.raw_source_ref] if message.raw_source_ref else []
                ):
                    self.store.resolve(ref)
            except (ValueError, OSError):
                return self._reject(message, "artifact reference is outside this run")

        # 7: deduplication never upgrades evidence by popularity.
        duplicate_key = self._dedup_key(message)
        duplicate_id = self._dedup.get(duplicate_key)
        if duplicate_id is not None:
            existing = self._messages[duplicate_id]
            existing.artifact_refs = list(
                dict.fromkeys(existing.artifact_refs + message.artifact_refs)
            )
            candidate_targets = list(
                message.target_route_ids
            ) or self.route_registry.neighbors(existing.source_route_id, current_round)
            existing_targets = {
                str(delivery["target_route_id"])
                for delivery in self._deliveries.values()
                if delivery["message_id"] == duplicate_id
            } | set(self._isolation_pending.get(duplicate_id, []))
            new_targets = [
                target
                for target in candidate_targets
                if target != existing.source_route_id and target not in existing_targets
            ]
            for target in new_targets:
                self._enqueue(existing, target, current_round)
            decision = BrokerDecision(
                message_id=message.message_id,
                accepted=True,
                duplicate_of=duplicate_id,
                selected_targets=new_targets,
            )
            self._record_decision(decision, "message_deduplicated")
            return decision

        dependencies_resolved = self.typed_memory.dependencies_resolved(
            message.dependencies
        )
        dependency_cycle = self.typed_memory.would_create_cycle(
            message.message_id, message.dependencies
        )
        known_counterexample = self.typed_memory.has_counterexample(
            message.normalized_statement
        )
        gate = validate_evidence_tier(
            message,
            self.config,
            referee_agent_id=referee_agent_id,
            dependencies_resolved=dependencies_resolved,
            dependency_cycle=dependency_cycle,
            known_counterexample=known_counterexample,
        )
        if not gate.accepted:
            return self._reject(message, gate.reason)

        # 8-12: isolation, global-share gate, matching, and rate limits.
        explicit = list(dict.fromkeys(message.target_route_ids))
        if message.evidence_type == EvidenceType.COUNTEREXAMPLE:
            explicit.extend(
                self.typed_memory.affected_routes_for_counterexample(message)
            )
        candidate_targets = explicit or self.route_registry.neighbors(
            message.source_route_id, current_round
        )
        source_neighbors = set(
            self.route_registry.neighbors(message.source_route_id, current_round)
        )
        selected: list[str] = []
        rejected: dict[str, str] = {}
        can_share = cross_route_share_allowed(message, self.config)
        max_neighbors = cross.max_neighbors_per_route
        for target in dict.fromkeys(candidate_targets):
            if target == message.source_route_id:
                continue
            try:
                self.route_registry.get(target)
            except KeyError:
                rejected[target] = "unknown target route"
                continue
            if not cross.enabled or not can_share:
                rejected[target] = "cross-route sharing disabled for this message"
                continue
            if (
                message.evidence_type != EvidenceType.COUNTEREXAMPLE
                and target not in source_neighbors
            ):
                rejected[target] = "target is not a sparse neighbor"
                continue
            if len(selected) >= max_neighbors:
                rejected[target] = "neighbor cap reached"
                continue
            route_count_key = f"{current_round}:{target}"
            if (
                self._round_route_counts[route_count_key]
                >= cross.max_messages_per_route_per_round
            ):
                rejected[target] = "per-route round message cap reached"
                continue
            if (
                self._round_global_counts[current_round]
                >= cross.max_global_messages_per_round
            ):
                rejected[target] = "global round message cap reached"
                continue
            selected.append(target)

        self._messages[message.message_id] = message
        self._dedup[duplicate_key] = message.message_id
        if referee_agent_id is not None:
            self._review_provenance[message.message_id] = {
                "referee_agent_id": referee_agent_id,
                "independent": referee_agent_id != message.source_agent_id,
                "review_round": current_round,
            }
        # Only broker-admitted deliveries may make an artifact visible in a
        # target route's persistent typed-memory context. Requested but rejected
        # targets must not bypass the broker through MessageEnvelope metadata.
        message.target_route_ids = []

        # 13-14: memory and graph write happen even for a route-local insight.
        self.typed_memory.add_message(message, referee_agent_id=referee_agent_id)
        self.proof_graph.ingest_message(message)

        isolated = (
            current_round < cross.initial_isolation_rounds
            and message.evidence_type != EvidenceType.COUNTEREXAMPLE
        )
        for target in selected:
            if isolated:
                self._isolation_pending[message.message_id].append(target)
                continue
            self._enqueue(message, target, current_round)

        if message.evidence_type == EvidenceType.COUNTEREXAMPLE:
            self.typed_memory.apply_counterexample(message)
            self.proof_graph.apply_counterexample(message)

        decision = BrokerDecision(
            message_id=message.message_id,
            accepted=True,
            selected_targets=selected,
            rejected_targets=rejected,
            score_breakdown={
                "selected_target_count": float(len(selected)),
                "queued_by_initial_isolation": float(len(selected) if isolated else 0),
            },
        )
        self._record_decision(decision, "message_published")
        return decision

    def _enqueue(
        self, message: MessageEnvelope, target_route_id: str, current_round: int
    ) -> None:
        key = delivery_key(message.message_id, target_route_id)
        if key in self._deliveries:
            return
        self._deliveries[key] = {
            "delivery_key": key,
            "message_id": message.message_id,
            "target_route_id": target_route_id,
            "delivered_round": current_round,
            "prompt_consumed": False,
            "acknowledged": False,
            "status": "pending",
            "receipt_token": secrets.token_urlsafe(24),
            "processing_opportunities": 0,
        }
        if target_route_id not in message.target_route_ids:
            message.target_route_ids.append(target_route_id)
        self._route_queues[target_route_id].append(key)
        count_key = f"{current_round}:{target_route_id}"
        self._round_route_counts[count_key] += 1
        self._round_global_counts[current_round] += 1

    def _release_isolation(self, current_round: int) -> None:
        if current_round < self.config.topology.cross_route.initial_isolation_rounds:
            return
        cross = self.config.topology.cross_route
        for message_id, targets in list(self._isolation_pending.items()):
            message = self._messages.get(message_id)
            if message is None:
                continue
            deferred: list[str] = []
            for target in targets:
                route_key = f"{current_round}:{target}"
                if (
                    self._round_route_counts[route_key]
                    >= cross.max_messages_per_route_per_round
                    or self._round_global_counts[current_round]
                    >= cross.max_global_messages_per_round
                ):
                    deferred.append(target)
                    continue
                self._enqueue(message, target, current_round)
            if deferred:
                self._isolation_pending[message_id] = deferred
            else:
                del self._isolation_pending[message_id]

    def inbox(
        self,
        route_id: str,
        *,
        current_round: int,
        max_messages: int | None = None,
    ) -> list[MessageEnvelope]:
        self.route_registry.get(route_id)
        self._release_isolation(current_round)
        for key in self._route_queues.get(route_id, []):
            delivery = self._deliveries[key]
            if delivery["status"] == "pending":
                delivery["processing_opportunities"] = (
                    int(delivery.get("processing_opportunities", 0)) + 1
                )
        self.expire(current_round)
        limit = max_messages
        if limit is None:
            limit = self.config.topology.cross_route.max_messages_per_route_per_round
        result: list[MessageEnvelope] = []
        queue = sorted(
            self._route_queues.get(route_id, []),
            key=lambda key: (
                0
                if self._messages[self._deliveries[key]["message_id"]].evidence_type
                == EvidenceType.COUNTEREXAMPLE
                else 1,
                self._deliveries[key]["delivered_round"],
                key,
            ),
        )
        for key in queue:
            delivery = self._deliveries[key]
            if delivery["status"] != "pending" or delivery["prompt_consumed"]:
                continue
            message = self._messages[delivery["message_id"]]
            result.append(message)
            delivery["prompt_consumed"] = True
            self._emit_delivery_event(
                "message_delivered",
                {
                    "message_id": message.message_id,
                    "source_route_id": message.source_route_id,
                    "target_route_id": route_id,
                    "message_type": message.message_type.value,
                    "memory_tier": message.memory_tier.value,
                    "delivered_round": delivery["delivered_round"],
                },
            )
            if len(result) >= limit:
                break
        return result

    def acknowledge(self, receipt: MessageReceipt) -> None:
        key = delivery_key(receipt.message_id, receipt.target_route_id)
        delivery = self._deliveries.get(key)
        if delivery is None:
            raise ValueError("receipt does not correspond to a delivery")
        message = self._messages[receipt.message_id]
        expected = message.expected_semantic_hash()
        parsed_hash = stable_hash(
            {
                "assumptions": receipt.parsed_assumptions,
                "conclusion": receipt.parsed_conclusion,
                "quantifiers": [
                    item.model_dump(mode="json") for item in receipt.parsed_quantifiers
                ],
                "variable_bindings": [
                    item.model_dump(mode="json")
                    for item in receipt.parsed_variable_bindings
                ],
            }
        )
        expected_token = str(delivery.get("receipt_token", ""))
        token_valid = bool(receipt.receipt_token) and secrets.compare_digest(
            receipt.receipt_token, expected_token
        )
        # Compatibility for v0.7 checkpoints created before opaque tokens. The
        # semantic hash is computed by trusted local code, never copied from a
        # model-generated field.
        legacy_receipt = not receipt.receipt_token and bool(receipt.semantic_hash)
        legacy_semantic_valid = (
            legacy_receipt
            and receipt.semantic_hash == expected
            and parsed_hash == expected
        )
        if not token_valid and not legacy_semantic_valid:
            receipt.status = ReceiptStatus.REJECTED
            receipt.reason = (
                "semantic hash mismatch"
                if legacy_receipt
                else "invalid or missing broker receipt token"
            )
        delivery["acknowledged"] = True
        delivery["status"] = receipt.status.value
        self._receipts[key] = receipt
        self._emit_delivery_event("message_acknowledged", receipt)

    def record_utility(
        self,
        message_id: str,
        target_route_id: str,
        *,
        referenced_step_ids: list[str] | None = None,
        closed_obligation_ids: list[str] | None = None,
        refuted_claim_ids: list[str] | None = None,
        produced_message_ids: list[str] | None = None,
        blueprint_rewrite_request_ids: list[str] | None = None,
        cited_by_final_proof: bool = False,
        proof_debt_before: float | None = None,
        proof_debt_after: float | None = None,
    ) -> bool:
        """Record only externally verified mathematical use, never mere receipt."""
        key = delivery_key(message_id, target_route_id)
        receipt = self._receipts.get(key)
        if receipt is None or receipt.status != ReceiptStatus.ACCEPTED:
            return False
        step_ids = sorted(set(referenced_step_ids or []))
        obligation_ids = sorted(set(closed_obligation_ids or []))
        refutation_ids = sorted(set(refuted_claim_ids or []))
        produced_ids = sorted(set(produced_message_ids or []))
        rewrite_ids = sorted(set(blueprint_rewrite_request_ids or []))
        debt_reduction = 0.0
        if proof_debt_before is not None and proof_debt_after is not None:
            debt_reduction = max(0.0, proof_debt_before - proof_debt_after)
        # A route-wide debt drop is not, by itself, attributable to this
        # particular message. Require a verified citation or obligation closure;
        # debt reduction then strengthens that already-established use.
        if not (
            step_ids
            or obligation_ids
            or refutation_ids
            or produced_ids
            or rewrite_ids
            or cited_by_final_proof
        ):
            return False
        score = min(
            1.0,
            (0.3 if step_ids else 0.0)
            + (0.3 if obligation_ids else 0.0)
            + (0.3 if refutation_ids else 0.0)
            + (0.2 if produced_ids else 0.0)
            + (0.2 if rewrite_ids else 0.0)
            + (0.4 if cited_by_final_proof else 0.0)
            + min(0.2, debt_reduction),
        )
        record = {
            "message_id": message_id,
            "target_route_id": target_route_id,
            "referenced_step_ids": step_ids,
            "closed_obligation_ids": obligation_ids,
            "refuted_claim_ids": refutation_ids,
            "produced_message_ids": produced_ids,
            "blueprint_rewrite_request_ids": rewrite_ids,
            "cited_by_final_proof": cited_by_final_proof,
            "proof_debt_reduction": debt_reduction,
            "score": score,
        }
        self._utility_records[key] = record
        self._emit_delivery_event("message_used", record)
        return True

    def utility_for_route(self, route_id: str) -> float:
        accepted_keys = [
            key
            for key, receipt in self._receipts.items()
            if receipt.target_route_id == route_id
            and receipt.status == ReceiptStatus.ACCEPTED
        ]
        if not accepted_keys:
            return 0.0
        return sum(
            float(self._utility_records.get(key, {}).get("score", 0.0))
            for key in accepted_keys
        ) / len(accepted_keys)

    def blind_review_provenance(self, message_id: str) -> dict[str, Any]:
        """Return auditable referee provenance without leaking reviewer identity."""
        record = self._review_provenance.get(message_id)
        if record is None:
            return {
                "independent_referee_recorded": False,
                "reviewer_count": 0,
            }
        referee = str(record.get("referee_agent_id", ""))
        return {
            "independent_referee_recorded": bool(record.get("independent", False)),
            "reviewer_count": 1 if referee else 0,
            "reviewer_identity_hash": (
                stable_hash(
                    {
                        "problem_hash": self.route_registry.problem_hash,
                        "message_id": message_id,
                        "referee": referee,
                    }
                )
                if referee
                else ""
            ),
            "review_round": record.get("review_round"),
        }

    def is_globally_admitted_fact(self, message_id: str) -> bool:
        """Return whether a live typed fact passed this broker and an independent referee."""
        message = self._messages.get(message_id)
        if message is None:
            return False
        if message.memory_tier != MemoryTier.FACT:
            return False
        if message.verification_status != ClaimStatus.VERIFIED:
            return False
        current_fact = next(
            (fact for fact in self.typed_memory.facts if fact.message_id == message_id),
            None,
        )
        if current_fact is None or current_fact.content_hash != message.content_hash:
            return False
        provenance = self.blind_review_provenance(message_id)
        return bool(provenance["independent_referee_recorded"])

    def admitted_facts(self) -> list[MessageEnvelope]:
        """Return globally admissible facts in stable broker insertion order."""
        return [
            message
            for message in self._messages.values()
            if self.is_globally_admitted_fact(message.message_id)
        ]

    def expire(self, current_round: int) -> list[str]:
        expired: list[str] = []
        for key, delivery in self._deliveries.items():
            if delivery["status"] != "pending":
                continue
            message = self._messages[delivery["message_id"]]
            if int(delivery.get("processing_opportunities", 0)) > message.ttl_rounds:
                delivery["status"] = ReceiptStatus.EXPIRED.value
                expired.append(key)
                self._emit_delivery_event(
                    "message_expired",
                    {
                        "message_id": message.message_id,
                        "target_route_id": delivery["target_route_id"],
                        "current_round": current_round,
                        "processing_opportunities": delivery.get(
                            "processing_opportunities", 0
                        ),
                    },
                )
        return expired

    def export_state(self) -> dict[str, Any]:
        return {
            "messages": {
                key: value.model_dump(mode="json")
                for key, value in self._messages.items()
            },
            "admitted_fact_ids": [
                message.message_id for message in self.admitted_facts()
            ],
            "dedup": dict(self._dedup),
            "decisions": [item.model_dump(mode="json") for item in self._decisions],
            "deliveries": dict(self._deliveries),
            "route_queues": dict(self._route_queues),
            "receipts": {
                key: value.model_dump(mode="json")
                for key, value in self._receipts.items()
            },
            "utility_records": dict(self._utility_records),
            "review_provenance": dict(self._review_provenance),
            "isolation_pending": dict(self._isolation_pending),
            "round_route_counts": dict(self._round_route_counts),
            "round_global_counts": dict(self._round_global_counts),
        }

    @classmethod
    def from_state(
        cls,
        state: dict[str, Any],
        *,
        config: SystemConfig,
        store: ArtifactStore | None,
        activity: ActivityStream | None,
        route_registry: RouteRegistry,
        proof_graph: "ProofGraphStore",
        typed_memory: "TypedMemory",
    ) -> "MessageBroker":
        broker = cls(
            config,
            store,
            activity,
            route_registry,
            proof_graph,
            typed_memory,
        )
        broker._messages = {
            str(key): MessageEnvelope.model_validate(value)
            for key, value in dict(state.get("messages", {})).items()
        }
        broker._dedup = {
            str(key): str(value) for key, value in dict(state.get("dedup", {})).items()
        }
        broker._decisions = [
            BrokerDecision.model_validate(item) for item in state.get("decisions", [])
        ]
        broker._deliveries = {
            str(key): dict(value)
            for key, value in dict(state.get("deliveries", {})).items()
        }
        broker._route_queues = defaultdict(
            list,
            {
                str(key): list(value)
                for key, value in dict(state.get("route_queues", {})).items()
            },
        )
        broker._receipts = {
            str(key): MessageReceipt.model_validate(value)
            for key, value in dict(state.get("receipts", {})).items()
        }
        broker._utility_records = {
            str(key): dict(value)
            for key, value in dict(state.get("utility_records", {})).items()
        }
        broker._review_provenance = {
            str(key): dict(value)
            for key, value in dict(state.get("review_provenance", {})).items()
        }
        broker._isolation_pending = defaultdict(
            list,
            {
                str(key): list(value)
                for key, value in dict(state.get("isolation_pending", {})).items()
            },
        )
        broker._round_route_counts = defaultdict(
            int,
            {
                str(key): int(value)
                for key, value in dict(state.get("round_route_counts", {})).items()
            },
        )
        broker._round_global_counts = defaultdict(
            int,
            {
                int(key): int(value)
                for key, value in dict(state.get("round_global_counts", {})).items()
            },
        )
        return broker
