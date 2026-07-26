from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from ..config import InspirationConfig
from ..schemas import (
    InspirationProposal,
    InspirationReview,
    MechanismChainSignature,
    NoveltySignature,
)
from .ontology import MechanismNormalizer


def _jaccard(left: Iterable[str], right: Iterable[str]) -> float:
    a = {item.casefold().strip() for item in left if item.strip()}
    b = {item.casefold().strip() for item in right if item.strip()}
    if not a and not b:
        return 0.0
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
    mechanism_chain_similarity: float = 0.0


class NoveltyGate:
    """Mechanism-aware novelty metric; prose wording is deliberately absent."""

    def __init__(self, config: InspirationConfig) -> None:
        self.config = config
        self.normalizer = MechanismNormalizer()

    def similarity(
        self, left: NoveltySignature, right: NoveltySignature
    ) -> tuple[float, dict[str, float]]:
        left = self.normalizer.normalize_signature(left)
        right = self.normalizer.normalize_signature(right)
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
            "extension": _jaccard(left.extension_tags, right.extension_tags),
        }
        weights = {
            "representation": self.config.novelty_representation_weight,
            "mechanism": self.config.novelty_mechanism_weight,
            "object": self.config.novelty_object_weight,
            "transformation": self.config.novelty_transformation_weight,
            "principle": self.config.novelty_principle_weight,
            "obligation": self.config.novelty_obligation_weight,
            # Unrecognized extension tags are retained for audit and weak
            # similarity hints, but may not independently declare a duplicate.
            "extension": 0.05,
        }
        values = {
            "representation": (left.representation_tags, right.representation_tags),
            "mechanism": (left.mechanism_tags, right.mechanism_tags),
            "object": (left.core_objects, right.core_objects),
            "transformation": (
                left.key_transformations,
                right.key_transformations,
            ),
            "principle": (left.proof_principles, right.proof_principles),
            "obligation": (
                left.targeted_obligation_ids,
                right.targeted_obligation_ids,
            ),
            "extension": (left.extension_tags, right.extension_tags),
        }
        active = [name for name, pair in values.items() if pair[0] or pair[1]]
        total = sum(weights[name] for name in active)
        similarity = (
            sum(dimensions[name] * weights[name] for name in active) / total
            if total
            else 0.0
        )
        structural = {
            "representation",
            "mechanism",
            "object",
            "transformation",
            "principle",
        }
        if not structural.intersection(active):
            similarity = min(similarity, 0.5)
        return similarity, dimensions

    def assess(
        self,
        candidate: NoveltySignature,
        existing: Iterable[NoveltySignature],
    ) -> NoveltyAssessment:
        nearest_hash: str | None = None
        maximum = 0.0
        maximum_chain_similarity = 0.0
        duplicate = False
        nearest_dimensions: dict[str, float] = {}
        for other in existing:
            normalized_candidate = self.normalizer.normalize_signature(candidate)
            normalized_other = self.normalizer.normalize_signature(other)
            similarity, dimensions = self.similarity(
                normalized_candidate, normalized_other
            )
            chain_similarity = self._mechanism_chain_similarity(candidate, other)
            maximum_chain_similarity = max(
                maximum_chain_similarity,
                chain_similarity,
            )
            structurally_comparable = (
                self._shared_structural_dimensions(
                    normalized_candidate,
                    normalized_other,
                )
                >= 2
            )
            duplicate = duplicate or (
                chain_similarity >= self.config.mechanism_duplicate_threshold
                or (
                    structurally_comparable
                    and similarity >= self.config.mechanism_duplicate_threshold
                )
            )
            if similarity >= maximum:
                maximum = similarity
                nearest_hash = normalized_other.normalized_hash
                nearest_dimensions = dimensions
        novelty = 1.0 - maximum if nearest_hash is not None else 1.0
        return NoveltyAssessment(
            novelty_score=max(0.0, min(1.0, novelty)),
            maximum_similarity=maximum,
            duplicate=duplicate,
            nearest_hash=nearest_hash,
            dimension_similarities=nearest_dimensions,
            mechanism_chain_similarity=maximum_chain_similarity,
        )

    @staticmethod
    def _mechanism_chain_similarity(
        left: NoveltySignature,
        right: NoveltySignature,
    ) -> float:
        left_chain = MechanismChainSignature.from_novelty_signature(left)
        right_chain = MechanismChainSignature.from_novelty_signature(right)
        if not left_chain.complete or not right_chain.complete:
            return 0.0
        left_payload = left_chain.normalized_payload()
        right_payload = right_chain.normalized_payload()
        return (
            sum(
                _jaccard(left_payload[stage], right_payload[stage])
                for stage in (
                    "representation",
                    "transformations",
                    "bridge_pattern",
                    "terminal_argument",
                )
            )
            / 4.0
        )

    @staticmethod
    def _shared_structural_dimensions(
        left: NoveltySignature,
        right: NoveltySignature,
    ) -> int:
        return sum(
            bool(getattr(left, field_name)) and bool(getattr(right, field_name))
            for field_name in (
                "representation_tags",
                "mechanism_tags",
                "core_objects",
                "key_transformations",
                "proof_principles",
            )
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
