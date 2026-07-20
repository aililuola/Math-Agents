from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable

from ..config import SystemConfig
from ..schemas import NoveltySignature, RouteDescriptor, RouteStatus


def normalize_math_text(value: str) -> str:
    value = value.casefold().strip()
    value = re.sub(r"\s+", " ", value)
    value = re.sub(r"[\.,;:!?，。；：！？]", "", value)
    return value


def token_set(value: str) -> set[str]:
    normalized = normalize_math_text(value)
    latin = set(re.findall(r"[a-z0-9_]+", normalized))
    cjk = "".join(re.findall(r"[\u3400-\u9fff]", normalized))
    return latin | {cjk[i : i + 2] for i in range(max(0, len(cjk) - 1))}


def jaccard(left: Iterable[str], right: Iterable[str]) -> float:
    a, b = set(left), set(right)
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def statement_similarity(left: str, right: str) -> float:
    if normalize_math_text(left) == normalize_math_text(right):
        return 1.0
    return jaccard(token_set(left), token_set(right))


def signature_similarity(left: NoveltySignature, right: NoveltySignature) -> float:
    dimensions = (
        (left.representation_tags, right.representation_tags),
        (left.mechanism_tags, right.mechanism_tags),
        (left.core_objects, right.core_objects),
        (left.key_transformations, right.key_transformations),
        (left.proof_principles, right.proof_principles),
        (left.targeted_obligation_ids, right.targeted_obligation_ids),
    )
    return sum(jaccard(a, b) for a, b in dimensions) / len(dimensions)


@dataclass(frozen=True, slots=True)
class DuplicateRouteMatch:
    source_route_id: str
    target_route_id: str
    similarity: float
    survivor_route_id: str
    reason: str


class DuplicateRouteDetector:
    """Detect mechanism duplication rather than prose-level paraphrase alone."""

    def __init__(self, config: SystemConfig) -> None:
        self.config = config

    def similarity(
        self,
        left: RouteDescriptor,
        right: RouteDescriptor,
        *,
        left_obligations: Iterable[str] = (),
        right_obligations: Iterable[str] = (),
        left_fact_ids: Iterable[str] = (),
        right_fact_ids: Iterable[str] = (),
    ) -> float:
        mechanism = jaccard(left.mechanism_signature, right.mechanism_signature)
        obligations = jaccard(left_obligations, right_obligations)
        facts = jaccard(left_fact_ids, right_fact_ids)
        # Mechanism agreement dominates so wording alone cannot merge routes.
        return 0.60 * mechanism + 0.25 * obligations + 0.15 * facts

    def detect(
        self,
        routes: Iterable[RouteDescriptor],
        *,
        obligations_by_route: dict[str, list[str]] | None = None,
        fact_ids_by_route: dict[str, list[str]] | None = None,
        progress_by_route: dict[str, float] | None = None,
    ) -> list[DuplicateRouteMatch]:
        obligations_by_route = obligations_by_route or {}
        fact_ids_by_route = fact_ids_by_route or {}
        progress_by_route = progress_by_route or {}
        active = [item for item in routes if item.status == RouteStatus.ACTIVE]
        matches: list[DuplicateRouteMatch] = []
        threshold = self.config.topology.broker.duplicate_strategy_threshold
        for index, left in enumerate(active):
            for right in active[index + 1 :]:
                score = self.similarity(
                    left,
                    right,
                    left_obligations=obligations_by_route.get(left.route_id, []),
                    right_obligations=obligations_by_route.get(right.route_id, []),
                    left_fact_ids=fact_ids_by_route.get(left.route_id, []),
                    right_fact_ids=fact_ids_by_route.get(right.route_id, []),
                )
                if score < threshold:
                    continue
                left_progress = progress_by_route.get(left.route_id, 0.0)
                right_progress = progress_by_route.get(right.route_id, 0.0)
                survivor = (
                    left.route_id if left_progress >= right_progress else right.route_id
                )
                matches.append(
                    DuplicateRouteMatch(
                        source_route_id=(
                            right.route_id
                            if survivor == left.route_id
                            else left.route_id
                        ),
                        target_route_id=survivor,
                        similarity=score,
                        survivor_route_id=survivor,
                        reason="mechanism, obligation, and fact overlap exceed threshold",
                    )
                )
        return matches
