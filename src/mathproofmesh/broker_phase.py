from __future__ import annotations

from collections.abc import Mapping, Sequence

from .communication.broker import MessageBroker
from .proof_graph.store import ProofGraphStore
from .schemas import MessageEnvelope, MessageReceipt, ProofDelta, ReceiptStatus


def record_verified_message_usage(
    broker: MessageBroker,
    delivered: Sequence[MessageEnvelope],
    receipts: Sequence[MessageReceipt],
    delta: ProofDelta,
    *,
    route_id: str,
    proof_graph: ProofGraphStore | None,
    proof_debt_before: float | None,
    refuted_claim_ids_by_message: Mapping[str, Sequence[str]] | None = None,
    produced_message_ids_by_message: Mapping[str, Sequence[str]] | None = None,
    blueprint_rewrite_ids_by_message: Mapping[str, Sequence[str]] | None = None,
    final_cited_message_ids: Sequence[str] = (),
) -> list[str]:
    """Credit a message only when the accepted delta demonstrably cites or closes it."""
    messages = {message.message_id: message for message in delivered}
    refuted_claim_ids_by_message = refuted_claim_ids_by_message or {}
    produced_message_ids_by_message = produced_message_ids_by_message or {}
    blueprint_rewrite_ids_by_message = blueprint_rewrite_ids_by_message or {}
    step_by_id = {step.step_id: step for step in delta.new_steps}
    used: list[str] = []
    debt_after = proof_graph.proof_debt(route_id) if proof_graph is not None else None
    closed_ids = {
        item.obligation_id
        for item in (proof_graph.obligations if proof_graph is not None else [])
        if item.status == "closed" and route_id in item.route_ids
    }
    for receipt in receipts:
        if receipt.status != ReceiptStatus.ACCEPTED:
            continue
        message = messages.get(receipt.message_id)
        if message is None:
            continue
        valid_steps: list[str] = []
        for step_id in receipt.referenced_in_step_ids:
            step = step_by_id.get(step_id)
            if step is None:
                continue
            serialized = f"{step.statement}\n{step.justification}".casefold()
            if (
                message.message_id in step.dependencies
                or message.content_hash in step.dependencies
                or message.normalized_statement in serialized
                or message.conclusion.casefold() in serialized
            ):
                valid_steps.append(step_id)
        valid_obligations = sorted(
            set(receipt.claimed_closed_obligation_ids) & closed_ids
        )
        if broker.record_utility(
            message.message_id,
            route_id,
            referenced_step_ids=valid_steps,
            closed_obligation_ids=valid_obligations,
            refuted_claim_ids=list(
                refuted_claim_ids_by_message.get(message.message_id, ())
            ),
            produced_message_ids=list(
                produced_message_ids_by_message.get(message.message_id, ())
            ),
            blueprint_rewrite_request_ids=list(
                blueprint_rewrite_ids_by_message.get(message.message_id, ())
            ),
            cited_by_final_proof=message.message_id in set(final_cited_message_ids),
            proof_debt_before=proof_debt_before,
            proof_debt_after=debt_after,
        ):
            used.append(message.message_id)
    return used
