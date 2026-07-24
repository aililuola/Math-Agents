from __future__ import annotations

from typing import Iterable

from ..schemas import (
    InvariantHypothesis,
    NoveltySignature,
    ProblemContract,
    ProofObligation,
    stable_hash,
)


class InvariantHypothesisAgent:
    """Suggest falsifiable invariants without declaring them established."""

    def propose(
        self,
        problem: ProblemContract,
        obligations: Iterable[ProofObligation],
        *,
        domain: str = "unknown",
        max_proposals: int = 2,
    ) -> list[InvariantHypothesis]:
        targets = [
            item.obligation_id
            for item in obligations
            if item.status in {"open", "tentative", "blocked"}
        ]
        if not targets:
            return []
        templates = self._templates(domain)
        results: list[InvariantHypothesis] = []
        for index, (expression, behavior, principle) in enumerate(
            templates[:max_proposals]
        ):
            signature = NoveltySignature(
                mechanism_tags=["invariant_hypothesis", principle],
                core_objects=["state", "allowed_operation"],
                key_transformations=["compare_before_after"],
                proof_principles=[principle],
                targeted_obligation_ids=targets,
            )
            digest = stable_hash((problem.integrity_hash, expression, tuple(targets)))
            results.append(
                InvariantHypothesis(
                    hypothesis_id=f"invariant_{digest[:12]}",
                    target_obligation_ids=targets,
                    state_definition=(
                        "A state is a valid intermediate object under the original "
                        "problem constraints."
                    ),
                    allowed_operations=[
                        "one explicitly legal transformation from the problem"
                    ],
                    candidate_expression=expression,
                    behavior=behavior,  # type: ignore[arg-type]
                    boundary_case=(
                        "the smallest nontrivial valid state, not an empty/degenerate case"
                    ),
                    boundary_result=(
                        f"Evaluate {expression} directly before trusting the hypothesis"
                    ),
                    falsification_request=(
                        "Ask an independent skeptic or exact typed tool to find one "
                        "legal operation violating the claimed behavior."
                    ),
                    novelty_signature=signature,
                )
            )
        return results

    @staticmethod
    def _templates(domain: str) -> list[tuple[str, str, str]]:
        if domain.casefold() == "number_theory":
            return [
                ("valuation profile modulo the operation", "invariant", "valuation"),
                ("sum of normalized residues", "nonincreasing", "modular_potential"),
            ]
        if domain.casefold() == "combinatorics":
            return [
                ("number of unresolved incidences", "nonincreasing", "potential"),
                ("parity of the cut size", "invariant", "parity"),
            ]
        if domain.casefold() == "geometry":
            return [
                ("directed-angle relation", "invariant", "directed_angles"),
                (
                    "signed power with respect to the auxiliary circle",
                    "invariant",
                    "power",
                ),
            ]
        return [
            (
                "degree/valuation profile of the transformed expression",
                "invariant",
                "degree",
            ),
            ("number of unresolved terms", "nonincreasing", "potential"),
        ]

    @staticmethod
    def validate(hypothesis: InvariantHypothesis) -> list[str]:
        errors: list[str] = []
        if not hypothesis.state_definition or not hypothesis.allowed_operations:
            errors.append("state or operation is undefined")
        if not hypothesis.boundary_case or not hypothesis.boundary_result:
            errors.append("nontrivial boundary check is missing")
        if not hypothesis.falsification_request:
            errors.append("independent falsification was not requested")
        return errors
