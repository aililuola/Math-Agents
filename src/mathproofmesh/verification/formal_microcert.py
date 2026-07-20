from __future__ import annotations

from typing import Protocol, runtime_checkable

from ..proof_graph.store import ProofGraphStore
from ..schemas import (
    FormalCertificateRef,
    FormalStatementPacket,
    ObligationKind,
    ProofObligation,
    stable_hash,
)


@runtime_checkable
class FormalVerifierBackend(Protocol):
    name: str

    def available(self) -> bool: ...

    def verify(self, packet: FormalStatementPacket) -> FormalCertificateRef: ...


class FormalizationCandidateSelector:
    """Spend formal budget only on shared/high-centrality/high-risk obligations."""

    def select(
        self,
        obligations: list[ProofObligation],
        *,
        max_candidates: int = 2,
    ) -> list[ProofObligation]:
        scored: list[tuple[float, ProofObligation]] = []
        for item in obligations:
            if item.status == "closed":
                continue
            quantifier_risk = min(1.0, len(item.quantifiers) / 3)
            shared = min(1.0, max(0, len(set(item.route_ids)) - 1) / 2)
            main = 1.0 if item.kind == ObligationKind.MAIN_GOAL else 0.0
            score = (
                0.4 * item.centrality
                + 0.25 * shared
                + 0.2 * quantifier_risk
                + 0.15 * main
            )
            if score >= 0.45:
                scored.append((score, item))
        scored.sort(key=lambda pair: (-pair[0], pair[1].obligation_id))
        return [item for _, item in scored[:max_candidates]]

    @staticmethod
    def packet(obligation: ProofObligation) -> FormalStatementPacket:
        return FormalStatementPacket(
            problem_hash=obligation.problem_hash,
            obligation_id=obligation.obligation_id,
            statement=obligation.statement,
            assumptions=obligation.assumptions,
            quantifiers=obligation.quantifiers,
        )


class CompilerFeedbackInterpreter:
    """Compiler failure creates a formalization obligation, not a mathematical refutation."""

    def unavailable(
        self, packet: FormalStatementPacket, *, backend_name: str
    ) -> FormalCertificateRef:
        return FormalCertificateRef(
            packet_id=packet.packet_id,
            backend=backend_name,
            status="pending",
            statement_hash=stable_hash(packet.statement),
            diagnostics=["formal verifier backend unavailable"],
        )

    def apply_failure(
        self,
        packet: FormalStatementPacket,
        certificate: FormalCertificateRef,
        graph: ProofGraphStore,
    ) -> ProofObligation | None:
        if certificate.status != "failed":
            return None
        source = graph.get_obligation(packet.obligation_id)
        return graph.add_obligation(
            ProofObligation(
                problem_hash=packet.problem_hash,
                route_ids=source.route_ids,
                kind=ObligationKind.FORMALIZATION_TASK,
                statement=(
                    f"Repair formalization of {packet.obligation_id}: "
                    f"{' | '.join(certificate.diagnostics)}"
                ),
                normalized_statement=f"formalize:{packet.obligation_id}",
                assumptions=packet.assumptions,
                quantifiers=packet.quantifiers,
                dependency_ids=[packet.obligation_id],
                status="open",
                priority=max(0.6, source.priority),
                centrality=source.centrality,
            )
        )
