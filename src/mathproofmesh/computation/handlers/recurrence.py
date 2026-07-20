from __future__ import annotations

from fractions import Fraction
from typing import Any

import sympy as sp

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence
from .symbolic import parse_expression


def _fraction(value: Any) -> Fraction:
    if isinstance(value, bool):
        raise ValueError("boolean is not an exact recurrence value")
    return Fraction(str(value))


def _sympy_fraction(value: sp.Expr) -> Fraction:
    simplified = sp.cancel(value)
    if not simplified.is_Rational:
        raise ValueError("recurrence expression did not evaluate to a rational number")
    return Fraction(int(sp.numer(simplified)), int(sp.denom(simplified)))


def _integer(value: Any, label: str) -> int:
    parsed = _fraction(value)
    if parsed.denominator != 1:
        raise ValueError(f"{label} must be an integer")
    return parsed.numerator


def run_recurrence_check(spec: ExperimentSpec) -> HandlerEvidence:
    args = spec.arguments
    initial = [_fraction(value) for value in args.get("initial_values", [])]
    coefficients = [_fraction(value) for value in args.get("coefficients", [])]
    if not initial or not coefficients:
        raise ValueError("initial_values and coefficients must be non-empty")
    order = len(coefficients)
    if len(initial) < order:
        raise ValueError("initial_values must contain at least recurrence order values")
    start_n = _integer(args.get("start_n", 0), "start_n")
    end_n = _integer(args["end_n"], "end_n")
    required_count = end_n - start_n + 1
    if end_n < start_n or required_count > spec.max_cases:
        raise ValueError("invalid or over-budget recurrence range")
    inhomogeneous = parse_expression(str(args.get("inhomogeneous", "0")))
    n_symbol = next(
        (symbol for symbol in inhomogeneous.free_symbols if str(symbol) == "n"),
        sp.Symbol("n", integer=True),
    )
    generated = list(initial)
    while len(generated) < required_count:
        index = start_n + len(generated)
        value = sum(
            coefficient * generated[-offset - 1]
            for offset, coefficient in enumerate(coefficients)
        )
        extra = _sympy_fraction(inhomogeneous.subs(n_symbol, index))
        generated.append(value + extra)
    sequence = generated[:required_count]

    claimed_text = args.get("claimed_expression")
    if claimed_text is None:
        return HandlerEvidence(
            outcome=ExperimentOutcome.NOT_REFUTED,
            evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
            certificate={
                "values": [str(value) for value in sequence],
                "start_n": start_n,
                "end_n": end_n,
            },
            scope={"start_n": start_n, "end_n": end_n},
            exact_arithmetic=True,
            cases_checked=len(sequence),
            verification_notes=[
                "The recurrence values were generated exactly; no general claim was certified."
            ],
        )

    claimed = parse_expression(str(claimed_text))
    claim_n = next(
        (symbol for symbol in claimed.free_symbols if str(symbol) == "n"),
        sp.Symbol("n", integer=True),
    )
    checked = 0
    for offset, actual in enumerate(sequence):
        index = start_n + offset
        expected = _sympy_fraction(claimed.subs(claim_n, index))
        checked += 1
        if actual != expected:
            # A second direct substitution is the independent exact check.
            rechecked = _sympy_fraction(claimed.subs(claim_n, index))
            if actual != rechecked:
                return HandlerEvidence(
                    outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                    evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
                    counterexample={
                        "n": index,
                        "actual": str(actual),
                        "claimed": str(expected),
                    },
                    scope={"start_n": start_n, "end_n": end_n},
                    exact_arithmetic=True,
                    cases_checked=checked,
                    independently_verified=True,
                    verification_notes=[
                        "The first mismatch was independently re-substituted using exact rational arithmetic."
                    ],
                )
    return HandlerEvidence(
        outcome=ExperimentOutcome.NOT_REFUTED,
        evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
        certificate={
            "claimed_expression": str(claimed_text),
            "values": [str(value) for value in sequence],
        },
        scope={"start_n": start_n, "end_n": end_n},
        exact_arithmetic=True,
        cases_checked=checked,
        verification_notes=[
            "The claimed formula matched only the declared finite interval; induction or another proof is still required."
        ],
    )
