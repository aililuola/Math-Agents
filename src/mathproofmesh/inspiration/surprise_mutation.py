from __future__ import annotations

from collections.abc import Iterable

from ..schemas import (
    NoveltySignature,
    ProblemContract,
    SurpriseMutationDirective,
    stable_hash,
)
from .domain_operators import DomainOperatorRegistry


_GENERIC_MUTATIONS: tuple[dict[str, object], ...] = (
    {
        "operator_id": "dualize",
        "transformation": "replace objects and relations by their natural duals",
        "preconditions": ["a well-defined dual object and dual relation exist"],
        "obligations": ["prove the dual translation in both directions"],
        "reversibility": ["dualizing twice recovers the scoped original data"],
        "tests": ["dualize a smallest boundary configuration twice"],
        "failures": ["the target contains metric data not preserved by duality"],
    },
    {
        "operator_id": "complement",
        "transformation": "replace the target structure by its complement",
        "preconditions": ["the ambient universe is explicitly fixed"],
        "obligations": ["translate every hypothesis and the target under complement"],
        "reversibility": ["taking the complement twice restores the original object"],
        "tests": ["check empty and full ambient structures"],
        "failures": ["the ambient universe changes during the argument"],
    },
    {
        "operator_id": "quotient",
        "transformation": "identify states related by a proved equivalence relation",
        "preconditions": [
            "the proposed relation is an equivalence and respects operations"
        ],
        "obligations": ["prove well-defined operations on equivalence classes"],
        "reversibility": [
            "state exactly which original information the quotient discards"
        ],
        "tests": ["test representatives from one nontrivial equivalence class"],
        "failures": ["the target depends on information erased by the quotient"],
    },
    {
        "operator_id": "lift",
        "transformation": "embed the problem in a stronger modulus, dimension, or ambient structure",
        "preconditions": ["the original objects admit the proposed embedding"],
        "obligations": ["prove the lifted claim projects to the original target"],
        "reversibility": ["provide a projection or restriction map"],
        "tests": ["project a smallest lifted example back to the original domain"],
        "failures": [
            "the lifted problem is stronger but no easier or has spurious solutions"
        ],
    },
    {
        "operator_id": "project",
        "transformation": "project the state to a smaller statistic that retains the target obstruction",
        "preconditions": ["the target is determined by the selected statistic"],
        "obligations": [
            "prove fibers of the projection do not change the target truth value"
        ],
        "reversibility": ["record the exact information lost in each fiber"],
        "tests": ["search for two fiber elements with different target behavior"],
        "failures": ["the projection merges a valid and invalid state"],
    },
    {
        "operator_id": "extremalize",
        "transformation": "choose an admissible object extremal for a well-founded parameter",
        "preconditions": [
            "the admissible set is nonempty and the parameter attains an extremum"
        ],
        "obligations": ["derive a local structural consequence of extremality"],
        "reversibility": ["show the extremal restriction does not assume the target"],
        "tests": ["verify the parameter on the smallest admissible family"],
        "failures": [
            "the parameter is not well-founded or extremality gives no rigidity"
        ],
    },
    {
        "operator_id": "random_auxiliary_object",
        "transformation": "add one seed-determined auxiliary object satisfying explicit constraints",
        "preconditions": ["the constraint set for the auxiliary object is nonempty"],
        "obligations": ["prove existence and independence from arbitrary choices"],
        "reversibility": ["eliminate the auxiliary object from the final conclusion"],
        "tests": ["instantiate the deterministic seed on a boundary case"],
        "failures": [
            "the construction hides an unjustified generic-position assumption"
        ],
    },
    {
        "operator_id": "reverse_operation",
        "transformation": "study legal predecessor states or inverse operations from the target",
        "preconditions": [
            "inverse operations and their domains are explicitly defined"
        ],
        "obligations": ["classify all valid predecessors of a target state"],
        "reversibility": [
            "prove each inverse step corresponds to a legal forward step"
        ],
        "tests": ["apply an inverse and then the forward operation"],
        "failures": [
            "the operation is many-to-one and the inverse classification is incomplete"
        ],
    },
    {
        "operator_id": "local_to_global",
        "transformation": "split the global target into compatible local conditions",
        "preconditions": ["the selected localizations cover every global obstruction"],
        "obligations": [
            "prove local necessity",
            "prove the compatibility gluing condition",
        ],
        "reversibility": ["reconstruct a global witness or proof from local data"],
        "tests": ["test a known local-but-not-global obstruction"],
        "failures": ["a compatibility obstruction survives all local checks"],
    },
    {
        "operator_id": "relax_then_round",
        "transformation": "relax the discrete constraints, solve a continuous surrogate, then round",
        "preconditions": [
            "a meaningful relaxation contains every feasible discrete object"
        ],
        "obligations": ["bound the rounding loss", "restore every discrete constraint"],
        "reversibility": ["state which relaxed conclusions survive rounding"],
        "tests": ["round an extremal fractional boundary point"],
        "failures": ["the integrality gap is as large as the desired conclusion"],
    },
    {
        "operator_id": "encode_as_graph",
        "transformation": "encode objects and allowed relations as a graph or hypergraph",
        "preconditions": ["vertices, edges, and multiplicities are explicitly defined"],
        "obligations": [
            "prove the encoding and decoding maps",
            "translate the target property",
        ],
        "reversibility": ["recover every original constraint from graph data"],
        "tests": ["decode a graph with an isolated vertex and a repeated relation"],
        "failures": ["ordering or multiplicity is lost"],
        "tools": ["graph_certificate"],
    },
    {
        "operator_id": "encode_as_polynomial",
        "transformation": "encode the target constraints as polynomial identities or vanishing conditions",
        "preconditions": ["the coefficient field and characteristic are explicit"],
        "obligations": ["prove algebraic solutions correspond to admissible objects"],
        "reversibility": ["exclude extraneous roots or multiplicities"],
        "tests": ["check the encoding on the smallest valid and invalid objects"],
        "failures": ["the polynomial has extraneous solutions"],
        "tools": ["sympy_equivalent"],
    },
    {
        "operator_id": "encode_as_state_machine",
        "transformation": "encode progress by a finite state and deterministic transition",
        "preconditions": [
            "the finite state contains all history relevant to the next step"
        ],
        "obligations": [
            "prove transition completeness",
            "classify cycles and terminal states",
        ],
        "reversibility": ["map every state transition to a legal original operation"],
        "tests": ["compare two histories mapped to the same state"],
        "failures": [
            "the state abstraction omits history and becomes nondeterministic"
        ],
        "tools": ["recurrence_check"],
    },
)


class ControlledMutationPlanner:
    """Choose a distinct mutation deterministically and record its seed."""

    def __init__(self, registry: DomainOperatorRegistry | None = None) -> None:
        self.registry = registry or DomainOperatorRegistry()

    def plan(
        self,
        problem: ProblemContract,
        *,
        task_id: str,
        proposal_slot: int,
        target_obligation_ids: Iterable[str],
        domain: str,
        existing_signatures: Iterable[NoveltySignature] = (),
    ) -> SurpriseMutationDirective:
        targets = list(dict.fromkeys(target_obligation_ids))
        forbidden = {
            tag.casefold()
            for signature in existing_signatures
            for tag in (
                *signature.representation_tags,
                *signature.mechanism_tags,
                *signature.key_transformations,
            )
        }
        domain_specific = self.registry.select(
            problem,
            domain=domain,
            families=("mutation",),
            forbidden=forbidden,
            limit=16,
        )
        pool: list[dict[str, object]] = [
            {
                "operator_id": item.operator_id,
                "transformation": item.transformation,
                "preconditions": item.preconditions,
                "obligations": item.generated_obligations,
                "reversibility": item.reversibility_requirements,
                "tests": item.fast_failure_tests,
                "failures": item.known_failure_modes,
                "tools": item.suggested_tools,
            }
            for item in domain_specific
        ]
        pool.extend(
            item
            for item in _GENERIC_MUTATIONS
            if str(item["operator_id"]).casefold() not in forbidden
        )
        if not pool:
            pool = list(_GENERIC_MUTATIONS)
        seed = int(stable_hash((problem.integrity_hash, task_id))[:12], 16)
        selected = pool[(seed + proposal_slot) % len(pool)]
        operator_id = str(selected["operator_id"])
        directive_id = (
            "mutation_"
            + stable_hash(
                (problem.integrity_hash, task_id, proposal_slot, operator_id, targets)
            )[:16]
        )
        return SurpriseMutationDirective(
            directive_id=directive_id,
            operator_id=operator_id,
            seed=seed + proposal_slot,
            target_obligation_ids=targets,
            transformation=str(selected["transformation"]),
            preconditions=list(selected["preconditions"]),
            generated_obligations=list(selected["obligations"]),
            reversibility_requirements=list(selected["reversibility"]),
            fast_failure_tests=list(selected["tests"]),
            known_failure_modes=list(selected["failures"]),
            suggested_tools=list(selected.get("tools", [])),
        )


__all__ = ["ControlledMutationPlanner"]
