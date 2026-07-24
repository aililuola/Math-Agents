from __future__ import annotations

from typing import Iterable, Literal

from pydantic import Field

from ..config import SystemConfig
from ..schemas import (
    ClaimStatus,
    EvidenceType,
    MessageEnvelope,
    ObligationKind,
    ProofObligation,
    StrictModel,
    new_id,
)
from .store import ProofGraphStore


class ContradictionRecord(StrictModel):
    contradiction_id: str = Field(default_factory=lambda: new_id("conflict"))
    message_ids: list[str]
    route_ids: list[str]
    normalized_statement: str
    reason: str
    status: Literal["open", "resolved"] = "open"
    resolution_message_id: str | None = None
    centrality: float = Field(default=0.0, ge=0.0, le=1.0)


def _scope_signature(message: MessageEnvelope) -> tuple[object, ...]:
    return (
        tuple(message.assumptions),
        tuple(item.model_dump_json() for item in message.quantifiers),
        tuple(message.scope_limitations),
    )


class ContradictionBroker:
    """Detect exact or scope-compatible conflicts; never vote them away."""

    def __init__(self, config: SystemConfig, proof_graph: ProofGraphStore) -> None:
        self.config = config
        self.proof_graph = proof_graph
        self.records: list[ContradictionRecord] = []
        self._seen_pairs: set[tuple[str, str]] = set()

    def detect(
        self,
        messages: Iterable[MessageEnvelope] | None = None,
        *,
        current_round: int,
    ) -> list[ContradictionRecord]:
        del current_round
        if not self.config.topology.broker.contradiction_detection:
            return []
        candidates = list(messages or self.proof_graph.claim_nodes)
        cap = self.config.topology.broker.max_conflict_tasks_per_round
        created: list[ContradictionRecord] = []
        for index, left in enumerate(candidates):
            for right in candidates[index + 1 :]:
                pair = tuple(sorted((left.message_id, right.message_id)))
                if pair in self._seen_pairs:
                    continue
                reason = self._conflict_reason(left, right)
                if reason is None:
                    continue
                self._seen_pairs.add(pair)
                exact_counterexamples = [
                    item
                    for item in (left, right)
                    if item.evidence_type == EvidenceType.COUNTEREXAMPLE
                ]
                record = ContradictionRecord(
                    message_ids=list(pair),
                    route_ids=sorted(
                        {
                            left.source_route_id,
                            right.source_route_id,
                            *left.target_route_ids,
                            *right.target_route_ids,
                        }
                    ),
                    normalized_statement=left.normalized_statement,
                    reason=reason,
                    status="resolved" if exact_counterexamples else "open",
                    resolution_message_id=(
                        exact_counterexamples[0].message_id
                        if exact_counterexamples
                        else None
                    ),
                    centrality=self._matching_centrality(left.normalized_statement),
                )
                self.records.append(record)
                created.append(record)
                self.proof_graph.record_event("contradiction_detected", record)
                if record.status == "open":
                    self._block_related_obligations(record)
                if len(created) >= cap:
                    return created
        return created

    @staticmethod
    def _conflict_reason(left: MessageEnvelope, right: MessageEnvelope) -> str | None:
        # Differing scope or quantifier order is a diagnostic, not automatically a
        # contradiction. Only comparable scopes can conflict.
        if _scope_signature(left) != _scope_signature(right):
            return None
        same_statement = left.normalized_statement == right.normalized_statement
        if same_statement and {
            left.verification_status,
            right.verification_status,
        } == {ClaimStatus.VERIFIED, ClaimStatus.REJECTED}:
            return "the same scoped statement is both verified and rejected"
        if same_statement and any(
            item.evidence_type == EvidenceType.COUNTEREXAMPLE for item in (left, right)
        ):
            return "an exact counterexample refutes the scoped statement"
        if (
            left.conclusion == f"not ({right.conclusion})"
            or right.conclusion == f"not ({left.conclusion})"
        ):
            return "mutually exclusive conclusions under identical assumptions"
        return None

    def _matching_centrality(self, normalized_statement: str) -> float:
        values = [
            item.centrality
            for item in self.proof_graph.obligations
            if item.normalized_statement == normalized_statement
        ]
        return max(values, default=0.0)

    def _block_related_obligations(self, record: ContradictionRecord) -> None:
        for item in self.proof_graph.obligations:
            if item.normalized_statement != record.normalized_statement:
                continue
            if item.status != "refuted":
                item.status = "blocked"
        self.proof_graph.add_obligation(
            ProofObligation(
                problem_hash=self.proof_graph.problem_hash,
                route_ids=record.route_ids,
                kind=ObligationKind.CONTRADICTION,
                statement=f"Resolve contradiction: {record.normalized_statement}",
                normalized_statement=f"resolve:{record.normalized_statement}",
                status="open",
                priority=max(0.8, record.centrality),
                centrality=record.centrality,
            )
        )

    def resolve(
        self, contradiction_id: str, *, resolution_message_id: str
    ) -> ContradictionRecord:
        record = next(
            item for item in self.records if item.contradiction_id == contradiction_id
        )
        resolution = next(
            (
                item
                for item in self.proof_graph.claim_nodes
                if item.message_id == resolution_message_id
            ),
            None,
        )
        if resolution is None:
            raise ValueError("resolution must be a graph evidence message")
        if (
            resolution.message_type.value != "contradiction_notice"
            or resolution.verification_status != ClaimStatus.VERIFIED
        ):
            raise ValueError(
                "contradictions require an independently reviewed resolution"
            )
        record.status = "resolved"
        record.resolution_message_id = resolution_message_id
        for item in self.proof_graph.obligations:
            if (
                item.normalized_statement == record.normalized_statement
                and item.status == "blocked"
            ):
                item.status = "open"
        self.proof_graph.record_event("contradiction_resolved", record)
        return record

    def unresolved(self) -> list[ContradictionRecord]:
        return [item for item in self.records if item.status == "open"]

    def export_state(self) -> dict[str, object]:
        return {
            "records": [item.model_dump(mode="json") for item in self.records],
            "seen_pairs": [list(item) for item in sorted(self._seen_pairs)],
        }

    @classmethod
    def from_state(
        cls,
        state: dict[str, object],
        *,
        config: SystemConfig,
        proof_graph: ProofGraphStore,
    ) -> "ContradictionBroker":
        broker = cls(config, proof_graph)
        broker.records = [
            ContradictionRecord.model_validate(item)
            for item in state.get("records", [])  # type: ignore[arg-type]
        ]
        broker._seen_pairs = {
            tuple(sorted(str(value) for value in item))
            for item in state.get("seen_pairs", [])  # type: ignore[union-attr]
        }
        return broker
