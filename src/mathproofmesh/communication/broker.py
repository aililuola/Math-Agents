from __future__ import annotations

from collections import defaultdict
import secrets
from collections.abc import Callable
from typing import TYPE_CHECKING, Any

from ..activity import ActivityStream
from ..config import SystemConfig
from ..schemas import (
    BrokerDecision,
    ClaimStatus,
    DeliveryState,
    EvidenceType,
    MemoryTier,
    MessageEnvelope,
    MessagePriority,
    MessageReceipt,
    MessageType,
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
        self._invalidated_messages: dict[str, str] = {}
        self._invalidated_deliveries: dict[str, dict[str, Any]] = {}
        self._isolation_pending: dict[str, list[str]] = defaultdict(list)
        self._round_route_counts: dict[str, int] = defaultdict(int)
        self._round_global_counts: dict[int, int] = defaultdict(int)
        self._round_route_priority_counts: dict[str, int] = defaultdict(int)
        self._round_global_priority_counts: dict[str, int] = defaultdict(int)
        self._proof_control_message_gate: (
            Callable[[MessageEnvelope, int], tuple[bool, str | None]] | None
        ) = None
        self._proof_control_broadcast_gate: (
            Callable[[MessageEnvelope, int], bool] | None
        ) = None

    @property
    def decisions(self) -> list[BrokerDecision]:
        return list(self._decisions)

    @property
    def receipts(self) -> list[MessageReceipt]:
        return list(self._receipts.values())

    @property
    def messages(self) -> list[MessageEnvelope]:
        return list(self._messages.values())

    def set_proof_control_message_gate(
        self,
        gate: Callable[[MessageEnvelope, int], tuple[bool, str | None]] | None,
    ) -> None:
        """Attach an optional additive gate; legacy Broker policy remains authoritative."""

        self._proof_control_message_gate = gate

    def set_proof_control_broadcast_gate(
        self,
        gate: Callable[[MessageEnvelope, int], bool] | None,
    ) -> None:
        """Attach an optional delivery gate without rejecting local content."""

        self._proof_control_broadcast_gate = gate

    def utility_record(
        self, message_id: str, target_route_id: str
    ) -> dict[str, Any] | None:
        record = self._utility_records.get(delivery_key(message_id, target_route_id))
        return dict(record) if record is not None else None

    def delivery_record(
        self, message_id: str, target_route_id: str
    ) -> dict[str, Any] | None:
        delivery = self._deliveries.get(delivery_key(message_id, target_route_id))
        return dict(delivery) if delivery is not None else None

    def receipt_record(
        self,
        message_id: str,
        target_route_id: str,
    ) -> MessageReceipt | None:
        return self._receipts.get(delivery_key(message_id, target_route_id))

    def deliveries_requiring_route_update(self) -> list[dict[str, Any]]:
        high_value = {
            MessagePriority.CRITICAL.value,
            MessagePriority.HIGH.value,
        }
        return sorted(
            (
                dict(delivery)
                for delivery in self._deliveries.values()
                if delivery.get("status") == "pending"
                and delivery.get("priority") in high_value
                and int(delivery.get("processing_opportunities", 0)) == 0
                and not bool(delivery.get("prompt_consumed", False))
                and delivery.get("delivery_state", DeliveryState.QUEUED.value)
                == DeliveryState.QUEUED.value
            ),
            key=lambda item: (
                self._priority_rank(str(item.get("priority", "low"))),
                int(item.get("delivered_round", 0)),
                str(item.get("delivery_key", "")),
            ),
        )

    def schedule_route_update(
        self,
        message_id: str,
        target_route_id: str,
        *,
        action_id: str,
    ) -> dict[str, Any]:
        key = delivery_key(message_id, target_route_id)
        delivery = self._deliveries.get(key)
        if delivery is None:
            raise ValueError("route update does not correspond to a delivery")
        if delivery["status"] != "pending":
            raise ValueError("only a pending delivery can schedule a route update")
        if int(delivery.get("processing_opportunities", 0)) != 0:
            raise ValueError("delivery already had a natural processing opportunity")
        existing_action = delivery.get("route_update_action_id")
        if existing_action not in {None, action_id}:
            raise ValueError("delivery already belongs to another route update")
        delivery["route_update_action_id"] = action_id
        delivery["delivery_state"] = DeliveryState.SCHEDULED.value
        self._emit_delivery_event(
            "message_route_update_scheduled",
            {
                "message_id": message_id,
                "target_route_id": target_route_id,
                "action_id": action_id,
            },
        )
        return dict(delivery)

    def present_scheduled_route_update(
        self,
        message_id: str,
        target_route_id: str,
        *,
        action_id: str,
        current_round: int,
    ) -> MessageEnvelope:
        self.route_registry.get(target_route_id)
        key = delivery_key(message_id, target_route_id)
        delivery = self._deliveries.get(key)
        if delivery is None:
            raise ValueError("route update does not correspond to a delivery")
        if delivery.get("route_update_action_id") != action_id:
            raise ValueError("route update action does not own this delivery")
        if (
            delivery["status"] == "pending"
            and delivery["prompt_consumed"]
            and delivery.get("delivery_state") == DeliveryState.PRESENTED.value
        ):
            return self._messages[message_id]
        if delivery["status"] != "pending" or delivery["prompt_consumed"]:
            raise ValueError("route update delivery is no longer pending")
        if delivery.get("delivery_state") != DeliveryState.SCHEDULED.value:
            raise ValueError("delivery is not scheduled for a route update")
        self.expire(current_round)
        if delivery["status"] != "pending":
            raise ValueError("route update delivery expired before presentation")
        delivery["processing_opportunities"] = (
            int(delivery.get("processing_opportunities", 0)) + 1
        )
        delivery["prompt_consumed"] = True
        delivery["delivery_state"] = DeliveryState.PRESENTED.value
        message = self._messages[message_id]
        self._emit_delivery_event(
            "message_route_update_presented",
            {
                "message_id": message_id,
                "source_route_id": message.source_route_id,
                "target_route_id": target_route_id,
                "message_type": message.message_type.value,
                "memory_tier": message.memory_tier.value,
                "delivered_round": delivery["delivered_round"],
                "presented_round": current_round,
                "action_id": action_id,
            },
        )
        return message

    @staticmethod
    def _priority_rank(priority: str) -> int:
        return {
            MessagePriority.CRITICAL.value: 0,
            MessagePriority.HIGH.value: 1,
            MessagePriority.NORMAL.value: 2,
            MessagePriority.LOW.value: 3,
        }.get(priority, 3)

    def message_priority(self, message: MessageEnvelope) -> MessagePriority:
        if (
            message.evidence_type == EvidenceType.COUNTEREXAMPLE
            or message.message_type
            in {
                MessageType.COUNTEREXAMPLE,
                MessageType.CONTRADICTION_NOTICE,
            }
        ):
            return MessagePriority.CRITICAL
        if (
            message.memory_tier == MemoryTier.FACT
            and message.verification_status == ClaimStatus.VERIFIED
        ):
            return MessagePriority.HIGH
        high_centrality = False
        for dependency_id in message.dependencies:
            try:
                obligation = self.proof_graph.get_obligation(dependency_id)
            except KeyError:
                continue
            if obligation.centrality >= 0.7:
                high_centrality = True
                break
        if high_centrality and message.message_type in {
            MessageType.BRIDGE_LEMMA_REQUEST,
            MessageType.PROOF_OBLIGATION,
        }:
            return MessagePriority.HIGH
        if message.message_type in {
            MessageType.PROOF_OBLIGATION,
            MessageType.REPAIR_REQUEST,
            MessageType.BRIDGE_LEMMA_REQUEST,
            MessageType.STRATEGY_REWRITE_REQUEST,
        }:
            return MessagePriority.NORMAL
        return MessagePriority.LOW

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
        if message_id in self._invalidated_messages:
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

    def _priority_slot_available(
        self,
        message: MessageEnvelope,
        target_route_id: str,
        current_round: int,
    ) -> tuple[bool, str]:
        priority = self.message_priority(message)
        route_key = f"{current_round}:{target_route_id}"
        route_limit = self.config.topology.cross_route.max_messages_per_route_per_round

        def reserved_slots(
            counts: dict[str, int],
            *,
            key_prefix: str,
            limit: int,
        ) -> int:
            if priority in {MessagePriority.CRITICAL, MessagePriority.HIGH}:
                return 0
            high_value_missing = (
                counts[f"{key_prefix}:{MessagePriority.CRITICAL.value}"]
                + counts[f"{key_prefix}:{MessagePriority.HIGH.value}"]
                == 0
            )
            normal_missing = counts[f"{key_prefix}:{MessagePriority.NORMAL.value}"] == 0
            requested = int(high_value_missing)
            if priority == MessagePriority.LOW:
                requested += int(normal_missing)
            if requested == 0 or limit <= 0:
                return 0
            return min(requested, max(1, limit - 1))

        route_reserves = reserved_slots(
            self._round_route_priority_counts,
            key_prefix=route_key,
            limit=route_limit,
        )
        if self._round_route_counts[route_key] >= max(0, route_limit - route_reserves):
            return False, f"{priority.value} priority slot unavailable"

        global_limit = self.config.topology.cross_route.max_global_messages_per_round
        global_reserves = reserved_slots(
            self._round_global_priority_counts,
            key_prefix=str(current_round),
            limit=global_limit,
        )
        if self._round_global_counts[current_round] >= max(
            0,
            global_limit - global_reserves,
        ):
            return False, f"global {priority.value} priority slot unavailable"
        return True, ""

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
            broadcast_allowed = (
                self._proof_control_broadcast_gate is None
                or self._proof_control_broadcast_gate(existing, current_round)
            )
            candidate_targets = (
                list(message.target_route_ids)
                or self.route_registry.neighbors(
                    existing.source_route_id,
                    current_round,
                )
                if broadcast_allowed
                else []
            )
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
        if self._proof_control_message_gate is not None:
            allowed, reason = self._proof_control_message_gate(message, current_round)
            if not allowed:
                return self._reject(
                    message,
                    reason or "proof-control message admission rejected",
                )

        # 8-12: isolation, global-share gate, matching, and rate limits.
        explicit = list(dict.fromkeys(message.target_route_ids))
        if message.evidence_type == EvidenceType.COUNTEREXAMPLE:
            explicit.extend(
                self.typed_memory.affected_routes_for_counterexample(message)
            )
        broadcast_allowed = (
            self._proof_control_broadcast_gate is None
            or self._proof_control_broadcast_gate(message, current_round)
        )
        candidate_targets = (
            explicit
            or self.route_registry.neighbors(message.source_route_id, current_round)
            if broadcast_allowed
            else []
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
            slot_available, slot_reason = self._priority_slot_available(
                message,
                target,
                current_round,
            )
            if not slot_available:
                rejected[target] = slot_reason
                continue
            selected.append(target)

        self._messages[message.message_id] = message
        self._dedup[duplicate_key] = message.message_id
        self._invalidated_messages.pop(message.message_id, None)
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
        priority = self.message_priority(message)
        self._deliveries[key] = {
            "delivery_key": key,
            "message_id": message.message_id,
            "target_route_id": target_route_id,
            "delivered_round": current_round,
            "prompt_consumed": False,
            "acknowledged": False,
            "status": "pending",
            "delivery_state": DeliveryState.QUEUED.value,
            "priority": priority.value,
            "receipt_token": secrets.token_urlsafe(24),
            "processing_opportunities": 0,
        }
        if target_route_id not in message.target_route_ids:
            message.target_route_ids.append(target_route_id)
        self._route_queues[target_route_id].append(key)
        count_key = f"{current_round}:{target_route_id}"
        self._round_route_counts[count_key] += 1
        self._round_global_counts[current_round] += 1
        self._round_route_priority_counts[f"{count_key}:{priority.value}"] += 1
        self._round_global_priority_counts[f"{current_round}:{priority.value}"] += 1

    def _release_isolation(self, current_round: int) -> None:
        if current_round < self.config.topology.cross_route.initial_isolation_rounds:
            return
        for message_id, targets in list(self._isolation_pending.items()):
            message = self._messages.get(message_id)
            if message is None:
                continue
            deferred: list[str] = []
            for target in targets:
                slot_available, _reason = self._priority_slot_available(
                    message,
                    target,
                    current_round,
                )
                if not slot_available:
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
        self.expire(current_round)
        limit = max_messages
        if limit is None:
            limit = self.config.topology.cross_route.max_messages_per_route_per_round
        result: list[MessageEnvelope] = []
        queue = sorted(
            self._route_queues.get(route_id, []),
            key=lambda key: (
                self._priority_rank(str(self._deliveries[key].get("priority", "low"))),
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
            delivery["processing_opportunities"] = (
                int(delivery.get("processing_opportunities", 0)) + 1
            )
            delivery["prompt_consumed"] = True
            delivery["delivery_state"] = DeliveryState.PRESENTED.value
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
        delivery["delivery_state"] = DeliveryState.ACKNOWLEDGED.value
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
        delivery = self._deliveries.get(key)
        if delivery is not None:
            delivery["delivery_state"] = DeliveryState.USED.value
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
        if message is None or message_id in self._invalidated_messages:
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

    def invalidate_messages(
        self,
        message_ids: list[str],
        *,
        reason: str,
    ) -> list[str]:
        """Retain audit history while removing messages from live delivery."""

        invalidated = {
            message_id for message_id in message_ids if message_id in self._messages
        }
        if not invalidated:
            return []
        self._dedup = {
            key: message_id
            for key, message_id in self._dedup.items()
            if message_id not in invalidated
        }
        for message_id in invalidated:
            self._invalidated_messages[message_id] = reason
            self._isolation_pending.pop(message_id, None)
        invalidated_delivery_keys: list[str] = []
        for key, delivery in self._deliveries.items():
            if delivery.get("message_id") not in invalidated:
                continue
            archived = dict(delivery)
            archived["status"] = ReceiptStatus.REJECTED.value
            archived["delivery_state"] = DeliveryState.INVALIDATED.value
            archived["invalidation_reason"] = reason
            self._invalidated_deliveries[key] = archived
            invalidated_delivery_keys.append(key)
        for key in invalidated_delivery_keys:
            delivery = self._deliveries.pop(key)
            target_route_id = str(delivery.get("target_route_id", ""))
            if target_route_id in self._route_queues:
                self._route_queues[target_route_id] = [
                    queued_key
                    for queued_key in self._route_queues[target_route_id]
                    if queued_key != key
                ]
            self._receipts.pop(key, None)
            self._utility_records.pop(key, None)
        payload = {
            "message_ids": sorted(invalidated),
            "delivery_keys": sorted(invalidated_delivery_keys),
            "reason": reason,
        }
        self._emit_delivery_event("broker_messages_invalidated", payload)
        if self.store is not None:
            self.store.write_json("communication", "broker_state", self.export_state())
        return sorted(invalidated)

    def expire(self, current_round: int) -> list[str]:
        expired: list[str] = []
        for key, delivery in self._deliveries.items():
            if delivery["status"] != "pending":
                continue
            message = self._messages[delivery["message_id"]]
            opportunities = int(delivery.get("processing_opportunities", 0))
            no_opportunity_expired = (
                opportunities == 0
                and current_round - int(delivery.get("delivered_round", current_round))
                > message.ttl_rounds
            )
            if no_opportunity_expired or opportunities > message.ttl_rounds:
                delivery["status"] = ReceiptStatus.EXPIRED.value
                if no_opportunity_expired:
                    delivery["delivery_state"] = (
                        DeliveryState.EXPIRED_WITHOUT_OPPORTUNITY.value
                    )
                expired.append(key)
                self._emit_delivery_event(
                    "message_expired",
                    {
                        "message_id": message.message_id,
                        "target_route_id": delivery["target_route_id"],
                        "current_round": current_round,
                        "processing_opportunities": opportunities,
                        "delivery_state": delivery.get("delivery_state"),
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
            "invalidated_messages": dict(self._invalidated_messages),
            "invalidated_deliveries": dict(self._invalidated_deliveries),
            "isolation_pending": dict(self._isolation_pending),
            "round_route_counts": dict(self._round_route_counts),
            "round_global_counts": dict(self._round_global_counts),
            "round_route_priority_counts": dict(self._round_route_priority_counts),
            "round_global_priority_counts": dict(self._round_global_priority_counts),
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
        for delivery in broker._deliveries.values():
            message = broker._messages.get(str(delivery.get("message_id", "")))
            if message is not None:
                delivery.setdefault(
                    "priority",
                    broker.message_priority(message).value,
                )
            if "delivery_state" not in delivery:
                key = str(delivery.get("delivery_key", ""))
                if key in state.get("utility_records", {}):
                    delivery["delivery_state"] = DeliveryState.USED.value
                elif bool(delivery.get("acknowledged", False)):
                    delivery["delivery_state"] = DeliveryState.ACKNOWLEDGED.value
                elif bool(delivery.get("prompt_consumed", False)):
                    delivery["delivery_state"] = DeliveryState.PRESENTED.value
                else:
                    delivery["delivery_state"] = DeliveryState.QUEUED.value
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
        broker._invalidated_messages = {
            str(key): str(value)
            for key, value in dict(state.get("invalidated_messages", {})).items()
        }
        broker._invalidated_deliveries = {
            str(key): dict(value)
            for key, value in dict(state.get("invalidated_deliveries", {})).items()
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
        broker._round_route_priority_counts = defaultdict(
            int,
            {
                str(key): int(value)
                for key, value in dict(
                    state.get("round_route_priority_counts", {})
                ).items()
            },
        )
        broker._round_global_priority_counts = defaultdict(
            int,
            {
                str(key): int(value)
                for key, value in dict(
                    state.get("round_global_priority_counts", {})
                ).items()
            },
        )
        if not state.get("round_route_priority_counts"):
            for delivery in broker._deliveries.values():
                priority = str(delivery.get("priority", MessagePriority.LOW.value))
                route_key = (
                    f"{int(delivery.get('delivered_round', 0))}:"
                    f"{delivery.get('target_route_id', '')}"
                )
                broker._round_route_priority_counts[f"{route_key}:{priority}"] += 1
                broker._round_global_priority_counts[
                    f"{int(delivery.get('delivered_round', 0))}:{priority}"
                ] += 1
        return broker
