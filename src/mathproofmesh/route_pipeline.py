from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from .communication.broker import MessageBroker
from .config import SystemConfig
from .memory import TypedMemory
from .proof_graph.store import ProofGraphStore
from .schemas import (
    MessageEnvelope,
    MessageReceipt,
    ReceiptStatus,
    stable_hash,
)


def build_route_prompt_context(
    config: SystemConfig,
    *,
    route_id: str,
    current_round: int,
    broker: MessageBroker,
    typed_memory: TypedMemory,
    proof_graph: ProofGraphStore | None,
) -> tuple[list[MessageEnvelope], dict[str, Any]]:
    delivered = broker.inbox(route_id, current_round=current_round)
    open_obligations = (
        [
            obligation
            for obligation in proof_graph.obligations
            if obligation.status not in {"closed", "refuted"}
            and (not obligation.route_ids or route_id in obligation.route_ids)
        ]
        if proof_graph is not None
        else []
    )
    receipt_requirements = []
    for message in delivered:
        delivery = broker.delivery_record(message.message_id, route_id) or {}
        receipt_requirements.append(
            {
                "message_id": message.message_id,
                "target_route_id": route_id,
                "delivered_round": int(delivery.get("delivered_round", current_round)),
            }
        )
    return delivered, {
        "route_id": route_id,
        "broker_messages": delivered,
        "message_receipt_requirements": receipt_requirements,
        "fact_inbox": typed_memory.facts_for_route(route_id),
        "insight_hints": typed_memory.insights_for_route(route_id),
        "negative_memory": typed_memory.negatives_for_route(route_id),
        "open_obligations": open_obligations,
        "receipt_required": config.topology.typed_communication.require_receipt,
    }


def acknowledge_route_messages(
    broker: MessageBroker,
    delivered: Sequence[MessageEnvelope],
    receipts: Sequence[MessageReceipt],
    *,
    route_id: str,
    current_round: int,
) -> list[MessageReceipt]:
    candidates = {receipt.message_id: receipt for receipt in receipts}
    acknowledged: list[MessageReceipt] = []
    for message in delivered:
        candidate = candidates.get(message.message_id)
        delivery = broker.delivery_record(message.message_id, route_id) or {}
        delivered_round = int(delivery.get("delivered_round", current_round))
        if candidate is None:
            receipt = MessageReceipt(
                message_id=message.message_id,
                target_route_id=route_id,
                status=ReceiptStatus.REJECTED,
                reason="target route omitted the required semantic receipt",
                delivered_round=delivered_round,
            )
        else:
            parsed_hash = stable_hash(
                {
                    "assumptions": candidate.parsed_assumptions,
                    "conclusion": candidate.parsed_conclusion,
                    "quantifiers": [
                        item.model_dump(mode="json")
                        for item in candidate.parsed_quantifiers
                    ],
                    "variable_bindings": [
                        item.model_dump(mode="json")
                        for item in candidate.parsed_variable_bindings
                    ],
                }
            )
            receipt = MessageReceipt(
                receipt_id=candidate.receipt_id,
                message_id=message.message_id,
                target_route_id=route_id,
                status=candidate.status,
                parsed_assumptions=candidate.parsed_assumptions,
                parsed_conclusion=candidate.parsed_conclusion,
                parsed_quantifiers=candidate.parsed_quantifiers,
                parsed_variable_bindings=candidate.parsed_variable_bindings,
                referenced_in_step_ids=candidate.referenced_in_step_ids,
                claimed_closed_obligation_ids=(candidate.claimed_closed_obligation_ids),
                semantic_hash=parsed_hash,
                reason=candidate.reason,
                delivered_round=delivered_round,
                acknowledged_at=candidate.acknowledged_at,
            )
        broker.acknowledge(receipt)
        acknowledged.append(receipt)
    return acknowledged
