from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from ..config import InspirationConfig
from ..schemas import InspirationProposal, InspirationReview, NoveltySignature


def _jaccard(left: Iterable[str], right: Iterable[str]) -> float:
    a = {item.casefold().strip() for item in left if item.strip()}
    b = {item.casefold().strip() for item in right if item.strip()}
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


@dataclass(frozen=True, slots=True)
class NoveltyAssessment:
    novelty_score: float
    maximum_similarity: float
    duplicate: bool
    nearest_hash: str | None
    dimension_similarities: dict[str, float]


class NoveltyGate:
    """Mechanism-aware novelty metric; prose wording is deliberately absent."""

    def __init__(self, config: InspirationConfig) -> None:
        self.config = config

    def similarity(
        self, left: NoveltySignature, right: NoveltySignature
    ) -> tuple[float, dict[str, float]]:
        dimensions = {
            "representation": _jaccard(
                left.representation_tags, right.representation_tags
            ),
            "mechanism": _jaccard(left.mechanism_tags, right.mechanism_tags),
            "object": _jaccard(left.core_objects, right.core_objects),
            "transformation": _jaccard(
                left.key_transformations, right.key_transformations
            ),
            "principle": _jaccard(left.proof_principles, right.proof_principles),
            "obligation": _jaccard(
                left.targeted_obligation_ids, right.targeted_obligation_ids
            ),
        }
        weights = {
            "representation": self.config.novelty_representation_weight,
            "mechanism": self.config.novelty_mechanism_weight,
            "object": self.config.novelty_object_weight,
            "transformation": self.config.novelty_transformation_weight,
            "principle": self.config.novelty_principle_weight,
            "obligation": self.config.novelty_obligation_weight,
        }
        total = sum(weights.values())
        similarity = (
            sum(dimensions[name] * weights[name] for name in dimensions) / total
        )
        return similarity, dimensions

    def assess(
        self,
        candidate: NoveltySignature,
        existing: Iterable[NoveltySignature],
    ) -> NoveltyAssessment:
        nearest_hash: str | None = None
        maximum = 0.0
        nearest_dimensions: dict[str, float] = {}
        for other in existing:
            similarity, dimensions = self.similarity(candidate, other)
            if similarity >= maximum:
                maximum = similarity
                nearest_hash = other.normalized_hash
                nearest_dimensions = dimensions
        novelty = 1.0 - maximum if nearest_hash is not None else 1.0
        return NoveltyAssessment(
            novelty_score=max(0.0, min(1.0, novelty)),
            maximum_similarity=maximum,
            duplicate=maximum >= self.config.mechanism_duplicate_threshold,
            nearest_hash=nearest_hash,
            dimension_similarities=nearest_dimensions,
        )


class InspirationReferee:
    """Independent structural gate. It scores novelty, not mathematical truth."""

    def __init__(self, config: InspirationConfig) -> None:
        self.config = config

    def review(
        self,
        proposal: InspirationProposal,
        *,
        reviewer_agent_id: str,
        open_obligation_ids: Iterable[str],
        existing_signatures: Iterable[NoveltySignature] = (),
        immediate_counterexamples: Iterable[str] = (),
        hidden_assumptions: Iterable[str] = (),
    ) -> InspirationReview:
        if reviewer_agent_id == proposal.source_agent_id:
            raise ValueError("an inspiration author cannot referee its own proposal")
        assessment = NoveltyGate(self.config).assess(
            proposal.novelty_signature, existing_signatures
        )
        targets = set(proposal.novelty_signature.targeted_obligation_ids)
        targets.update(proposal.generated_obligations)
        relevant = bool(targets & set(open_obligation_ids))
        counterexamples = list(immediate_counterexamples)
        assumptions = list(hidden_assumptions)
        coherent = bool(proposal.statement.strip()) and not counterexamples
        distinct = (
            not assessment.duplicate
            and proposal.novelty_score >= self.config.novelty_threshold
        )
        if counterexamples or not coherent or not relevant or not distinct:
            recommendation = "reject"
        elif proposal.estimated_cost == 0:
            recommendation = "store_insight"
        elif proposal.target_route_ids:
            recommendation = "attach_to_existing_route"
        else:
            recommendation = "create_new_route"
        confidence = 0.25
        confidence += 0.25 if distinct else 0.0
        confidence += 0.25 if relevant else 0.0
        confidence += 0.25 if coherent else 0.0
        return InspirationReview(
            proposal_id=proposal.proposal_id,
            reviewer_agent_id=reviewer_agent_id,
            semantically_distinct=distinct,
            relevant_to_open_obligation=relevant,
            internally_coherent=coherent,
            hidden_assumptions=assumptions,
            immediate_counterexamples=counterexamples,
            recommendation=recommendation,
            confidence=confidence,
        )
