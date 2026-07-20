from __future__ import annotations

from typing import Iterable

from ..schemas import (
    NoveltySignature,
    ProblemContract,
    ProofObligation,
    RepresentationCandidate,
    stable_hash,
)


REPRESENTATION_RULES: dict[str, tuple[str, ...]] = {
    "number_theory": (
        "modular_congruence",
        "p_adic_valuation",
        "recurrence_finite_state",
        "extremal_minimal_counterexample",
        "invariant_monovariant",
    ),
    "combinatorics": (
        "graph_hypergraph",
        "extremal_minimal_counterexample",
        "invariant_monovariant",
        "double_counting",
        "generating_function",
        "probabilistic_method",
        "linear_algebra_polynomial",
    ),
    "algebra": (
        "direct_algebra",
        "linear_algebra_polynomial",
        "generating_function",
        "extremal_minimal_counterexample",
        "invariant_monovariant",
    ),
    "inequalities": (
        "direct_algebra",
        "extremal_minimal_counterexample",
        "linear_algebra_polynomial",
        "probabilistic_method",
    ),
    "geometry": (
        "synthetic_geometry",
        "coordinate_geometry",
        "complex_plane",
        "inversion_projective",
        "linear_algebra_polynomial",
    ),
    "logic": (
        "graph_hypergraph",
        "recurrence_finite_state",
        "extremal_minimal_counterexample",
    ),
}


class RepresentationSwitchboard:
    """Select applicable mathematical representations and expose their losses."""

    def applicable_representations(
        self,
        problem: ProblemContract,
        *,
        domain: str = "unknown",
        existing_signatures: Iterable[NoveltySignature] = (),
    ) -> list[str]:
        text = f"{problem.normalized_statement} {problem.exact_statement}".casefold()
        inferred = domain.casefold()
        if inferred not in REPRESENTATION_RULES:
            if any(token in text for token in ("prime", "integer", "divis", "mod")):
                inferred = "number_theory"
            elif any(token in text for token in ("graph", "subset", "color", "排列")):
                inferred = "combinatorics"
            elif any(
                token in text for token in ("triangle", "circle", "angle", "几何")
            ):
                inferred = "geometry"
            elif any(token in text for token in ("inequality", "≥", "≤", "不等式")):
                inferred = "inequalities"
            else:
                inferred = "algebra"
        used = {
            tag
            for signature in existing_signatures
            for tag in signature.representation_tags
        }
        available = [
            item for item in REPRESENTATION_RULES[inferred] if item not in used
        ]
        return available or list(REPRESENTATION_RULES[inferred])

    def generate(
        self,
        problem: ProblemContract,
        obligations: Iterable[ProofObligation],
        *,
        domain: str = "unknown",
        existing_signatures: Iterable[NoveltySignature] = (),
        max_candidates: int = 3,
    ) -> list[RepresentationCandidate]:
        targets = [
            item
            for item in obligations
            if item.status in {"open", "tentative", "blocked"}
        ]
        if not targets:
            return []
        names = self.applicable_representations(
            problem, domain=domain, existing_signatures=existing_signatures
        )[:max_candidates]
        candidates: list[RepresentationCandidate] = []
        for name in names:
            signature = NoveltySignature(
                representation_tags=[name],
                mechanism_tags=["representation_switch"],
                core_objects=["original_problem_objects"],
                key_transformations=[f"encode_as:{name}"],
                proof_principles=[self._principle(name)],
                targeted_obligation_ids=[item.obligation_id for item in targets],
            )
            candidate_hash = stable_hash(
                (problem.integrity_hash, name, signature.normalized_hash)
            )
            candidates.append(
                RepresentationCandidate(
                    candidate_id=f"representation_{candidate_hash[:12]}",
                    source_problem_hash=problem.integrity_hash,
                    representation_name=name,
                    rewritten_problem_view=(
                        f"Re-express the open obligations using {name}; preserve "
                        "the original quantifiers and prove the encoding is reversible."
                    ),
                    object_mapping={
                        "original_problem_objects": f"{name}_objects",
                        "original_relations": f"{name}_relations",
                    },
                    preserved_invariants=[
                        "original quantifier order",
                        "truth of the target under a proved reversible encoding",
                    ],
                    lost_conditions=[
                        "none assumed; reversibility remains an explicit obligation"
                    ],
                    new_candidate_tools=self._tools(name),
                    expected_advantage=(
                        f"{name} exposes a different mechanism for the shared gap"
                    ),
                    failure_risks=[
                        "encoding may be one-way rather than equivalent",
                        "boundary or degeneracy cases may be lost",
                    ],
                    fast_failure_tests=[
                        "check the inverse mapping on a nontrivial boundary case",
                        "try to construct two source objects with the same encoding",
                    ],
                    novelty_signature=signature,
                )
            )
        return candidates

    @staticmethod
    def _principle(name: str) -> str:
        if "extremal" in name:
            return "minimal_counterexample"
        if "count" in name:
            return "double_counting"
        if "invariant" in name:
            return "invariance"
        if "probabilistic" in name:
            return "probabilistic_method"
        return name

    @staticmethod
    def _tools(name: str) -> list[str]:
        if name == "modular_congruence":
            return ["modular_exhaustive"]
        if name in {"coordinate_geometry", "complex_plane"}:
            return ["exact_geometry", "sympy_equivalent"]
        if name == "graph_hypergraph":
            return ["graph_certificate"]
        if name in {"recurrence_finite_state", "generating_function"}:
            return ["recurrence_check", "sympy_equivalent"]
        return []

    @staticmethod
    def validate_candidate(candidate: RepresentationCandidate) -> list[str]:
        errors: list[str] = []
        if not candidate.object_mapping:
            errors.append("missing object mapping")
        if not candidate.preserved_invariants:
            errors.append("missing preserved properties")
        if not candidate.failure_risks:
            errors.append("missing representation-loss risks")
        if not candidate.fast_failure_tests:
            errors.append("missing fast failure test")
        if not candidate.novelty_signature.targeted_obligation_ids:
            errors.append("candidate is not bound to an open obligation")
        return errors
