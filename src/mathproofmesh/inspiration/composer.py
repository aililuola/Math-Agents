from __future__ import annotations

from collections.abc import Iterable
from itertools import combinations

from ..config import InspirationConfig
from ..proof_graph.store import ProofGraphStore
from ..schemas import (
    ComposedInspiration,
    InspirationProposal,
    InspirationReview,
    NoveltySignature,
    ProofObligation,
    stable_hash,
)


class InspirationComposer:
    """Combine independently reviewed, complementary ideas without proving them."""

    def __init__(self, config: InspirationConfig) -> None:
        self.config = config

    def compose(
        self,
        proposals: Iterable[InspirationProposal],
        reviews: Iterable[InspirationReview],
        *,
        quick_falsification_passed: set[str],
        proof_graph: ProofGraphStore,
        existing_composition_sources: set[tuple[str, ...]] | None = None,
    ) -> list[ComposedInspiration]:
        if (
            not self.config.inspiration_composer_enabled
            or self.config.composer_max_candidates_per_round == 0
        ):
            return []
        review_map = {item.proposal_id: item for item in reviews}
        eligible = [
            proposal
            for proposal in proposals
            if proposal.composition is None
            and proposal.proposal_id in review_map
            and review_map[proposal.proposal_id].recommendation != "reject"
            and review_map[proposal.proposal_id].internally_coherent
            and not review_map[proposal.proposal_id].immediate_counterexamples
        ]
        existing = existing_composition_sources or set()
        results: list[ComposedInspiration] = []
        maximum_sources = min(self.config.composer_max_sources, len(eligible))
        for source_count in range(2, maximum_sources + 1):
            for sources in combinations(eligible, source_count):
                source_ids = tuple(sorted(item.proposal_id for item in sources))
                if source_ids in existing:
                    continue
                if self.config.composer_require_quick_falsification and not (
                    set(source_ids) & quick_falsification_passed
                ):
                    continue
                estimated_cost = sum(item.estimated_cost for item in sources)
                if estimated_cost > self.config.composer_max_combined_cost:
                    continue
                targets = self._related_targets(sources, proof_graph)
                if not targets or not self._complementary(sources):
                    continue
                reviews_for_sources = [review_map[item.proposal_id] for item in sources]
                hidden = list(
                    dict.fromkeys(
                        condition
                        for review in reviews_for_sources
                        for condition in review.hidden_assumptions
                    )
                )
                compatibility = [
                    "all source proposals preserve the original scoped assumptions",
                    "each mechanism output satisfies the next mechanism input domain",
                    *[f"discharge hidden condition: {item}" for item in hidden],
                ]
                combined = list(
                    dict.fromkeys(
                        tag
                        for item in sources
                        for tag in (
                            item.mechanism.value,
                            *item.novelty_signature.representation_tags,
                            *item.novelty_signature.mechanism_tags,
                        )
                    )
                )
                bridge = self._bridge_statement(sources)
                fast_tests = list(
                    dict.fromkeys(
                        [
                            *(
                                test
                                for item in sources
                                for test in self._fast_tests(item)
                            ),
                            "check every source-to-target interface on the smallest admissible case",
                        ]
                    )
                )
                signature = NoveltySignature(
                    representation_tags=list(
                        dict.fromkeys(
                            tag
                            for item in sources
                            for tag in item.novelty_signature.representation_tags
                        )
                    ),
                    mechanism_tags=list(
                        dict.fromkeys(
                            [
                                "inspiration_composition",
                                *(
                                    tag
                                    for item in sources
                                    for tag in item.novelty_signature.mechanism_tags
                                ),
                            ]
                        )
                    ),
                    core_objects=list(
                        dict.fromkeys(
                            tag
                            for item in sources
                            for tag in item.novelty_signature.core_objects
                        )
                    ),
                    key_transformations=list(
                        dict.fromkeys(
                            [
                                *(
                                    tag
                                    for item in sources
                                    for tag in item.novelty_signature.key_transformations
                                ),
                                "compose_verified_interfaces",
                            ]
                        )
                    ),
                    proof_principles=list(
                        dict.fromkeys(
                            [
                                *(
                                    tag
                                    for item in sources
                                    for tag in item.novelty_signature.proof_principles
                                ),
                                "bridge_lemma",
                            ]
                        )
                    ),
                    targeted_obligation_ids=targets,
                )
                identifier = (
                    "composition_"
                    + stable_hash(
                        (source_ids, targets, signature.normalized_hash, bridge)
                    )[:16]
                )
                results.append(
                    ComposedInspiration(
                        composition_id=identifier,
                        source_proposal_ids=list(source_ids),
                        target_obligation_ids=targets,
                        compatibility_conditions=compatibility,
                        combined_mechanism=combined,
                        first_executable_step=bridge,
                        new_obligations=[bridge],
                        fast_failure_tests=fast_tests,
                        estimated_cost=estimated_cost,
                        novelty_signature=signature,
                    )
                )
                if len(results) >= self.config.composer_max_candidates_per_round:
                    return results
        return results

    @staticmethod
    def _related_targets(
        proposals: tuple[InspirationProposal, ...],
        graph: ProofGraphStore,
    ) -> list[str]:
        target_sets = [set(item.generated_obligations) for item in proposals]
        overlap = set.intersection(*target_sets)
        if overlap:
            return sorted(overlap)
        known = {item.obligation_id: item for item in graph.obligations}
        connected = {0}
        while True:
            expanded = set(connected)
            for left_index in connected:
                for right_index, right_targets in enumerate(target_sets):
                    if right_index in connected:
                        continue
                    if InspirationComposer._target_sets_adjacent(
                        target_sets[left_index], right_targets, known
                    ):
                        expanded.add(right_index)
            if expanded == connected:
                break
            connected = expanded
        if len(connected) != len(target_sets):
            return []
        return sorted(set().union(*target_sets))

    @staticmethod
    def _complementary(
        proposals: tuple[InspirationProposal, ...],
    ) -> bool:
        tag_sets = [
            {
                item.mechanism.value,
                *item.novelty_signature.representation_tags,
                *item.novelty_signature.mechanism_tags,
                *item.novelty_signature.key_transformations,
            }
            for item in proposals
        ]
        return all(
            bool(
                tags
                - set().union(
                    *(
                        other
                        for index, other in enumerate(tag_sets)
                        if index != source_index
                    )
                )
            )
            for source_index, tags in enumerate(tag_sets)
        )

    @staticmethod
    def _target_sets_adjacent(
        left_targets: set[str],
        right_targets: set[str],
        known: dict[str, ProofObligation],
    ) -> bool:
        if left_targets & right_targets:
            return True
        for left_id in left_targets:
            left_item = known.get(left_id)
            if left_item is None:
                continue
            for right_id in right_targets:
                right_item = known.get(right_id)
                if right_item is None:
                    continue
                if (
                    right_id in left_item.dependency_ids
                    or left_id in right_item.dependency_ids
                ):
                    return True
        return False

    @staticmethod
    def _bridge_statement(sources: tuple[InspirationProposal, ...]) -> str:
        interfaces = [
            f"'{left.statement}' -> '{right.statement}'"
            for left, right in zip(sources, sources[1:], strict=False)
        ]
        return "Prove the mechanism interfaces in sequence: " + "; ".join(interfaces)

    @staticmethod
    def _fast_tests(proposal: InspirationProposal) -> list[str]:
        if proposal.representation is not None:
            return list(proposal.representation.fast_failure_tests)
        if proposal.construction is not None:
            return list(proposal.construction.falsification_tests)
        if proposal.invariant is not None:
            return [proposal.invariant.falsification_request]
        if proposal.mutation is not None:
            return list(proposal.mutation.fast_failure_tests)
        if proposal.composition is not None:
            return list(proposal.composition.fast_failure_tests)
        return ["try the smallest nontrivial admissible case"]


__all__ = ["InspirationComposer"]
