from __future__ import annotations

from ..schemas import (
    MessageEnvelope,
    MessageReceipt,
    QuantifierSpec,
    ReceiptStatus,
    VariableBinding,
    stable_hash,
)


def build_receipt(
    message: MessageEnvelope,
    target_route_id: str,
    *,
    delivered_round: int,
    status: ReceiptStatus = ReceiptStatus.ACCEPTED,
    parsed_assumptions: list[str] | None = None,
    parsed_conclusion: str | None = None,
    parsed_quantifiers: list[QuantifierSpec] | None = None,
    parsed_variable_bindings: list[VariableBinding] | None = None,
    referenced_in_step_ids: list[str] | None = None,
    claimed_closed_obligation_ids: list[str] | None = None,
    reason: str = "",
) -> MessageReceipt:
    assumptions = (
        list(message.assumptions)
        if parsed_assumptions is None
        else list(parsed_assumptions)
    )
    conclusion = message.conclusion if parsed_conclusion is None else parsed_conclusion
    quantifiers = (
        list(message.quantifiers)
        if parsed_quantifiers is None
        else list(parsed_quantifiers)
    )
    variable_bindings = (
        list(message.variable_bindings)
        if parsed_variable_bindings is None
        else list(parsed_variable_bindings)
    )
    semantic_hash = stable_hash(
        {
            "assumptions": assumptions,
            "conclusion": conclusion,
            "quantifiers": [item.model_dump(mode="json") for item in quantifiers],
            "variable_bindings": [
                item.model_dump(mode="json") for item in variable_bindings
            ],
        }
    )
    return MessageReceipt(
        message_id=message.message_id,
        target_route_id=target_route_id,
        status=status,
        parsed_assumptions=assumptions,
        parsed_conclusion=conclusion,
        parsed_quantifiers=quantifiers,
        parsed_variable_bindings=variable_bindings,
        referenced_in_step_ids=list(referenced_in_step_ids or []),
        claimed_closed_obligation_ids=list(claimed_closed_obligation_ids or []),
        semantic_hash=semantic_hash,
        reason=reason,
        delivered_round=delivered_round,
    )
