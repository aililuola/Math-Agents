from __future__ import annotations

from fractions import Fraction
from typing import Any

import sympy as sp

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence
from .symbolic import parse_expression, relation_holds_exact


_RELATIONS = {"eq", "ne", "le", "lt", "ge", "gt"}
_DOMAIN_KEYS = {
    "min",
    "max",
    "min_exclusive",
    "max_exclusive",
    "positive",
    "nonnegative",
    "nonzero",
}
DEFAULT_TIMEOUT_MS = 10_000
MAX_TIMEOUT_MS = 60_000
_MAX_INTEGER_EXPONENT = 16


class UnsupportedRealOperationError(ValueError):
    """The expression falls outside the polynomial/rational QF_NRA fragment."""


def _exact_rational(value: Any, label: str) -> Fraction:
    if value is None or isinstance(value, bool):
        raise ValueError(f"{label} must be an exact rational number")
    if isinstance(value, float):
        raise ValueError(f"{label} must be an exact rational, not a float")
    try:
        return Fraction(str(value))
    except (TypeError, ValueError, ZeroDivisionError) as exc:
        raise ValueError(f"{label} must be an exact rational number") from exc


def _to_z3_real(expr: sp.Expr, variables: dict[str, Any], z3: Any) -> Any:
    """Translate a SymPy expression into a Z3 real term, rejecting anything
    outside + - * / with integer powers, Min/Max, and Abs."""

    if isinstance(expr, sp.Symbol):
        name = str(expr)
        if name not in variables:
            raise UnsupportedRealOperationError(f"undeclared variable: {name}")
        return variables[name]
    if isinstance(expr, sp.Rational):
        # Covers Integer as well; exact p/q construction avoids float rounding.
        return z3.RealVal(f"{expr.p}/{expr.q}")
    if isinstance(expr, sp.Add):
        terms = [_to_z3_real(term, variables, z3) for term in expr.args]
        result = terms[0]
        for term in terms[1:]:
            result = result + term
        return result
    if isinstance(expr, sp.Mul):
        factors = [_to_z3_real(factor, variables, z3) for factor in expr.args]
        result = factors[0]
        for factor in factors[1:]:
            result = result * factor
        return result
    if isinstance(expr, sp.Pow):
        exponent = expr.exp
        if not isinstance(exponent, sp.Integer):
            raise UnsupportedRealOperationError(
                f"non-integer exponent {exponent} is outside QF_NRA"
            )
        power = int(exponent)
        if abs(power) > _MAX_INTEGER_EXPONENT:
            raise UnsupportedRealOperationError(
                f"integer exponents are limited to |e| <= {_MAX_INTEGER_EXPONENT}"
            )
        base = _to_z3_real(expr.base, variables, z3)
        if power == 0:
            return z3.RealVal(1)
        result = base
        for _ in range(abs(power) - 1):
            result = result * base
        return result if power > 0 else z3.RealVal(1) / result
    if isinstance(expr, (sp.Min, sp.Max)):
        terms = [_to_z3_real(term, variables, z3) for term in expr.args]
        result = terms[0]
        for term in terms[1:]:
            if isinstance(expr, sp.Min):
                result = z3.If(result <= term, result, term)
            else:
                result = z3.If(result >= term, result, term)
        return result
    if isinstance(expr, sp.Abs):
        inner = _to_z3_real(expr.args[0], variables, z3)
        return z3.If(inner >= 0, inner, -inner)
    raise UnsupportedRealOperationError(
        f"unsupported operation for QF_NRA translation: {type(expr).__name__}"
    )


def _z3_relation(left: Any, right: Any, relation: str) -> Any:
    if relation == "eq":
        return left == right
    if relation == "ne":
        return left != right
    if relation == "le":
        return left <= right
    if relation == "lt":
        return left < right
    if relation == "ge":
        return left >= right
    if relation == "gt":
        return left > right
    raise ValueError(f"unsupported relation: {relation}")


def _parse_domain(spec: ExperimentSpec, name: str) -> dict[str, Any]:
    raw = spec.domains.get(name)
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        raise ValueError(f"domain for {name!r} must be a mapping")
    unknown = sorted(set(raw) - _DOMAIN_KEYS)
    if unknown:
        raise ValueError(
            f"domain for {name!r} has unsupported keys: {', '.join(unknown)}"
        )
    parsed: dict[str, Any] = {}
    for key in ("min", "max"):
        if key in raw:
            parsed[key] = _exact_rational(raw[key], f"domain {key} for {name!r}")
    for key in ("min_exclusive", "max_exclusive", "positive", "nonnegative", "nonzero"):
        if key in raw:
            if not isinstance(raw[key], bool):
                raise ValueError(f"domain {key} for {name!r} must be a boolean")
            parsed[key] = raw[key]
    if "min" in parsed and "max" in parsed and parsed["max"] < parsed["min"]:
        raise ValueError(f"domain for {name!r} has max < min")
    return parsed


def _domain_constraints(domain: dict[str, Any], variable: Any, z3: Any) -> list[Any]:
    constraints: list[Any] = []
    if "min" in domain:
        bound = z3.RealVal(f"{domain['min'].numerator}/{domain['min'].denominator}")
        constraints.append(
            variable > bound if domain.get("min_exclusive") else variable >= bound
        )
    if "max" in domain:
        bound = z3.RealVal(f"{domain['max'].numerator}/{domain['max'].denominator}")
        constraints.append(
            variable < bound if domain.get("max_exclusive") else variable <= bound
        )
    if domain.get("positive"):
        constraints.append(variable > 0)
    if domain.get("nonnegative"):
        constraints.append(variable >= 0)
    if domain.get("nonzero"):
        constraints.append(variable != 0)
    return constraints


def _point_in_domain(domain: dict[str, Any], value: Fraction) -> bool:
    if "min" in domain:
        if domain.get("min_exclusive"):
            if not value > domain["min"]:
                return False
        elif not value >= domain["min"]:
            return False
    if "max" in domain:
        if domain.get("max_exclusive"):
            if not value < domain["max"]:
                return False
        elif not value <= domain["max"]:
            return False
    if domain.get("positive") and not value > 0:
        return False
    if domain.get("nonnegative") and not value >= 0:
        return False
    if domain.get("nonzero") and value == 0:
        return False
    return True


def _model_value_as_fraction(value: Any, z3: Any) -> tuple[Fraction, bool]:
    """Return an exact rational for the model value; flags approximation of
    irrational algebraic model values."""

    if isinstance(value, z3.RatNumRef):
        return Fraction(value.as_fraction()), False
    if isinstance(value, z3.AlgebraicNumRef):
        return Fraction(value.approx(40).as_fraction()), True
    raise ValueError(f"unsupported Z3 model value kind: {type(value).__name__}")


def run_real_inequality(spec: ExperimentSpec) -> HandlerEvidence:
    try:
        import z3  # type: ignore[import-not-found]
    except ImportError as exc:
        raise RuntimeError(
            "real_inequality requires the optional z3-solver dependency"
        ) from exc

    args = spec.arguments
    relation = str(args.get("relation", "ge"))
    if relation not in _RELATIONS:
        raise ValueError("relation must be one of: " + ", ".join(sorted(_RELATIONS)))
    raw_timeout = args.get("max_runtime_ms", DEFAULT_TIMEOUT_MS)
    if isinstance(raw_timeout, bool) or not isinstance(raw_timeout, int):
        raise ValueError("max_runtime_ms must be an integer number of milliseconds")
    if raw_timeout < 1:
        raise ValueError("max_runtime_ms must be at least 1")
    timeout_ms = min(raw_timeout, MAX_TIMEOUT_MS)

    lhs = parse_expression(str(args["lhs"]))
    rhs = parse_expression(str(args.get("rhs", "0")))
    declared = [str(value) for value in args.get("variables", [])]
    free_names = sorted(str(symbol) for symbol in lhs.free_symbols | rhs.free_symbols)
    variables = declared or free_names
    if len(variables) != len(set(variables)):
        raise ValueError("real_inequality variable names must be unique")
    if not set(free_names).issubset(variables):
        raise ValueError("every expression variable must be declared")
    for name in spec.domains:
        if name not in variables:
            raise ValueError(f"domain declared for undeclared variable {name!r}")

    domains = {name: _parse_domain(spec, name) for name in variables}
    z3_variables = {name: z3.Real(name) for name in variables}
    try:
        z3_lhs = _to_z3_real(lhs, z3_variables, z3)
        z3_rhs = _to_z3_real(rhs, z3_variables, z3)
    except UnsupportedRealOperationError as exc:
        return HandlerEvidence(
            outcome=ExperimentOutcome.INCONCLUSIVE,
            evidence_strength=EvidenceStrength.HEURISTIC,
            scope={"relation": relation, "domains": spec.domains},
            verification_notes=[
                f"Unsupported operation: {exc}. Only polynomial/rational "
                "expressions with integer powers (plus Min/Max/Abs) can be "
                "decided in Z3 QF_NRA, so no evidence was produced."
            ],
        )

    solver = z3.Solver()
    solver.set("timeout", timeout_ms)
    solver.set("random_seed", spec.seed)
    for name in variables:
        solver.add(*_domain_constraints(domains[name], z3_variables[name], z3))
    solver.add(z3.Not(_z3_relation(z3_lhs, z3_rhs, relation)))
    status = solver.check()

    claim = f"{args['lhs']} {relation} {args.get('rhs', '0')}"
    if status == z3.unsat:
        return HandlerEvidence(
            outcome=ExperimentOutcome.CERTIFIED,
            evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
            certificate={
                "claim": claim,
                "relation": relation,
                "domains": spec.domains,
                "solver_status": "unsat",
                "timeout_ms": timeout_ms,
                "logic": "QF_NRA",
            },
            scope={
                "quantification": "universal over the declared real domain",
                "domains": spec.domains,
            },
            exact_arithmetic=True,
            independently_verified=False,
            verification_notes=[
                "Z3 nonlinear-real-arithmetic (QF_NRA) certificate: the negation "
                "of the declared inequality is unsatisfiable over the declared "
                "real domain, so the inequality holds throughout that domain.",
                "The certificate covers exactly the declared domain and relation; "
                "trust rests on Z3's NRA decision procedure rather than an "
                "independent checker.",
            ],
        )
    if status == z3.sat:
        model = solver.model()
        assignment: dict[str, Fraction] = {}
        approximated = False
        try:
            for name in variables:
                value, approximate = _model_value_as_fraction(
                    model.eval(z3_variables[name], model_completion=True), z3
                )
                assignment[name] = value
                approximated = approximated or approximate
        except ValueError as exc:
            return HandlerEvidence(
                outcome=ExperimentOutcome.INCONCLUSIVE,
                evidence_strength=EvidenceStrength.HEURISTIC,
                scope={"relation": relation, "domains": spec.domains},
                verification_notes=[
                    f"Z3 reported sat but its model was not exactly "
                    f"interpretable ({exc}); no counterexample is accepted."
                ],
            )
        in_domain = all(
            _point_in_domain(domains[name], assignment[name]) for name in variables
        )
        substitution = {
            symbol: sp.Rational(
                assignment[str(symbol)].numerator, assignment[str(symbol)].denominator
            )
            for symbol in lhs.free_symbols | rhs.free_symbols
        }
        confirmed = False
        lhs_value: sp.Expr | None = None
        rhs_value: sp.Expr | None = None
        if in_domain:
            try:
                lhs_value = sp.simplify(lhs.subs(substitution))
                rhs_value = sp.simplify(rhs.subs(substitution))
                confirmed = not relation_holds_exact(lhs_value, rhs_value, relation)
            except ValueError:
                confirmed = False
        if confirmed:
            notes = [
                "The Z3 model was independently re-substituted with exact SymPy "
                "rational arithmetic and confirmed to violate the declared "
                "inequality inside the declared domain."
            ]
            if approximated:
                notes.append(
                    "The Z3 model was an irrational algebraic number; a nearby "
                    "exact rational point was verified instead."
                )
            return HandlerEvidence(
                outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
                counterexample={
                    "assignment": {
                        name: str(value) for name, value in assignment.items()
                    },
                    "lhs_value": str(lhs_value),
                    "rhs_value": str(rhs_value),
                    "relation": relation,
                },
                scope={"domains": spec.domains},
                exact_arithmetic=True,
                cases_checked=1,
                independently_verified=True,
                verification_notes=notes,
            )
        return HandlerEvidence(
            outcome=ExperimentOutcome.INCONCLUSIVE,
            evidence_strength=EvidenceStrength.HEURISTIC,
            scope={
                "relation": relation,
                "domains": spec.domains,
                "candidate_assignment": {
                    name: str(value) for name, value in assignment.items()
                },
            },
            verification_notes=[
                "Z3 reported a candidate model but exact rational "
                "re-substitution did not confirm a violation inside the "
                "declared domain; no counterexample is accepted."
            ],
        )
    return HandlerEvidence(
        outcome=ExperimentOutcome.INCONCLUSIVE,
        evidence_strength=EvidenceStrength.HEURISTIC,
        scope={
            "solver_status": "unknown",
            "timeout_ms": timeout_ms,
            "domains": spec.domains,
        },
        verification_notes=[
            "Z3 returned unknown (timeout or incompleteness); this is not "
            "evidence for either side of the inequality."
        ],
    )
