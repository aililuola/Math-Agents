from __future__ import annotations

from collections.abc import Sequence

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
) -> list[str]:
    """Credit a message only when the accepted delta demonstrably cites or closes it."""
    messages = {message.message_id: message for message in delivered}
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
            proof_debt_before=proof_debt_before,
            proof_debt_after=debt_after,
        ):
            used.append(message.message_id)
    return used
