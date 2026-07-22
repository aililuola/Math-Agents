from __future__ import annotations

from typing import Iterable

from ..schemas import (
    ConstructionProposal,
    NoveltySignature,
    ProblemContract,
    ProofObligation,
    stable_hash,
)
from .domain_operators import DomainOperatorRegistry


DOMAIN_CONSTRUCTIONS: dict[str, tuple[str, ...]] = {
    "number_theory": (
        "auxiliary_sequence",
        "minimal_counterexample",
        "finite_state",
        "equivalence_relation",
        "quotient_structure",
    ),
    "combinatorics": (
        "bipartite_graph",
        "coloring",
        "potential_function",
        "generating_function",
        "extremal_object",
    ),
    "algebra": (
        "auxiliary_polynomial",
        "generating_function",
        "auxiliary_sequence",
        "quotient_structure",
    ),
    "inequalities": (
        "extremal_object",
        "potential_function",
        "auxiliary_polynomial",
    ),
    "geometry": (
        "auxiliary_point_circle_line",
        "new_coordinate_system",
        "potential_function",
    ),
}


class AuxiliaryConstructionInventor:
    """Generate explicit, obligation-bound, quickly falsifiable constructions."""

    def __init__(self, registry: DomainOperatorRegistry | None = None) -> None:
        self.registry = registry or DomainOperatorRegistry()

    def propose(
        self,
        problem: ProblemContract,
        obligations: Iterable[ProofObligation],
        *,
        domain: str = "unknown",
        max_proposals: int = 3,
        use_domain_operators: bool = True,
    ) -> list[ConstructionProposal]:
        targets = [
            item
            for item in obligations
            if item.status in {"open", "tentative", "blocked"}
        ]
        if not targets:
            return []
        inferred_domain = self.registry.infer_domain(problem, domain)
        fallback_types = list(
            DOMAIN_CONSTRUCTIONS.get(
                domain.casefold(),
                ("auxiliary_sequence", "extremal_object", "potential_function"),
            )
        )
        plugin_types = (
            [
                item.operator_id
                for item in self.registry.select(
                    problem,
                    domain=inferred_domain,
                    families=("construction",),
                    limit=max_proposals * 2,
                )
            ]
            if use_domain_operators
            else []
        )
        # Local fallback must exercise the same auditable operator contracts as
        # Active prompts. Generic templates remain available after every
        # applicable domain operator rather than hiding all plugins beyond K.
        types = list(dict.fromkeys([*plugin_types, *fallback_types]))
        proposals: list[ConstructionProposal] = []
        target_ids = [item.obligation_id for item in targets]
        for construction_type in types[:max_proposals]:
            operator = (
                self.registry.match(
                    construction_type,
                    domain=inferred_domain,
                    family="construction",
                )
                if use_domain_operators
                else None
            )
            constructed = (
                list(operator.object_tags)
                if operator is not None and operator.object_tags
                else self._objects(construction_type)
            )
            signature = NoveltySignature(
                representation_tags=[],
                mechanism_tags=["auxiliary_construction", construction_type],
                core_objects=constructed,
                key_transformations=[f"adjoin:{construction_type}"],
                proof_principles=[self._principle(construction_type)],
                targeted_obligation_ids=target_ids,
            )
            digest = stable_hash(
                (problem.integrity_hash, construction_type, tuple(target_ids))
            )
            proposals.append(
                ConstructionProposal(
                    proposal_id=f"construction_{digest[:12]}",
                    construction_type=construction_type,
                    constructed_objects=constructed,
                    definition=(
                        operator.transformation
                        if operator is not None
                        else (
                            f"Define the {construction_type} solely from the original "
                            "objects and record every well-definedness condition."
                        )
                    ),
                    intended_obligations=target_ids,
                    expected_invariant_or_relation=(
                        f"The {construction_type} should convert the open gap into "
                        "a local relation that can be independently verified."
                    ),
                    expected_proof_debt_reduction=(
                        "It targets the currently open high-priority obligations "
                        "rather than extending an already closed derivation."
                    ),
                    falsification_tests=(
                        list(operator.fast_failure_tests)
                        if operator is not None
                        else [
                            "instantiate the smallest nontrivial admissible case",
                            "test well-definedness under every allowed representation choice",
                        ]
                    ),
                    failure_conditions=(
                        list(operator.known_failure_modes)
                        if operator is not None
                        else [
                            "the construction depends on an arbitrary choice",
                            "the claimed relation is equivalent to the original unresolved goal",
                        ]
                    ),
                    operator_id=operator.operator_id if operator is not None else None,
                    operator_preconditions=(
                        list(operator.preconditions) if operator is not None else []
                    ),
                    generated_obligations=(
                        list(operator.generated_obligations)
                        if operator is not None
                        else []
                    ),
                    reversibility_requirements=(
                        list(operator.reversibility_requirements)
                        if operator is not None
                        else []
                    ),
                    suggested_tools=(
                        list(operator.suggested_tools) if operator is not None else []
                    ),
                    novelty_signature=signature,
                )
            )
        return proposals

    @staticmethod
    def _objects(construction_type: str) -> list[str]:
        names = {
            "auxiliary_sequence": ["auxiliary_sequence"],
            "minimal_counterexample": ["minimal_counterexample"],
            "finite_state": ["state_space", "transition_relation"],
            "equivalence_relation": ["equivalence_classes"],
            "quotient_structure": ["quotient_object"],
            "bipartite_graph": ["left_vertices", "right_vertices", "incidence_edges"],
            "coloring": ["color_classes"],
            "potential_function": ["state", "potential"],
            "generating_function": ["coefficient_sequence", "generating_function"],
            "extremal_object": ["extremal_object"],
            "auxiliary_polynomial": ["auxiliary_polynomial", "root_multiset"],
            "auxiliary_point_circle_line": ["auxiliary_point", "auxiliary_locus"],
            "new_coordinate_system": ["origin", "basis", "coordinate_map"],
        }
        return names.get(construction_type, [construction_type])

    @staticmethod
    def _principle(construction_type: str) -> str:
        if "graph" in construction_type or "color" in construction_type:
            return "incidence_structure"
        if "potential" in construction_type:
            return "monovariant"
        if "extremal" in construction_type or "minimal" in construction_type:
            return "extremal_principle"
        return "auxiliary_object"

    @staticmethod
    def validate(proposal: ConstructionProposal, open_ids: Iterable[str]) -> list[str]:
        errors: list[str] = []
        if not proposal.definition.strip():
            errors.append("construction has no definition")
        if not set(proposal.intended_obligations) & set(open_ids):
            errors.append("construction targets no open obligation")
        if not proposal.falsification_tests:
            errors.append("construction has no falsification test")
        if not proposal.failure_conditions:
            errors.append("construction has no stated failure condition")
        return errors
