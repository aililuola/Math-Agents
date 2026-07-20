from __future__ import annotations

from ..schemas import MessageEnvelope, MessageReceipt, ReceiptStatus


def build_receipt(
    message: MessageEnvelope,
    target_route_id: str,
    *,
    delivered_round: int,
    status: ReceiptStatus = ReceiptStatus.ACCEPTED,
    parsed_assumptions: list[str] | None = None,
    parsed_conclusion: str | None = None,
    reason: str = "",
) -> MessageReceipt:
    assumptions = (
        list(message.assumptions)
        if parsed_assumptions is None
        else list(parsed_assumptions)
    )
    conclusion = message.conclusion if parsed_conclusion is None else parsed_conclusion
    semantic_hash = message.expected_semantic_hash()
    if assumptions != message.assumptions or conclusion != message.conclusion:
        semantic_hash = "mismatch"
    return MessageReceipt(
        message_id=message.message_id,
        target_route_id=target_route_id,
        status=status,
        parsed_assumptions=assumptions,
        parsed_conclusion=conclusion,
        semantic_hash=semantic_hash,
        reason=reason,
        delivered_round=delivered_round,
    )
