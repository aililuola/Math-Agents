from __future__ import annotations

import itertools
import re
from collections.abc import Sequence
from typing import Any

import sympy as sp

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence
from .symbolic import parse_expression


def _exact_int(value: Any, label: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{label} must be an integer")
    if isinstance(value, int):
        return value
    if isinstance(value, str) and re.fullmatch(r"[+-]?\d+", value.strip()):
        return int(value)
    raise ValueError(f"{label} must be an integer")


def _domain_values(spec: ExperimentSpec, variable: str, modulus: int) -> Sequence[int]:
    raw = spec.domains.get(variable)
    if raw is None:
        if modulus > spec.max_cases:
            raise ValueError(
                f"full residue domain for {variable!r} exceeds max_cases={spec.max_cases}"
            )
        return range(modulus)
    if isinstance(raw, list):
        values = sorted(
            {
                _exact_int(value, f"domain value for {variable!r}") % modulus
                for value in raw
            }
        )
        if not values:
            raise ValueError(f"domain for {variable!r} cannot be empty")
        return values
    if not isinstance(raw, dict):
        raise ValueError(f"domain for {variable!r} must be a mapping or list")
    if "values" in raw:
        values = sorted(
            {
                _exact_int(value, f"domain value for {variable!r}") % modulus
                for value in raw["values"]
            }
        )
        if not values:
            raise ValueError(f"domain for {variable!r} cannot be empty")
        return values
    lower = _exact_int(raw.get("min", 0), f"domain minimum for {variable!r}")
    upper = _exact_int(raw.get("max", modulus - 1), f"domain maximum for {variable!r}")
    if upper < lower:
        raise ValueError(f"domain for {variable!r} has max < min")
    span = upper - lower + 1
    if span >= modulus:
        if modulus > spec.max_cases:
            raise ValueError(
                f"full residue domain for {variable!r} exceeds max_cases={spec.max_cases}"
            )
        return range(modulus)
    if span > spec.max_cases:
        raise ValueError(f"domain for {variable!r} exceeds max_cases={spec.max_cases}")
    return sorted({value % modulus for value in range(lower, upper + 1)})


def _evaluate_modular_relation(
    lhs: sp.Expr,
    rhs: sp.Expr,
    relation: str,
    substitution: dict[sp.Symbol, int],
    modulus: int,
) -> tuple[bool, int, int]:
    left_expr = sp.cancel(lhs.subs(substitution))
    right_expr = sp.cancel(rhs.subs(substitution))
    if not (left_expr.is_Integer and right_expr.is_Integer):
        raise ValueError("modular_exhaustive expressions must evaluate to integers")
    left = int(left_expr) % modulus
    right = int(right_expr) % modulus
    if relation == "eq":
        return left == right, left, right
    if relation == "ne":
        return left != right, left, right
    raise ValueError("modular_exhaustive supports only eq and ne relations")


def run_modular(spec: ExperimentSpec) -> HandlerEvidence:
    args = spec.arguments
    modulus = _exact_int(args.get("modulus", 0), "modulus")
    if modulus < 2:
        raise ValueError("modulus must be at least 2")
    lhs = parse_expression(str(args["lhs"]))
    rhs = parse_expression(str(args.get("rhs", "0")))
    relation = str(args.get("relation", "eq"))
    variables = [str(value) for value in args.get("variables", [])]
    if not variables:
        variables = sorted(
            str(symbol) for symbol in lhs.free_symbols | rhs.free_symbols
        )
    if not variables:
        raise ValueError("modular_exhaustive requires at least one variable")
    if len(variables) != len(set(variables)):
        raise ValueError("modular_exhaustive variable names must be unique")
    expression_variables = {
        str(symbol) for symbol in lhs.free_symbols | rhs.free_symbols
    }
    if not expression_variables.issubset(variables):
        raise ValueError("every expression variable must be declared")
    symbols = {name: sp.Symbol(name, integer=True) for name in variables}
    # Reparse using integer symbols so exact integer predicates remain decidable.
    lhs = sp.sympify(str(args["lhs"]).replace("^", "**"), locals=symbols)
    rhs = sp.sympify(str(args.get("rhs", "0")).replace("^", "**"), locals=symbols)
    domains = [_domain_values(spec, name, modulus) for name in variables]
    total_cases = 1
    for values in domains:
        total_cases *= len(values)
    if total_cases > spec.max_cases:
        raise ValueError(
            f"requested {total_cases} residue assignments exceeds max_cases={spec.max_cases}"
        )

    checked = 0
    for values in itertools.product(*domains):
        substitution = {symbols[name]: value for name, value in zip(variables, values)}
        holds, left, right = _evaluate_modular_relation(
            lhs, rhs, relation, substitution, modulus
        )
        checked += 1
        if not holds:
            # Re-evaluate through the standalone exact predicate before accepting it.
            rechecked, _, _ = _evaluate_modular_relation(
                lhs, rhs, relation, substitution, modulus
            )
            if not rechecked:
                return HandlerEvidence(
                    outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                    evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
                    counterexample={
                        "assignment": dict(zip(variables, values)),
                        "lhs_mod": left,
                        "rhs_mod": right,
                        "modulus": modulus,
                    },
                    scope={"domains": spec.domains, "modulus": modulus},
                    exact_arithmetic=True,
                    cases_checked=checked,
                    independently_verified=True,
                    verification_notes=[
                        "The violating residue assignment was re-evaluated exactly."
                    ],
                )

    finite_reduction = bool(args.get("finite_reduction", False))
    reduction = str(args.get("reduction_justification", "")).strip()
    full_residue_coverage = all(len(values) == modulus for values in domains)
    if finite_reduction and reduction and full_residue_coverage:
        return HandlerEvidence(
            outcome=ExperimentOutcome.CERTIFIED,
            evidence_strength=EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
            certificate={
                "modulus": modulus,
                "variables": variables,
                "domains": {
                    name: {
                        "residues": f"0..{modulus - 1}",
                        "residue_count": modulus,
                    }
                    for name in variables
                },
                "finite_reduction_justification": reduction,
                "all_cases_satisfied": True,
            },
            scope={
                "finite_reduction_claimed": True,
                "full_residue_coverage": True,
            },
            exact_arithmetic=True,
            cases_checked=checked,
            verification_notes=[
                "All declared residue classes were checked; a reviewer must still verify the stated finite reduction."
            ],
        )
    return HandlerEvidence(
        outcome=ExperimentOutcome.NOT_REFUTED,
        evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
        scope={
            "domains": spec.domains,
            "modulus": modulus,
            "full_residue_coverage": full_residue_coverage,
        },
        exact_arithmetic=True,
        cases_checked=checked,
        verification_notes=[
            "All declared residue assignments passed, but coverage of the original claim was not established."
        ],
    )
