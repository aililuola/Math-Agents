from __future__ import annotations

from collections.abc import Iterable, Sequence

from ..config import MessageUtilityControlConfig
from ..proof_graph.store import ProofGraphStore
from ..schemas import MessageEnvelope, MessagePriority, MessageType, stable_hash
from .models import (
    BroadcastDecision,
    BroadcastDecisionRecord,
    MessageExpectedEffect,
    MessageUsageReceipt,
    MessageUtilityContract,
)


class MessageUtilityController:
    """Separate delivery from locally verified mathematical use."""

    EXEMPT_MESSAGE_TYPES = {
        MessageType.COUNTEREXAMPLE,
        MessageType.CONTRADICTION_NOTICE,
    }

    def __init__(
        self,
        config: MessageUtilityControlConfig | None = None,
        *,
        proof_graph: ProofGraphStore | None = None,
        contracts: dict[str, MessageUtilityContract] | None = None,
        receipts: dict[str, MessageUsageReceipt] | None = None,
        broadcast_decisions: dict[str, BroadcastDecisionRecord] | None = None,
    ) -> None:
        self.config = config or MessageUtilityControlConfig()
        self.proof_graph = proof_graph
        self.contracts = contracts if contracts is not None else {}
        self.receipts = receipts if receipts is not None else {}
        self.broadcast_decisions = (
            broadcast_decisions if broadcast_decisions is not None else {}
        )
        self.expired_contract_ids: set[str] = set()

    def decide_broadcast(
        self,
        message: MessageEnvelope,
        *,
        contract: MessageUtilityContract | None,
        priority: MessagePriority,
        current_round: int,
    ) -> BroadcastDecisionRecord:
        del current_round
        existing = next(
            (
                item
                for item in self.broadcast_decisions.values()
                if item.message_id == message.message_id
            ),
            None,
        )
        if existing is not None:
            return existing

        exceptional = message.message_type in self.EXEMPT_MESSAGE_TYPES
        high_priority = priority in {
            MessagePriority.CRITICAL,
            MessagePriority.HIGH,
        }
        material_effect = bool(
            contract is not None
            and contract.expected_effect
            in {
                MessageExpectedEffect.CLOSE,
                MessageExpectedEffect.REFUTE,
            }
        )
        expected_reduction = (
            contract.expected_core_debt_reduction if contract is not None else 0.0
        )
        positive_utility = (
            expected_reduction > self.config.broadcast_min_expected_core_debt_reduction
        )

        if exceptional:
            decision = BroadcastDecision.BROADCAST
            reason = "critical_cross_route_control_evidence"
        elif high_priority:
            decision = BroadcastDecision.BROADCAST
            reason = "high_priority_cross_route_evidence"
        elif material_effect:
            decision = BroadcastDecision.BROADCAST
            reason = f"material_effect:{contract.expected_effect.value}"
        elif positive_utility:
            decision = BroadcastDecision.BROADCAST
            reason = "positive_expected_core_debt_reduction"
        else:
            decision = BroadcastDecision.KEEP_LOCAL
            reason = "zero_expected_cross_route_utility"

        decision_id = (
            "broadcast_decision_"
            + stable_hash(
                {
                    "message_id": message.message_id,
                    "content_hash": message.content_hash,
                    "contract_id": contract.contract_id if contract else None,
                    "priority": priority.value,
                }
            )[:20]
        )
        record = BroadcastDecisionRecord(
            decision_id=decision_id,
            message_id=message.message_id,
            decision=decision,
            reason=reason,
            priority=priority.value,
            expected_core_debt_reduction=expected_reduction,
            target_obligation_ids=(
                list(contract.target_obligation_ids) if contract is not None else []
            ),
            consumes_neighbor_quota=decision == BroadcastDecision.BROADCAST,
        )
        self.broadcast_decisions[record.decision_id] = record
        return record

    def register_contract(
        self,
        message: MessageEnvelope,
        *,
        target_obligation_ids: Sequence[str],
        expected_effect: MessageExpectedEffect,
        required_assumptions: Sequence[str] = (),
        expected_core_debt_reduction: float = 0.0,
        current_round: int,
    ) -> MessageUtilityContract:
        if len(target_obligation_ids) > self.config.max_target_obligations:
            raise ValueError("message utility contract has too many targets")
        contract = MessageUtilityContract(
            message_id=message.message_id,
            source_route_id=message.source_route_id,
            target_obligation_ids=list(dict.fromkeys(target_obligation_ids)),
            expected_effect=expected_effect,
            required_assumptions=list(required_assumptions),
            expected_core_debt_reduction=expected_core_debt_reduction,
            expires_round=current_round + self.config.utility_credit_horizon_rounds,
        )
        self.validate_contract(contract, message=message, current_round=current_round)
        self.contracts[contract.contract_id] = contract
        return contract

    def validate_contract(
        self,
        contract: MessageUtilityContract,
        *,
        message: MessageEnvelope,
        current_round: int,
    ) -> bool:
        if contract.message_id != message.message_id:
            raise ValueError("utility contract message mismatch")
        if contract.source_route_id != message.source_route_id:
            raise ValueError("utility contract source route mismatch")
        if contract.expires_round < current_round:
            raise ValueError("utility contract is already expired")
        if not contract.target_obligation_ids:
            raise ValueError("utility contract requires a target obligation")
        if self.proof_graph is not None:
            known = {item.obligation_id for item in self.proof_graph.obligations}
            missing = set(contract.target_obligation_ids) - known
            if missing:
                raise ValueError(
                    "utility contract references unknown obligations: "
                    + ", ".join(sorted(missing))
                )
        return True

    def contract_for_message(
        self, message_id: str, *, current_round: int | None = None
    ) -> MessageUtilityContract | None:
        candidates = [
            item
            for item in self.contracts.values()
            if item.message_id == message_id
            and item.contract_id not in self.expired_contract_ids
            and (current_round is None or item.expires_round >= current_round)
        ]
        return max(candidates, key=lambda item: item.expires_round, default=None)

    def requires_contract(self, message: MessageEnvelope) -> bool:
        return (
            self.config.require_utility_contract_for_cross_route
            and bool(message.target_route_ids)
            and message.message_type not in self.EXEMPT_MESSAGE_TYPES
        )

    def record_usage(
        self,
        *,
        message_id: str,
        consumer_route_id: str,
        referenced_step_ids: Sequence[str] = (),
        closed_obligation_ids: Sequence[str] = (),
        refuted_claim_ids: Sequence[str] = (),
        produced_message_ids: Sequence[str] = (),
        blueprint_rewrite_request_ids: Sequence[str] = (),
        cited_by_final_proof: bool = False,
        verified_step_ids: Iterable[str] = (),
        actually_closed_obligation_ids: Iterable[str] = (),
        actually_refuted_claim_ids: Iterable[str] = (),
        verified_produced_message_ids: Iterable[str] = (),
        executed_blueprint_rewrite_ids: Iterable[str] = (),
    ) -> MessageUsageReceipt:
        contract = self.contract_for_message(message_id)
        if contract is None:
            raise ValueError("verified usage requires an active utility contract")
        steps = sorted(set(referenced_step_ids) & set(verified_step_ids))
        obligations = sorted(
            set(closed_obligation_ids) & set(actually_closed_obligation_ids)
        )
        refutations = sorted(set(refuted_claim_ids) & set(actually_refuted_claim_ids))
        produced = sorted(
            set(produced_message_ids) & set(verified_produced_message_ids)
        )
        rewrites = sorted(
            set(blueprint_rewrite_request_ids) & set(executed_blueprint_rewrite_ids)
        )
        verified_use = bool(
            steps
            or obligations
            or refutations
            or produced
            or rewrites
            or cited_by_final_proof
        )
        score = (
            len(steps) * self.config.verified_step_credit
            + len(obligations) * self.config.obligation_close_credit
            + len(refutations) * self.config.refutation_credit
            + len(rewrites) * self.config.blueprint_rewrite_credit
            + len(produced) * self.config.verified_step_credit
            + (self.config.final_citation_credit if cited_by_final_proof else 0.0)
        )
        receipt = MessageUsageReceipt(
            message_id=message_id,
            consumer_route_id=consumer_route_id,
            referenced_step_ids=steps,
            closed_obligation_ids=obligations,
            refuted_claim_ids=refutations,
            produced_message_ids=produced,
            blueprint_rewrite_request_ids=rewrites,
            cited_by_final_proof=cited_by_final_proof,
            verified_use=verified_use,
            utility_score=score if verified_use else 0.0,
        )
        self.receipts[receipt.usage_receipt_id] = receipt
        return receipt

    def expire_contracts(self, current_round: int) -> list[str]:
        expired = sorted(
            item.contract_id
            for item in self.contracts.values()
            if item.expires_round < current_round
            and item.contract_id not in self.expired_contract_ids
        )
        self.expired_contract_ids.update(expired)
        return expired

    def route_utility(self, route_id: str) -> float:
        return sum(
            item.utility_score
            for item in self.receipts.values()
            if item.consumer_route_id == route_id and item.verified_use
        )

    def no_use_count(self, source_route_id: str) -> int:
        used_message_ids = {
            item.message_id for item in self.receipts.values() if item.verified_use
        }
        return sum(
            item.contract_id in self.expired_contract_ids
            and item.source_route_id == source_route_id
            and item.message_id not in used_message_ids
            for item in self.contracts.values()
        )
