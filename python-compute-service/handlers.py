"""Restricted SymPy and Z3 handlers for the MathProofMesh sidecar."""

from __future__ import annotations

import ast
import math
import random
from fractions import Fraction
from typing import Any, Callable

import sympy as sp
import z3


ALLOWED_FUNCTIONS: dict[str, Any] = {
    "sin": sp.sin,
    "cos": sp.cos,
    "tan": sp.tan,
    "asin": sp.asin,
    "acos": sp.acos,
    "atan": sp.atan,
    "exp": sp.exp,
    "log": sp.log,
    "sqrt": sp.sqrt,
    "Abs": sp.Abs,
    "factorial": sp.factorial,
    "floor": sp.floor,
    "ceiling": sp.ceiling,
    "Min": sp.Min,
    "Max": sp.Max,
}
ALLOWED_CONSTANTS: dict[str, Any] = {"pi": sp.pi, "E": sp.E, "I": sp.I}
ALLOWED_AST_NODES = (
    ast.Expression,
    ast.BinOp,
    ast.UnaryOp,
    ast.Add,
    ast.Sub,
    ast.Mult,
    ast.Div,
    ast.FloorDiv,
    ast.Pow,
    ast.Mod,
    ast.UAdd,
    ast.USub,
    ast.Call,
    ast.Name,
    ast.Load,
    ast.Constant,
    ast.Tuple,
    ast.List,
)
RELATIONS = {"eq", "ne", "le", "lt", "ge", "gt"}
DOMAIN_KEYS = {
    "min",
    "max",
    "min_exclusive",
    "max_exclusive",
    "positive",
    "nonnegative",
    "nonzero",
}


class UnsafeExpressionError(ValueError):
    """Expression is outside the sidecar's mathematical AST allowlist."""


class UnsupportedRealOperationError(ValueError):
    """Expression is outside the supported QF_NRA fragment."""


def parse_expression(expression: str) -> sp.Expr:
    if not isinstance(expression, str) or not expression.strip():
        raise ValueError("expression must be a non-empty string")
    normalized = expression.replace("^", "**")
    tree = ast.parse(normalized, mode="eval")
    names: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ALLOWED_AST_NODES):
            raise UnsafeExpressionError(f"disallowed syntax: {type(node).__name__}")
        if isinstance(node, ast.Name):
            if node.id.startswith("_") or "__" in node.id:
                raise UnsafeExpressionError("private/dunder names are forbidden")
            names.add(node.id)
        if isinstance(node, ast.Call):
            if (
                not isinstance(node.func, ast.Name)
                or node.func.id not in ALLOWED_FUNCTIONS
            ):
                raise UnsafeExpressionError(
                    "only whitelisted mathematical functions are allowed"
                )
            if node.keywords:
                raise UnsafeExpressionError("keyword arguments are forbidden")
        if isinstance(node, ast.Constant) and (
            isinstance(node.value, bool)
            or not isinstance(node.value, (int, float, complex))
        ):
            raise UnsafeExpressionError("only numeric constants are allowed")
    local_values: dict[str, Any] = {**ALLOWED_FUNCTIONS, **ALLOWED_CONSTANTS}
    for name in names:
        if name not in local_values:
            local_values[name] = sp.Symbol(name, real=True)
    return sp.sympify(
        normalized,
        locals=local_values,
        evaluate=True,
        rational=True,
    )


def relation_holds_exact(lhs: sp.Expr, rhs: sp.Expr, relation: str) -> bool:
    difference = sp.simplify(lhs - rhs)
    if relation == "eq":
        if difference.is_zero is None:
            raise ValueError("exact equality is undecidable for this substitution")
        return bool(difference.is_zero)
    if relation == "ne":
        if difference.is_zero is None:
            raise ValueError("exact inequality is undecidable for this substitution")
        return not bool(difference.is_zero)
    if not (lhs.is_real and rhs.is_real):
        raise ValueError("ordered relations require real values")
    comparisons = {
        "le": lhs <= rhs,
        "lt": lhs < rhs,
        "ge": lhs >= rhs,
        "gt": lhs > rhs,
    }
    if relation not in comparisons:
        raise ValueError(f"unsupported relation: {relation}")
    comparison = comparisons[relation]
    if comparison is sp.S.true:
        return True
    if comparison is sp.S.false:
        return False
    raise ValueError("exact ordered relation is undecidable for this substitution")


def execute(method: str, params: dict[str, Any], limits: dict[str, Any]) -> dict[str, Any]:
    if method == "sympy_simplify":
        expression = str(params["expression"])
        parsed = parse_expression(expression)
        simplified = sp.simplify(parsed)
        certificate = {
            "input": expression,
            "parsed": str(parsed),
            "simplified": str(simplified),
            "is_zero": bool(simplified == 0),
        }
        return evidence(
            "certified",
            "formal_certificate",
            certificate=certificate,
            exact_arithmetic=True,
            notes=["SymPy simplified the parsed expression exactly."],
        )
    if method == "sympy_equivalent":
        lhs = parse_expression(str(params["lhs"]))
        rhs = parse_expression(str(params["rhs"]))
        difference = sp.simplify(lhs - rhs)
        certificate = {
            "lhs": str(lhs),
            "rhs": str(rhs),
            "difference": str(difference),
            "equivalent": bool(difference == 0),
        }
        if certificate["equivalent"]:
            return evidence(
                "certified",
                "formal_certificate",
                certificate=certificate,
                exact_arithmetic=True,
                notes=["The symbolic difference simplified exactly to zero."],
            )
        return evidence(
            "inconclusive",
            "heuristic",
            certificate=certificate,
            exact_arithmetic=True,
            notes=[
                "The expressions did not simplify to the same form; no concrete counterexample was certified."
            ],
        )
    if method == "polynomial_factor":
        expression = parse_expression(str(params["expression"]))
        certificate = {
            "expanded": str(sp.expand(expression)),
            "factored": str(sp.factor(expression)),
        }
        return evidence(
            "certified",
            "formal_certificate",
            certificate=certificate,
            exact_arithmetic=True,
            notes=["Expanded and factored forms were computed symbolically."],
        )
    if method == "numeric_counterexample":
        return numeric_counterexample(params, limits)
    if method == "real_inequality":
        return real_inequality(params, limits)
    raise KeyError(method)


def numeric_counterexample(
    params: dict[str, Any], limits: dict[str, Any]
) -> dict[str, Any]:
    lhs = parse_expression(str(params["lhs"]))
    rhs = parse_expression(str(params["rhs"]))
    relation = str(params.get("relation", "eq"))
    if relation not in RELATIONS:
        raise ValueError("unsupported relation")
    variables = [
        str(value)
        for value in (
            params.get("variables")
            or sorted(str(symbol) for symbol in lhs.free_symbols | rhs.free_symbols)
        )
    ]
    if len(variables) != len(set(variables)):
        raise ValueError("variables must be unique")
    ranges = params.get("ranges") or {}
    if not isinstance(ranges, dict):
        raise ValueError("ranges must be an object")
    maximum = int(limits.get("max_cases", 200))
    samples = min(10_000, maximum, max(1, int(params.get("samples", 200))))
    tolerance = float(params.get("tolerance", 1e-8))
    if not math.isfinite(tolerance) or tolerance < 0:
        raise ValueError("numeric comparison tolerance must be nonnegative")
    random_source = random.Random(int(limits.get("seed", 20260719)))
    symbols = {str(symbol): symbol for symbol in lhs.free_symbols | rhs.free_symbols}
    for name in variables:
        symbols.setdefault(name, sp.Symbol(name, real=True))

    def approximate_holds(left: complex, right: complex) -> bool:
        scale = tolerance * max(1.0, abs(left), abs(right))
        if relation == "eq":
            return abs(left - right) <= scale
        if relation == "ne":
            return abs(left - right) > scale
        if abs(left.imag) > tolerance or abs(right.imag) > tolerance:
            return False
        comparisons: dict[str, Callable[[], bool]] = {
            "le": lambda: left.real <= right.real + tolerance,
            "lt": lambda: left.real < right.real - tolerance,
            "ge": lambda: left.real + tolerance >= right.real,
            "gt": lambda: left.real - tolerance > right.real,
        }
        return comparisons[relation]()

    checked = 0
    skipped = 0
    for _ in range(samples):
        substitution: dict[sp.Symbol, sp.Rational] = {}
        readable: dict[str, str] = {}
        for name in variables:
            raw_range = ranges.get(name, [-5, 5])
            if not isinstance(raw_range, list) or len(raw_range) != 2:
                raise ValueError(f"range for {name!r} must be [lower, upper]")
            lower = sp.Rational(str(raw_range[0]))
            upper = sp.Rational(str(raw_range[1]))
            if upper < lower:
                raise ValueError(f"range for {name!r} has upper < lower")
            value = lower + (upper - lower) * sp.Rational(
                random_source.randint(0, 1000), 1000
            )
            substitution[symbols[name]] = value
            readable[name] = str(value)
        try:
            exact_lhs = sp.simplify(lhs.subs(substitution))
            exact_rhs = sp.simplify(rhs.subs(substitution))
            left = complex(sp.N(exact_lhs, 50))
            right = complex(sp.N(exact_rhs, 50))
            if not all(
                math.isfinite(value)
                for value in (left.real, left.imag, right.real, right.imag)
            ):
                skipped += 1
                continue
        except (ArithmeticError, TypeError, ValueError):
            skipped += 1
            continue
        checked += 1
        if not approximate_holds(left, right):
            try:
                independently_refuted = not relation_holds_exact(
                    exact_lhs, exact_rhs, relation
                )
            except ValueError:
                skipped += 1
                continue
            if independently_refuted:
                return evidence(
                    "counterexample_found",
                    "counterexample",
                    counterexample={
                        "assignment": readable,
                        "lhs_value": str(exact_lhs),
                        "rhs_value": str(exact_rhs),
                    },
                    scope={"sampling": "seeded rational", "skipped": skipped},
                    cases_checked=checked,
                    independently_verified=True,
                    notes=[
                        "A re-evaluated counterexample refutes the stated claim."
                    ],
                )
    return evidence(
        "not_refuted",
        "heuristic",
        scope={"sampling": "seeded rational", "skipped": skipped},
        cases_checked=checked,
        notes=["Random testing found no counterexample; this is not a proof."],
    )


def real_inequality(
    params: dict[str, Any], limits: dict[str, Any]
) -> dict[str, Any]:
    relation = str(params.get("relation", "ge"))
    if relation not in RELATIONS:
        raise ValueError("unsupported relation")
    timeout_ms = min(
        60_000,
        max(1, int(params.get("max_runtime_ms", limits.get("timeout_ms", 10_000)))),
    )
    lhs = parse_expression(str(params["lhs"]))
    rhs = parse_expression(str(params.get("rhs", "0")))
    declared = [str(value) for value in params.get("variables", [])]
    free_names = sorted(str(symbol) for symbol in lhs.free_symbols | rhs.free_symbols)
    variables = declared or free_names
    if len(variables) != len(set(variables)):
        raise ValueError("real_inequality variable names must be unique")
    if not set(free_names).issubset(variables):
        raise ValueError("every expression variable must be declared")
    raw_domains = params.get("domains", {})
    if not isinstance(raw_domains, dict):
        raise ValueError("domains must be an object")
    if not set(raw_domains).issubset(variables):
        raise ValueError("domain declared for undeclared variable")
    domains = {name: parse_domain(raw_domains.get(name), name) for name in variables}
    z3_variables = {name: z3.Real(name) for name in variables}
    try:
        z3_lhs = to_z3_real(lhs, z3_variables)
        z3_rhs = to_z3_real(rhs, z3_variables)
    except UnsupportedRealOperationError as exc:
        return evidence(
            "inconclusive",
            "heuristic",
            scope={"relation": relation, "domains": raw_domains},
            notes=[f"Unsupported QF_NRA operation: {exc}."],
        )
    solver = z3.Solver()
    solver.set("timeout", timeout_ms)
    solver.set("random_seed", int(limits.get("seed", 20260719)))
    for name in variables:
        solver.add(*domain_constraints(domains[name], z3_variables[name]))
    solver.add(z3.Not(z3_relation(z3_lhs, z3_rhs, relation)))
    status = solver.check()
    claim = f"{params['lhs']} {relation} {params.get('rhs', '0')}"
    if status == z3.unsat:
        return evidence(
            "certified",
            "formal_certificate",
            certificate={
                "claim": claim,
                "relation": relation,
                "domains": raw_domains,
                "solver_status": "unsat",
                "timeout_ms": timeout_ms,
                "logic": "QF_NRA",
            },
            scope={
                "quantification": "universal over the declared real domain",
                "domains": raw_domains,
            },
            exact_arithmetic=True,
            notes=[
                "Z3 proved the negation unsatisfiable in QF_NRA over exactly the declared real domain.",
                "Trust rests on Z3's nonlinear-real-arithmetic decision procedure.",
            ],
        )
    if status == z3.sat:
        model = solver.model()
        assignment: dict[str, Fraction] = {}
        approximated = False
        try:
            for name in variables:
                value, approximate = model_fraction(
                    model.eval(z3_variables[name], model_completion=True)
                )
                assignment[name] = value
                approximated = approximated or approximate
        except ValueError as exc:
            return evidence(
                "inconclusive",
                "heuristic",
                scope={"relation": relation, "domains": raw_domains},
                notes=[f"Z3 model was not exactly interpretable: {exc}."],
            )
        in_domain = all(
            point_in_domain(domains[name], assignment[name]) for name in variables
        )
        substitutions = {
            symbol: sp.Rational(
                assignment[str(symbol)].numerator,
                assignment[str(symbol)].denominator,
            )
            for symbol in lhs.free_symbols | rhs.free_symbols
        }
        if in_domain:
            try:
                lhs_value = sp.simplify(lhs.subs(substitutions))
                rhs_value = sp.simplify(rhs.subs(substitutions))
                confirmed = not relation_holds_exact(lhs_value, rhs_value, relation)
            except ValueError:
                confirmed = False
            if confirmed:
                notes = [
                    "The Z3 model was independently re-substituted with exact SymPy rational arithmetic."
                ]
                if approximated:
                    notes.append(
                        "An exact rational approximation of an algebraic model value was independently verified."
                    )
                return evidence(
                    "counterexample_found",
                    "counterexample",
                    counterexample={
                        "assignment": {
                            name: str(value) for name, value in assignment.items()
                        },
                        "lhs_value": str(lhs_value),
                        "rhs_value": str(rhs_value),
                        "relation": relation,
                    },
                    scope={"domains": raw_domains},
                    exact_arithmetic=True,
                    cases_checked=1,
                    independently_verified=True,
                    notes=notes,
                )
        return evidence(
            "inconclusive",
            "heuristic",
            scope={
                "relation": relation,
                "domains": raw_domains,
                "candidate_assignment": {
                    name: str(value) for name, value in assignment.items()
                },
            },
            notes=[
                "Z3 returned a model but exact rational replay did not confirm a domain-valid violation."
            ],
        )
    return evidence(
        "inconclusive",
        "heuristic",
        scope={
            "solver_status": "unknown",
            "timeout_ms": timeout_ms,
            "domains": raw_domains,
        },
        notes=["Z3 returned unknown; this supports neither side of the inequality."],
    )


def to_z3_real(expr: sp.Expr, variables: dict[str, Any]) -> Any:
    if isinstance(expr, sp.Symbol):
        name = str(expr)
        if name not in variables:
            raise UnsupportedRealOperationError(f"undeclared variable: {name}")
        return variables[name]
    if isinstance(expr, sp.Rational):
        return z3.RealVal(f"{expr.p}/{expr.q}")
    if isinstance(expr, sp.Add):
        terms = [to_z3_real(term, variables) for term in expr.args]
        return sum(terms[1:], terms[0])
    if isinstance(expr, sp.Mul):
        factors = [to_z3_real(factor, variables) for factor in expr.args]
        result = factors[0]
        for factor in factors[1:]:
            result *= factor
        return result
    if isinstance(expr, sp.Pow):
        if not isinstance(expr.exp, sp.Integer) or abs(int(expr.exp)) > 16:
            raise UnsupportedRealOperationError("integer exponent must have |e| <= 16")
        power = int(expr.exp)
        base = to_z3_real(expr.base, variables)
        if power == 0:
            return z3.RealVal(1)
        result = base
        for _ in range(abs(power) - 1):
            result *= base
        return result if power > 0 else z3.RealVal(1) / result
    if isinstance(expr, (sp.Min, sp.Max)):
        terms = [to_z3_real(term, variables) for term in expr.args]
        result = terms[0]
        for term in terms[1:]:
            result = (
                z3.If(result <= term, result, term)
                if isinstance(expr, sp.Min)
                else z3.If(result >= term, result, term)
            )
        return result
    if isinstance(expr, sp.Abs):
        inner = to_z3_real(expr.args[0], variables)
        return z3.If(inner >= 0, inner, -inner)
    raise UnsupportedRealOperationError(type(expr).__name__)


def z3_relation(left: Any, right: Any, relation: str) -> Any:
    return {
        "eq": left == right,
        "ne": left != right,
        "le": left <= right,
        "lt": left < right,
        "ge": left >= right,
        "gt": left > right,
    }[relation]


def parse_domain(raw: Any, name: str) -> dict[str, Any]:
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        raise ValueError(f"domain for {name!r} must be an object")
    unknown = sorted(set(raw) - DOMAIN_KEYS)
    if unknown:
        raise ValueError(f"unsupported domain keys: {', '.join(unknown)}")
    result: dict[str, Any] = {}
    for key in ("min", "max"):
        if key in raw:
            if isinstance(raw[key], (bool, float)):
                raise ValueError("domain bounds must be exact rationals")
            result[key] = Fraction(str(raw[key]))
    for key in (
        "min_exclusive",
        "max_exclusive",
        "positive",
        "nonnegative",
        "nonzero",
    ):
        if key in raw:
            if not isinstance(raw[key], bool):
                raise ValueError("domain flags must be boolean")
            result[key] = raw[key]
    if "min" in result and "max" in result and result["max"] < result["min"]:
        raise ValueError(f"domain for {name!r} has max < min")
    return result


def domain_constraints(domain: dict[str, Any], variable: Any) -> list[Any]:
    result: list[Any] = []
    if "min" in domain:
        bound = z3.RealVal(
            f"{domain['min'].numerator}/{domain['min'].denominator}"
        )
        result.append(
            variable > bound if domain.get("min_exclusive") else variable >= bound
        )
    if "max" in domain:
        bound = z3.RealVal(
            f"{domain['max'].numerator}/{domain['max'].denominator}"
        )
        result.append(
            variable < bound if domain.get("max_exclusive") else variable <= bound
        )
    if domain.get("positive"):
        result.append(variable > 0)
    if domain.get("nonnegative"):
        result.append(variable >= 0)
    if domain.get("nonzero"):
        result.append(variable != 0)
    return result


def point_in_domain(domain: dict[str, Any], value: Fraction) -> bool:
    if "min" in domain:
        if domain.get("min_exclusive") and not value > domain["min"]:
            return False
        if not domain.get("min_exclusive") and not value >= domain["min"]:
            return False
    if "max" in domain:
        if domain.get("max_exclusive") and not value < domain["max"]:
            return False
        if not domain.get("max_exclusive") and not value <= domain["max"]:
            return False
    return not (
        (domain.get("positive") and not value > 0)
        or (domain.get("nonnegative") and not value >= 0)
        or (domain.get("nonzero") and value == 0)
    )


def model_fraction(value: Any) -> tuple[Fraction, bool]:
    if isinstance(value, z3.RatNumRef):
        return Fraction(value.as_fraction()), False
    if isinstance(value, z3.AlgebraicNumRef):
        return Fraction(value.approx(40).as_fraction()), True
    raise ValueError(type(value).__name__)


def evidence(
    outcome: str,
    strength: str,
    *,
    scope: dict[str, Any] | None = None,
    counterexample: dict[str, Any] | None = None,
    certificate: dict[str, Any] | None = None,
    exact_arithmetic: bool = False,
    cases_checked: int = 0,
    independently_verified: bool = False,
    notes: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "outcome": outcome,
        "evidence_strength": strength,
        "scope": scope or {},
        "counterexample": counterexample,
        "certificate": certificate,
        "exact_arithmetic": exact_arithmetic,
        "cases_checked": cases_checked,
        "independently_verified": independently_verified,
        "verification_notes": notes or [],
    }
