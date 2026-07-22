from __future__ import annotations

from typing import Iterable

from ..config import InspirationConfig
from ..proof_graph.store import ProofGraphStore
from ..schemas import (
    InspirationMechanism,
    InspirationOutcome,
    InspirationProposal,
    InspirationReview,
    MessageEnvelope,
    NegativeAnalogyRecord,
    ProblemContract,
    VerifiedExperienceRecord,
    stable_hash,
)


class VerifiedExperienceDistiller:
    """Extract reusable structure only after the ordinary Fact gate succeeds."""

    def __init__(self, config: InspirationConfig) -> None:
        self.config = config

    def distill_verified(
        self,
        *,
        problem: ProblemContract,
        proposal: InspirationProposal,
        fact: MessageEnvelope,
        outcome: InspirationOutcome,
        proof_graph: ProofGraphStore,
    ) -> VerifiedExperienceRecord | None:
        if not self.config.experience_distillation_enabled:
            return None
        obligations = []
        known = {item.obligation_id: item for item in proof_graph.obligations}
        for obligation_id in proposal.generated_obligations:
            item = known.get(obligation_id)
            if item is not None:
                obligations.append(item)
        signature = proposal.novelty_signature
        objects = list(dict.fromkeys(signature.core_objects))
        operations = list(dict.fromkeys(signature.key_transformations))
        object_map = {item: item for item in objects} or {"target": "target"}
        operation_map = {item: item for item in operations} or {
            "verified_step": "verified_step"
        }
        construction = ""
        if proposal.construction is not None:
            construction = proposal.construction.definition
        elif proposal.representation is not None:
            construction = proposal.representation.rewritten_problem_view
        elif proposal.invariant is not None:
            construction = proposal.invariant.state_definition
        elif proposal.mutation is not None:
            construction = proposal.mutation.transformation
        elif proposal.composition is not None:
            construction = proposal.composition.first_executable_step
        elif (
            proposal.reverse_goal is not None and proposal.reverse_goal.frontier_bridges
        ):
            construction = proposal.reverse_goal.frontier_bridges[0].missing_implication
        graph_motif = [
            f"{item.kind.value}:{item.status}:deps={len(item.dependency_ids)}"
            for item in obligations
        ]
        mechanism_chain = list(
            dict.fromkeys(
                [
                    proposal.mechanism.value,
                    *(
                        proposal.composition.combined_mechanism
                        if proposal.composition is not None
                        else []
                    ),
                    *(
                        [proposal.mutation.operator_id]
                        if proposal.mutation is not None
                        else []
                    ),
                    *signature.representation_tags,
                    *signature.mechanism_tags,
                    *signature.key_transformations,
                    *signature.proof_principles,
                ]
            )
        )
        record_id = (
            "experience_"
            + stable_hash(
                (problem.integrity_hash, proposal.proposal_id, fact.message_id)
            )[:16]
        )
        limitations = list(
            dict.fromkeys(
                [
                    *fact.scope_limitations,
                    *(
                        proposal.mutation.known_failure_modes
                        if proposal.mutation is not None
                        else []
                    ),
                    *(
                        proposal.composition.compatibility_conditions
                        if proposal.composition is not None
                        else []
                    ),
                ]
                or ["transfer requires independent verification in the target problem"]
            )
        )
        return VerifiedExperienceRecord(
            record_id=record_id,
            source_proposal_id=proposal.proposal_id,
            problem_hash=problem.integrity_hash,
            problem_skeleton=problem.normalized_statement,
            obligation_graph_motif=graph_motif,
            obligation_kinds=[item.kind.value for item in obligations],
            mechanism_chain=mechanism_chain,
            key_construction=construction,
            transferable_lemmas=[fact.statement],
            non_transferable_conditions=limitations,
            negative_transfer_examples=[],
            object_correspondence=object_map,
            operation_correspondence=operation_map,
            required_bridge_lemmas=[
                item.statement for item in obligations if item.status != "closed"
            ],
            representation_tags=signature.representation_tags,
            mechanism_tags=signature.mechanism_tags,
            object_tags=objects,
            operation_tags=operations,
            graph_tags=graph_motif,
            proof_principles=signature.proof_principles,
            proof_summary=proposal.rationale_summary,
            problem_summary=problem.normalized_statement,
            transfer_risks=limitations,
            cited_by_final_proof=outcome.cited_by_final_proof,
        )

    def distill_negative_analogy(
        self,
        *,
        problem: ProblemContract,
        proposal: InspirationProposal,
        review: InspirationReview,
        round_index: int,
    ) -> NegativeAnalogyRecord | None:
        if (
            not self.config.negative_analogy_library_enabled
            or proposal.mechanism != InspirationMechanism.STRUCTURAL_ANALOGY
            or review.recommendation != "reject"
        ):
            return None
        conditions = list(
            dict.fromkeys(
                [
                    *review.hidden_assumptions,
                    *review.immediate_counterexamples,
                ]
            )
        )
        reason = "; ".join(conditions) or (
            "analogy failed novelty, relevance, or coherence review"
        )
        source_record_id = (
            proposal.analogy.source_record_id if proposal.analogy is not None else None
        )
        return NegativeAnalogyRecord(
            record_id="negative_analogy_"
            + stable_hash((problem.integrity_hash, proposal.proposal_id, reason))[:16],
            proposal_id=proposal.proposal_id,
            source_record_id=source_record_id,
            problem_hash=problem.integrity_hash,
            mechanism=proposal.mechanism,
            failure_reason=reason,
            distinguishing_conditions=conditions,
            round_index=round_index,
        )


def experience_payloads(
    records: Iterable[VerifiedExperienceRecord],
) -> list[dict[str, object]]:
    return [record.model_dump(mode="json") for record in records]


__all__ = ["VerifiedExperienceDistiller", "experience_payloads"]
