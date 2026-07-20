from __future__ import annotations

import ast
import math
import random
from typing import Any, Callable

import sympy as sp

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence


_ALLOWED_FUNCTIONS: dict[str, Any] = {
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
_ALLOWED_CONSTANTS: dict[str, Any] = {"pi": sp.pi, "E": sp.E, "I": sp.I}
_ALLOWED_AST_NODES = (
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


class UnsafeExpressionError(ValueError):
    pass


def _validate_expression_ast(expression: str) -> set[str]:
    normalized = expression.replace("^", "**")
    tree = ast.parse(normalized, mode="eval")
    names: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, _ALLOWED_AST_NODES):
            raise UnsafeExpressionError(f"disallowed syntax: {type(node).__name__}")
        if isinstance(node, ast.Name):
            if node.id.startswith("_") or "__" in node.id:
                raise UnsafeExpressionError("private/dunder names are forbidden")
            names.add(node.id)
        if isinstance(node, ast.Call):
            if (
                not isinstance(node.func, ast.Name)
                or node.func.id not in _ALLOWED_FUNCTIONS
            ):
                raise UnsafeExpressionError(
                    "only whitelisted mathematical functions are allowed"
                )
            if node.keywords:
                raise UnsafeExpressionError("keyword arguments are forbidden")
        if isinstance(node, ast.Constant) and not isinstance(
            node.value, (int, float, complex)
        ):
            raise UnsafeExpressionError("only numeric constants are allowed")
    return names


def parse_expression(expression: str) -> sp.Expr:
    names = _validate_expression_ast(expression)
    locals_dict: dict[str, Any] = {**_ALLOWED_FUNCTIONS, **_ALLOWED_CONSTANTS}
    for name in names:
        if name not in locals_dict:
            locals_dict[name] = sp.Symbol(name, real=True)
    return sp.sympify(
        expression.replace("^", "**"),
        locals=locals_dict,
        evaluate=True,
        rational=True,
    )


def relation_holds_exact(lhs: sp.Expr, rhs: sp.Expr, relation: str) -> bool:
    difference = sp.simplify(lhs - rhs)
    zero_status = difference.is_zero
    if relation == "eq":
        if zero_status is None:
            raise ValueError("exact equality is undecidable for this substitution")
        return bool(zero_status)
    if relation == "ne":
        if zero_status is None:
            raise ValueError("exact inequality is undecidable for this substitution")
        return not zero_status
    if not (lhs.is_real and rhs.is_real):
        raise ValueError("ordered relations require real values")
    comparison: Any
    if relation == "le":
        comparison = lhs <= rhs
    elif relation == "lt":
        comparison = lhs < rhs
    elif relation == "ge":
        comparison = lhs >= rhs
    elif relation == "gt":
        comparison = lhs > rhs
    else:
        raise ValueError(f"unsupported relation: {relation}")
    if comparison is sp.S.true:
        return True
    if comparison is sp.S.false:
        return False
    raise ValueError("exact ordered relation is undecidable for this substitution")


def sympy_simplify_payload(args: dict[str, Any]) -> dict[str, Any]:
    expression = str(args["expression"])
    parsed = parse_expression(expression)
    simplified = sp.simplify(parsed)
    return {
        "input": expression,
        "parsed": str(parsed),
        "simplified": str(simplified),
        "is_zero": bool(simplified == 0),
    }


def sympy_equivalent_payload(args: dict[str, Any]) -> dict[str, Any]:
    lhs = parse_expression(str(args["lhs"]))
    rhs = parse_expression(str(args["rhs"]))
    difference = sp.simplify(lhs - rhs)
    return {
        "lhs": str(lhs),
        "rhs": str(rhs),
        "difference": str(difference),
        "equivalent": bool(difference == 0),
    }


def polynomial_factor_payload(args: dict[str, Any]) -> dict[str, Any]:
    expression = parse_expression(str(args["expression"]))
    return {
        "expanded": str(sp.expand(expression)),
        "factored": str(sp.factor(expression)),
    }


def numeric_counterexample_payload(
    args: dict[str, Any], random_source: random.Random
) -> dict[str, Any]:
    lhs = parse_expression(str(args["lhs"]))
    rhs = parse_expression(str(args["rhs"]))
    relation = str(args.get("relation", "eq"))
    variables = [
        str(value)
        for value in (
            args.get("variables")
            or sorted(str(symbol) for symbol in lhs.free_symbols | rhs.free_symbols)
        )
    ]
    ranges = args.get("ranges") or {}
    samples = min(10_000, max(1, int(args.get("samples", 200))))
    tolerance = float(args.get("tolerance", 1e-8))
    if tolerance < 0:
        raise ValueError("numeric comparison tolerance must be nonnegative")
    symbols = {str(symbol): symbol for symbol in lhs.free_symbols | rhs.free_symbols}
    for name in variables:
        symbols.setdefault(str(name), sp.Symbol(str(name), real=True))

    def approximate_holds(left: complex, right: complex) -> bool:
        if relation == "eq":
            return abs(left - right) <= tolerance * max(1.0, abs(left), abs(right))
        if relation == "ne":
            return abs(left - right) > tolerance * max(1.0, abs(left), abs(right))
        if abs(left.imag) > tolerance or abs(right.imag) > tolerance:
            return False
        comparisons: dict[str, Callable[[], bool]] = {
            "le": lambda: left.real <= right.real + tolerance,
            "lt": lambda: left.real < right.real - tolerance,
            "ge": lambda: left.real + tolerance >= right.real,
            "gt": lambda: left.real - tolerance > right.real,
        }
        if relation not in comparisons:
            raise ValueError(f"unsupported relation: {relation}")
        return comparisons[relation]()

    checked = 0
    skipped = 0
    for _ in range(samples):
        substitution: dict[sp.Symbol, sp.Rational] = {}
        readable: dict[str, str] = {}
        for name in variables:
            raw_range = ranges.get(name, [-5, 5])
            if not isinstance(raw_range, (list, tuple)) or len(raw_range) != 2:
                raise ValueError(f"range for {name!r} must be [lower, upper]")
            lower = sp.Rational(str(raw_range[0]))
            upper = sp.Rational(str(raw_range[1]))
            if upper < lower:
                raise ValueError(f"range for {name!r} has upper < lower")
            grid_position = random_source.randint(0, 1000)
            value = lower + (upper - lower) * sp.Rational(grid_position, 1000)
            substitution[symbols[name]] = value
            readable[name] = str(value)
        try:
            exact_lhs = sp.simplify(lhs.subs(substitution))
            exact_rhs = sp.simplify(rhs.subs(substitution))
            left = complex(sp.N(exact_lhs, 50))
            right = complex(sp.N(exact_rhs, 50))
            if not all(
                math.isfinite(value)
                for value in [left.real, left.imag, right.real, right.imag]
            ):
                skipped += 1
                continue
        except Exception:
            skipped += 1
            continue
        checked += 1
        if not approximate_holds(left, right):
            # This second exact substitution is the trusted counterexample check.
            try:
                independently_refuted = not relation_holds_exact(
                    exact_lhs, exact_rhs, relation
                )
            except ValueError:
                skipped += 1
                continue
            if independently_refuted:
                return {
                    "counterexample_found": True,
                    "assignment": readable,
                    "lhs_value": str(exact_lhs),
                    "rhs_value": str(exact_rhs),
                    "checked": checked,
                    "skipped": skipped,
                    "independently_verified": True,
                    "warning": "A re-evaluated counterexample refutes the stated claim.",
                }
    return {
        "counterexample_found": False,
        "checked": checked,
        "skipped": skipped,
        "independently_verified": False,
        "warning": "Random testing found no counterexample; this is not a proof.",
    }


def run_symbolic(spec: ExperimentSpec, random_source: random.Random) -> HandlerEvidence:
    method = spec.method.value
    if method == "sympy_simplify":
        payload = sympy_simplify_payload(spec.arguments)
        return HandlerEvidence(
            outcome=ExperimentOutcome.CERTIFIED,
            evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
            certificate=payload,
            exact_arithmetic=True,
            verification_notes=["SymPy simplified the parsed expression exactly."],
        )
    if method == "polynomial_factor":
        payload = polynomial_factor_payload(spec.arguments)
        return HandlerEvidence(
            outcome=ExperimentOutcome.CERTIFIED,
            evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
            certificate=payload,
            exact_arithmetic=True,
            verification_notes=[
                "Expanded and factored forms were computed symbolically."
            ],
        )
    if method == "sympy_equivalent":
        payload = sympy_equivalent_payload(spec.arguments)
        if payload["equivalent"]:
            return HandlerEvidence(
                outcome=ExperimentOutcome.CERTIFIED,
                evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
                certificate=payload,
                exact_arithmetic=True,
                verification_notes=[
                    "The symbolic difference simplified exactly to zero."
                ],
            )
        return HandlerEvidence(
            outcome=ExperimentOutcome.INCONCLUSIVE,
            evidence_strength=EvidenceStrength.HEURISTIC,
            certificate=payload,
            exact_arithmetic=True,
            verification_notes=[
                "The expressions did not simplify to the same form; no concrete counterexample was certified."
            ],
        )
    if method == "numeric_counterexample":
        arguments = dict(spec.arguments)
        arguments["samples"] = min(int(arguments.get("samples", 200)), spec.max_cases)
        payload = numeric_counterexample_payload(arguments, random_source)
        if payload["counterexample_found"]:
            return HandlerEvidence(
                outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
                counterexample={
                    "assignment": payload["assignment"],
                    "lhs_value": payload["lhs_value"],
                    "rhs_value": payload["rhs_value"],
                },
                scope={"sampling": "seeded rational", "skipped": payload["skipped"]},
                cases_checked=payload["checked"],
                independently_verified=True,
                verification_notes=[payload["warning"]],
            )
        return HandlerEvidence(
            outcome=ExperimentOutcome.NOT_REFUTED,
            evidence_strength=EvidenceStrength.HEURISTIC,
            scope={"sampling": "seeded rational", "skipped": payload["skipped"]},
            cases_checked=payload["checked"],
            independently_verified=False,
            verification_notes=[payload["warning"]],
        )
    raise ValueError(f"unsupported symbolic method: {method}")
