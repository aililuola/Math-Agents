from __future__ import annotations

from collections.abc import Iterable, Sequence

from ..config import InductionControlConfig
from .models import InductionMeasureProposal


class InductionMeasureSelector:
    def __init__(self, config: InductionControlConfig | None = None) -> None:
        self.config = config or InductionControlConfig()

    def detect_trigger(
        self,
        *texts: str,
        near_miss_hints: Iterable[str] = (),
    ) -> list[str]:
        joined = " ".join([*texts, *near_miss_hints]).casefold()
        triggers: list[str] = []
        if self.config.trigger_on_first_occurrence_barrier and any(
            marker in joined
            for marker in (
                "first occurrence",
                "first appearance",
                "first time",
                "第一次出现",
            )
        ):
            triggers.append("first_occurrence_barrier")
        if self.config.trigger_on_recursive_same_type_dependency and any(
            marker in joined
            for marker in (
                "same type",
                "recursive object",
                "recurs on",
                "递归回到",
            )
        ):
            triggers.append("recursive_same_type_dependency")
        if self.config.trigger_on_repeated_feature and any(
            marker in joined
            for marker in (
                "repeated feature",
                "occurrence count",
                "number of occurrences",
                "support size",
                "重复性质",
                "出现次数",
            )
        ):
            triggers.append("repeated_feature")
        if any(
            marker in joined
            for marker in (
                "ordinary induction fails",
                "induction on n fails",
                "natural index is insufficient",
                "普通归纳",
            )
        ):
            triggers.append("natural_index_insufficient")
        return list(dict.fromkeys(triggers))

    def propose_candidates(
        self,
        *,
        route_id: str,
        target_obligation_ids: Sequence[str],
        trigger_features: Sequence[str],
        hints: Sequence[str] = (),
        source_record_ids: Sequence[str] = (),
        source_agent_id: str | None = None,
    ) -> list[InductionMeasureProposal]:
        joined = " ".join([*trigger_features, *hints]).casefold()
        candidates: list[tuple[str, str, str, str]] = []
        if any(
            marker in joined
            for marker in ("occurrence", "repeated_feature", "first_occurrence")
        ):
            candidates.append(
                (
                    "occurrence_count",
                    "nonnegative integers",
                    "zero occurrences and the first occurrence",
                    "remove the latest relevant occurrence, decreasing the count by one",
                )
            )
        if "support" in joined:
            candidates.append(
                (
                    "support_size",
                    "nonnegative integers",
                    "empty support",
                    "remove one supported component, decreasing support size",
                )
            )
        if any(marker in joined for marker in ("edge", "vertex", "graph")):
            candidates.append(
                (
                    "edge_vertex_lexicographic",
                    "N x N with lexicographic order",
                    "no edges and the minimal vertex cases",
                    "reduce the edge count, or preserve it and reduce vertices",
                )
            )
        if "conflict" in joined:
            candidates.append(
                (
                    "conflict_count",
                    "nonnegative integers",
                    "zero conflicts",
                    "resolve one certified conflict without creating a new one",
                )
            )
        if not candidates:
            candidates.append(
                (
                    "object_complexity",
                    "nonnegative integers",
                    "minimal-complexity objects",
                    "replace the object by a strictly lower-complexity predecessor",
                )
            )

        proposals: list[InductionMeasureProposal] = []
        for measure, domain, base, decrease in candidates[
            : self.config.max_candidates_per_trigger
        ]:
            proposal = InductionMeasureProposal(
                route_id=route_id,
                target_obligation_ids=list(target_obligation_ids),
                measure_name=measure,
                well_founded_domain=domain,
                base_cases=[base],
                induction_step_relation=(
                    f"prove the target from predecessors ordered by {measure}"
                ),
                strict_decrease_argument=decrease,
                why_natural_index_is_insufficient=(
                    "The dependency follows structural recurrence or feature "
                    "occurrences rather than the ambient natural index."
                ),
                trigger_features=list(trigger_features),
                source_record_ids=list(dict.fromkeys(source_record_ids)),
                source_agent_id=source_agent_id,
                confidence=0.85 if measure == "occurrence_count" else 0.70,
            )
            if self.validate_well_foundedness(proposal):
                proposals.append(proposal)
        return proposals

    def validate_well_foundedness(self, proposal: InductionMeasureProposal) -> bool:
        if not proposal.target_obligation_ids:
            return False
        if self.config.require_well_foundedness_statement and not (
            proposal.well_founded_domain
            and proposal.base_cases
            and proposal.strict_decrease_argument
        ):
            return False
        if not all(item.strip() for item in proposal.base_cases):
            return False
        lowered = proposal.well_founded_domain.casefold()
        well_founded_domain = any(
            marker in lowered
            for marker in (
                "nonnegative integer",
                "natural",
                "lexicographic",
                "well-founded",
                "well founded",
            )
        )
        decrease = proposal.strict_decrease_argument.casefold()
        explicitly_decreasing = any(
            marker in decrease
            for marker in (
                "decreas",
                "strictly lower",
                "strictly smaller",
                "reduce",
                "remove",
            )
        )
        circular = any(
            marker in decrease
            for marker in (
                "same complexity",
                "equal complexity",
                "larger object",
                "nondecreasing",
            )
        )
        renamed_index = proposal.measure_name.casefold() in {
            "n",
            "index",
            "natural_index",
            "ambient_index",
        }
        return (
            well_founded_domain
            and explicitly_decreasing
            and not circular
            and not renamed_index
        )

    def accept(self, proposal: InductionMeasureProposal) -> InductionMeasureProposal:
        if not self.validate_well_foundedness(proposal):
            raise ValueError("induction measure is not explicitly well founded")
        proposal.status = "accepted"
        return proposal
