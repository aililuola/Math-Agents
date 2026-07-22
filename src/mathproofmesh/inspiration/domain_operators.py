from __future__ import annotations

from collections.abc import Iterable

from ..schemas import DomainOperatorSpec, ProblemContract


def _operator(
    operator_id: str,
    domain: str,
    family: str,
    title: str,
    transformation: str,
    *,
    tokens: tuple[str, ...] = (),
    obligations: tuple[str, ...] = ("prove the transformed statement",),
    reversible: tuple[str, ...] = ("prove equivalence with the original goal",),
    failures: tuple[str, ...] = ("the transformation loses a boundary case",),
    tests: tuple[str, ...] = ("check the smallest nontrivial boundary case",),
    tools: tuple[str, ...] = (),
    representations: tuple[str, ...] = (),
    mechanisms: tuple[str, ...] = (),
    objects: tuple[str, ...] = (),
    operations: tuple[str, ...] = (),
) -> DomainOperatorSpec:
    return DomainOperatorSpec(
        operator_id=operator_id,
        domain=domain,
        family=family,
        title=title,
        applicability_tokens=list(tokens),
        preconditions=[
            "all objects satisfy the original hypotheses",
            f"the {title} transformation is well-defined",
        ],
        transformation=transformation,
        generated_obligations=list(obligations),
        reversibility_requirements=list(reversible),
        fast_failure_tests=list(tests),
        known_failure_modes=list(failures),
        suggested_tools=list(tools),
        representation_tags=list(representations or (operator_id,)),
        mechanism_tags=list(mechanisms or (operator_id,)),
        object_tags=list(objects),
        operation_tags=list(operations or (operator_id,)),
    )


_OPERATORS = (
    # Number theory
    _operator(
        "modular_congruence",
        "number_theory",
        "representation",
        "modular congruence",
        "project every claim to the smallest informative modulus",
        tokens=("integer", "divis", "congru", "mod"),
        obligations=(
            "prove the chosen modulus is sufficient",
            "lift the modular conclusion",
        ),
        reversible=("justify the lift from residues to the original integers",),
        failures=("the modulus erases valuation or size information",),
        tools=("modular_exhaustive",),
    ),
    _operator(
        "p_adic_valuation",
        "number_theory",
        "representation",
        "p-adic valuation profile",
        "replace multiplicative divisibility by coordinatewise prime valuations",
        tokens=("prime", "power", "divis", "factor"),
        obligations=("identify every relevant prime", "prove the valuation bounds"),
        failures=("cancellation makes the minimum-valuation rule inapplicable",),
        tests=("test equal-valuation cancellation",),
    ),
    _operator(
        "recurrence_finite_state",
        "number_theory",
        "representation",
        "finite residue-state automaton",
        "encode recurrence states by a finite tuple of residues",
        tokens=("sequence", "recurrence", "iterate", "period"),
        obligations=(
            "prove the state transition is deterministic",
            "prove state repetition implies the target",
        ),
        failures=("the selected state omits history needed by the recurrence",),
        tools=("recurrence_check",),
    ),
    _operator(
        "extremal_minimal_counterexample",
        "number_theory",
        "representation",
        "minimal counterexample descent",
        "assume a least counterexample and construct a strictly smaller one",
        obligations=(
            "prove a least counterexample exists",
            "prove strict descent preserves all hypotheses",
        ),
        failures=("the descent parameter is not well-founded",),
    ),
    _operator(
        "invariant_monovariant",
        "number_theory",
        "representation",
        "arithmetic invariant profile",
        "track a residue or valuation quantity preserved by every legal operation",
        obligations=(
            "prove preservation for each operation",
            "separate initial and forbidden states",
        ),
        failures=("the quantity is constant on both desired and forbidden states",),
    ),
    _operator(
        "lte_compatible_transformation",
        "number_theory",
        "construction",
        "LTE-compatible factorization",
        "factor the difference of powers before applying a valuation formula",
        tokens=("power", "difference", "odd", "valuation"),
        obligations=("verify every LTE parity and divisibility hypothesis",),
        failures=("the prime or parity hypotheses of LTE are absent",),
        tests=("check p=2 and equal residue boundary cases",),
    ),
    _operator(
        "multiplicative_order",
        "number_theory",
        "construction",
        "multiplicative order reduction",
        "replace an exponential congruence by divisibility of an order",
        tokens=("exponent", "primitive", "order", "mod"),
        obligations=(
            "prove the base is a unit",
            "determine or bound the relevant order",
        ),
        failures=("the base is not invertible modulo the chosen modulus",),
        tools=("modular_exhaustive",),
    ),
    _operator(
        "crt_decomposition",
        "number_theory",
        "construction",
        "Chinese remainder decomposition",
        "split a composite-modulus condition into compatible prime-power conditions",
        tokens=("coprime", "modulus", "residue", "congru"),
        obligations=("prove pairwise coprimality", "recombine all local solutions"),
        failures=("the moduli are not coprime or compatibility is missing",),
        tools=("modular_exhaustive",),
    ),
    _operator(
        "lifting_modulus",
        "number_theory",
        "mutation",
        "modulus lifting",
        "lift a residue statement from p^k to p^(k+1) while tracking the correction term",
        tokens=("prime power", "lift", "mod"),
        obligations=("derive the correction congruence", "prove every lift is covered"),
        failures=("a singular derivative or nonunit coefficient blocks lifting",),
    ),
    _operator(
        "local_to_global_arithmetic",
        "number_theory",
        "mutation",
        "local-to-global arithmetic split",
        "separate the claim into prime-local constraints and a recombination condition",
        obligations=(
            "prove local necessity",
            "prove simultaneous local conditions are sufficient",
        ),
        failures=("a global compatibility obstruction is invisible locally",),
    ),
    # Combinatorics
    _operator(
        "graph_hypergraph",
        "combinatorics",
        "representation",
        "incidence graph encoding",
        "encode objects as vertices and admissible relations as edges or hyperedges",
        tokens=("subset", "incidence", "pair", "family"),
        obligations=(
            "prove the encoding is bijective",
            "translate the target graph property back",
        ),
        failures=("multiplicity or ordering is lost in the graph encoding",),
        tools=("graph_certificate",),
    ),
    _operator(
        "double_counting",
        "combinatorics",
        "representation",
        "double-counting incidence space",
        "define one incidence set and count it by two projections",
        tokens=("count", "family", "incidence", "degree"),
        obligations=("prove both counts enumerate exactly the same incidences",),
        failures=("one count silently includes multiplicity not present in the other",),
    ),
    _operator(
        "generating_function",
        "combinatorics",
        "representation",
        "generating function encoding",
        "encode admissible objects as coefficients of a formal series",
        tokens=("sequence", "count", "partition", "recurrence"),
        obligations=("justify coefficient extraction formally",),
        failures=(
            "analytic convergence is used where only formal identities are valid",
        ),
        tools=("sympy_equivalent",),
    ),
    _operator(
        "probabilistic_method",
        "combinatorics",
        "representation",
        "probabilistic relaxation",
        "sample an object from an explicit distribution and bound the bad events",
        tokens=("exists", "color", "subset", "avoid"),
        obligations=(
            "define the distribution",
            "prove positive probability of success",
        ),
        failures=("dependence invalidates the probability bound",),
    ),
    _operator(
        "linear_algebra_polynomial",
        "combinatorics",
        "representation",
        "linear algebra or polynomial encoding",
        "encode incidence constraints as rank, determinant, or polynomial vanishing conditions",
        obligations=("prove the algebraic encoding is faithful",),
        failures=("the field characteristic destroys the intended rank argument",),
        tools=("sympy_equivalent",),
    ),
    _operator(
        "shifting_compression",
        "combinatorics",
        "construction",
        "shifting/compression",
        "replace the family by a compressed family while preserving size and the forbidden-property condition",
        tokens=("family", "subset", "intersect", "extremal"),
        obligations=(
            "prove compression preserves admissibility",
            "characterize the terminal compressed family",
        ),
        failures=("the target property is not compression-monotone",),
    ),
    _operator(
        "hall_matching_conversion",
        "combinatorics",
        "construction",
        "Hall matching conversion",
        "construct a bipartite incidence graph and translate the goal to a matching",
        tokens=("distinct representative", "matching", "assignment", "choose"),
        obligations=("verify Hall's condition for every subset",),
        failures=(
            "the desired assignment has constraints not represented by adjacency",
        ),
        tools=("graph_certificate",),
    ),
    _operator(
        "potential_function",
        "combinatorics",
        "construction",
        "potential function",
        "assign an exact potential to each state and prove monotone progress",
        tokens=("operation", "game", "process", "terminate"),
        obligations=("prove strict potential change", "prove the potential is bounded"),
        failures=("legal moves can leave the potential unchanged or cycle",),
    ),
    _operator(
        "complement_duality",
        "combinatorics",
        "mutation",
        "complement or dual structure",
        "replace the family, graph, or order by its complement or dual",
        obligations=(
            "translate every hypothesis under duality",
            "translate the conclusion back",
        ),
        failures=("the property is not invariant under complement or duality",),
    ),
    # Inequalities
    _operator(
        "direct_algebra",
        "inequalities",
        "representation",
        "normalized homogeneous algebra",
        "normalize scale and rewrite the target as a homogeneous inequality",
        tokens=("positive", "inequality", "sum", "product"),
        obligations=(
            "justify normalization when the scale can vanish",
            "restore the original scale",
        ),
        failures=("normalization excludes a zero or sign-changing boundary",),
        tools=("sympy_equivalent",),
    ),
    _operator(
        "smoothing_variable_mixing",
        "inequalities",
        "construction",
        "smoothing and variable mixing",
        "replace two variables by an equalized pair while preserving the constraints",
        tokens=("symmetric", "positive", "fixed sum", "variables"),
        obligations=(
            "prove the objective is monotone under mixing",
            "handle the equality boundary",
        ),
        failures=("the expression is not convex or concave on the full domain",),
    ),
    _operator(
        "tangent_line",
        "inequalities",
        "construction",
        "tangent-line bound",
        "majorize or minorize each nonlinear term by a tangent at the equality point",
        tokens=("convex", "concave", "equality", "function"),
        obligations=("prove the global tangent bound on the declared domain",),
        failures=(
            "the tangent changes from support to crossing outside a local interval",
        ),
    ),
    _operator(
        "uvw_pqr",
        "inequalities",
        "representation",
        "uvw/pqr symmetric reduction",
        "rewrite a symmetric polynomial inequality through elementary symmetric parameters",
        tokens=("symmetric", "three variables", "polynomial"),
        obligations=("prove the reduction covers the admissible parameter region",),
        failures=("the inequality is cyclic rather than symmetric",),
        tools=("sympy_equivalent",),
    ),
    _operator(
        "sum_of_squares_target",
        "inequalities",
        "construction",
        "sum-of-squares target",
        "seek an exact decomposition into nonnegative weighted squares",
        tokens=("polynomial", "nonnegative", "equality"),
        obligations=(
            "verify the identity exactly",
            "prove every weight is nonnegative",
        ),
        failures=("a numerically plausible decomposition uses a negative coefficient",),
        tools=("sympy_equivalent",),
    ),
    _operator(
        "convex_dual",
        "inequalities",
        "mutation",
        "convex dual viewpoint",
        "replace the primal inequality by a supporting-hyperplane or conjugate bound",
        obligations=(
            "verify convexity and the dual domain",
            "recover the equality conditions",
        ),
        failures=("the function is not closed convex on the stated domain",),
    ),
    # Geometry
    _operator(
        "synthetic_geometry",
        "geometry",
        "representation",
        "synthetic incidence-angle geometry",
        "retain incidences, directed angles, ratios, and cyclicity as primitive relations",
        tokens=("triangle", "circle", "angle", "line"),
        obligations=("state all directed-angle and degeneracy conventions",),
        failures=("an undirected-angle step loses orientation",),
        tools=("exact_geometry",),
    ),
    _operator(
        "coordinate_geometry",
        "geometry",
        "representation",
        "exact coordinate geometry",
        "choose coordinates adapted to the strongest incidence and translate every object exactly",
        tokens=("point", "line", "circle", "distance"),
        obligations=(
            "prove coordinate denominators are nonzero",
            "translate the final identity back",
        ),
        failures=("the coordinate choice excludes a legal degenerate position",),
        tools=("exact_geometry", "sympy_equivalent"),
    ),
    _operator(
        "complex_plane",
        "geometry",
        "representation",
        "complex-plane geometry",
        "place a natural circle on the unit complex circle and encode incidences algebraically",
        tokens=("circle", "angle", "concyclic"),
        obligations=("justify conjugation identities and nonzero denominators",),
        failures=(
            "unit-circle normalization is incompatible with a required affine property",
        ),
        tools=("sympy_equivalent",),
    ),
    _operator(
        "inversion_projective",
        "geometry",
        "representation",
        "inversion or projective transform",
        "map the difficult circle-line configuration to a simpler incidence configuration",
        tokens=("circle", "tangent", "collinear", "concurrent"),
        obligations=(
            "track every exceptional point",
            "prove the target property is preserved",
        ),
        failures=(
            "the inversion center lies on a required object or orientation is lost",
        ),
    ),
    _operator(
        "auxiliary_intersection",
        "geometry",
        "construction",
        "auxiliary point intersection",
        "introduce the intersection of two target-relevant lines or loci",
        obligations=(
            "prove the intersection exists and is unique",
            "derive the intended incidence relation",
        ),
        failures=("the defining lines can be parallel or coincident",),
        tools=("exact_geometry",),
    ),
    _operator(
        "circle_completion",
        "geometry",
        "construction",
        "circle completion",
        "add the circle through three structurally central points and exploit power or radical axes",
        tokens=("circle", "power", "cyclic", "intersection"),
        obligations=(
            "prove the three points are noncollinear",
            "prove the required points lie on or relate to the circle",
        ),
        failures=(
            "the proposed circle is degenerate or restates the target cyclicity",
        ),
        tools=("exact_geometry",),
    ),
    _operator(
        "reflection_isogonal",
        "geometry",
        "construction",
        "reflection or isogonal conjugation",
        "reflect a line or point across an angle bisector to expose equal-angle structure",
        tokens=("angle", "bisector", "cevi", "triangle"),
        obligations=(
            "prove the isogonal correspondence is defined",
            "translate incidence under reflection",
        ),
        failures=(
            "the construction assumes an internal angle where an external one is required",
        ),
    ),
    _operator(
        "homothety_center",
        "geometry",
        "construction",
        "homothety center",
        "introduce the internal or external homothety center of two circles",
        tokens=("two circles", "tangent", "parallel"),
        obligations=(
            "identify the correct homothety center",
            "prove the mapped points correspond",
        ),
        failures=("equal or concentric circles make the chosen center exceptional",),
    ),
    _operator(
        "barycentric_projection",
        "geometry",
        "mutation",
        "barycentric or projective lift",
        "lift the triangle configuration to homogeneous coordinates and project the target incidence",
        obligations=(
            "prove homogeneous expressions are scale-invariant",
            "exclude vanishing normalization factors",
        ),
        failures=("a metric claim is treated as purely projective",),
        tools=("exact_geometry", "sympy_equivalent"),
    ),
)


class DomainOperatorRegistry:
    """Finite, auditable operator ontology for domain-specific inspiration."""

    def __init__(self, operators: Iterable[DomainOperatorSpec] = _OPERATORS) -> None:
        self._operators = {item.operator_id: item for item in operators}

    @property
    def operators(self) -> list[DomainOperatorSpec]:
        return list(self._operators.values())

    def get(self, operator_id: str) -> DomainOperatorSpec | None:
        return self._operators.get(operator_id)

    def select(
        self,
        problem: ProblemContract,
        *,
        domain: str,
        families: Iterable[str] = ("representation", "construction", "mutation"),
        forbidden: Iterable[str] = (),
        limit: int = 8,
    ) -> list[DomainOperatorSpec]:
        inferred = self.infer_domain(problem, domain)
        allowed_families = set(families)
        blocked = {item.casefold() for item in forbidden}
        text = f"{problem.normalized_statement} {problem.exact_statement}".casefold()
        ranked: list[tuple[int, int, DomainOperatorSpec]] = []
        for index, operator in enumerate(self._operators.values()):
            if operator.domain != inferred or operator.family not in allowed_families:
                continue
            labels = {
                operator.operator_id.casefold(),
                *(item.casefold() for item in operator.representation_tags),
                *(item.casefold() for item in operator.mechanism_tags),
                *(item.casefold() for item in operator.operation_tags),
            }
            if labels & blocked:
                continue
            score = sum(
                token.casefold() in text for token in operator.applicability_tokens
            )
            ranked.append((-score, index, operator))
        ranked.sort(key=lambda item: (item[0], item[1], item[2].operator_id))
        return [item[2] for item in ranked[:limit]]

    def match(
        self,
        name: str,
        *,
        domain: str,
        family: str,
    ) -> DomainOperatorSpec | None:
        direct = self.get(name)
        if direct is not None and direct.domain == domain and direct.family == family:
            return direct
        folded = name.casefold()
        return next(
            (
                item
                for item in self._operators.values()
                if item.domain == domain
                and item.family == family
                and folded
                in {
                    *(tag.casefold() for tag in item.representation_tags),
                    *(tag.casefold() for tag in item.mechanism_tags),
                    *(tag.casefold() for tag in item.operation_tags),
                }
            ),
            None,
        )

    @staticmethod
    def infer_domain(problem: ProblemContract, domain: str) -> str:
        normalized = domain.casefold()
        if normalized in {"number_theory", "combinatorics", "inequalities", "geometry"}:
            return normalized
        text = f"{problem.normalized_statement} {problem.exact_statement}".casefold()
        if any(token in text for token in ("prime", "integer", "divis", "congru")):
            return "number_theory"
        if any(token in text for token in ("graph", "subset", "color", "family")):
            return "combinatorics"
        if any(token in text for token in ("triangle", "circle", "angle", "point")):
            return "geometry"
        return "inequalities"

    @staticmethod
    def prompt_payload(
        operators: Iterable[DomainOperatorSpec],
    ) -> list[dict[str, object]]:
        return [item.model_dump(mode="json") for item in operators]


__all__ = ["DomainOperatorRegistry"]
