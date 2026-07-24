from __future__ import annotations

from fractions import Fraction
from math import gcd
from typing import Any, Callable

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence


def _integer(value: Any, label: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{label} must be an integer")
    parsed = Fraction(str(value))
    if parsed.denominator != 1:
        raise ValueError(f"{label} must be an integer")
    return parsed.numerator


def _exact(value: Any, label: str) -> Fraction:
    if isinstance(value, bool):
        raise ValueError(f"{label} must be an exact number")
    return Fraction(str(value))


def _greedy_predicate(
    args: dict[str, Any],
) -> tuple[str, Callable[[int, list[int]], bool]]:
    rule = str(args.get("rule", "avoid_three_term_arithmetic_progression"))
    if rule == "avoid_forbidden_differences":
        forbidden = {
            abs(_integer(value, "forbidden_differences item"))
            for value in args.get("forbidden_differences", [])
        }
        if not forbidden:
            raise ValueError("forbidden_differences must be non-empty for this rule")
        return rule, lambda candidate, prior: all(
            abs(candidate - value) not in forbidden for value in prior
        )
    if rule == "avoid_three_term_arithmetic_progression":
        return rule, lambda candidate, prior: all(
            left + candidate != 2 * middle
            for left_index, left in enumerate(prior)
            for middle in prior[left_index + 1 :]
        )
    if rule == "coprime_to_all":
        return rule, lambda candidate, prior: all(
            gcd(abs(candidate), abs(value)) == 1 for value in prior if value != 0
        )
    if rule == "gcd_overlap_all_prior":
        return rule, lambda candidate, prior: all(
            gcd(abs(candidate), abs(value)) > 1 for value in prior
        )
    raise ValueError(
        "rule must be avoid_forbidden_differences, "
        "avoid_three_term_arithmetic_progression, coprime_to_all, "
        "or gcd_overlap_all_prior"
    )


def _generate_greedy(
    args: dict[str, Any], *, max_cases: int
) -> tuple[list[int], int, str]:
    values = [
        _integer(value, "initial_values item")
        for value in args.get("initial_values", [])
    ]
    if not values:
        raise ValueError("initial_values must be non-empty")
    length = _integer(args.get("length", len(values)), "length")
    if length < len(values) or length < 1:
        raise ValueError("length must be at least the number of initial values")
    candidate_min = _integer(args.get("candidate_min", 0), "candidate_min")
    candidate_max = _integer(args.get("candidate_max", 1_000_000), "candidate_max")
    if candidate_max < candidate_min:
        raise ValueError("candidate_max must not be below candidate_min")
    strictly_increasing = bool(args.get("strictly_increasing", True))
    rule, predicate = _greedy_predicate(args)
    checked = 0
    while len(values) < length:
        start = candidate_min
        if strictly_increasing:
            start = max(start, values[-1] + 1)
        chosen: int | None = None
        for candidate in range(start, candidate_max + 1):
            checked += 1
            if checked > max_cases:
                raise ValueError("bounded greedy search exceeded max_cases")
            if candidate in values or not predicate(candidate, values):
                continue
            chosen = candidate
            break
        if chosen is None:
            raise ValueError(
                "no admissible next value exists in the declared finite domain"
            )
        values.append(chosen)
    return values, checked, rule


def run_bounded_greedy_sequence(spec: ExperimentSpec) -> HandlerEvidence:
    values, checked, rule = _generate_greedy(spec.arguments, max_cases=spec.max_cases)
    claimed = spec.arguments.get("claimed_values")
    if claimed is not None:
        claimed_values = [_integer(value, "claimed_values item") for value in claimed]
        for index, (actual, expected) in enumerate(zip(values, claimed_values)):
            if actual == expected:
                continue
            replayed, _, _ = _generate_greedy(spec.arguments, max_cases=spec.max_cases)
            if replayed[index] != expected:
                return HandlerEvidence(
                    outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                    evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
                    counterexample={
                        "index": index,
                        "generated": actual,
                        "claimed": expected,
                    },
                    scope={"generated_length": len(values), "rule": rule},
                    exact_arithmetic=True,
                    cases_checked=checked,
                    independently_verified=True,
                    verification_notes=[
                        "The first mismatch was independently regenerated by the same deterministic typed rule."
                    ],
                )
        if len(claimed_values) != len(values):
            index = min(len(claimed_values), len(values))
            return HandlerEvidence(
                outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
                counterexample={
                    "index": index,
                    "generated_length": len(values),
                    "claimed_length": len(claimed_values),
                },
                scope={"generated_length": len(values), "rule": rule},
                exact_arithmetic=True,
                cases_checked=checked,
                independently_verified=True,
                verification_notes=[
                    "The declared sequence lengths differ; the generated prefix was replayed exactly."
                ],
            )
    return HandlerEvidence(
        outcome=ExperimentOutcome.NOT_REFUTED,
        evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
        certificate={"values": values, "rule": rule},
        scope={"generated_length": len(values), "rule": rule},
        exact_arithmetic=True,
        cases_checked=checked,
        verification_notes=[
            "This is only a deterministic finite prefix; it does not prove an infinite pattern."
        ],
    )


def run_candidate_period_check(spec: ExperimentSpec) -> HandlerEvidence:
    raw_values = spec.arguments.get("values", [])
    values = [_exact(value, "values item") for value in raw_values]
    if not values:
        raise ValueError("values must be non-empty")
    period = _integer(spec.arguments.get("candidate_period"), "candidate_period")
    start = _integer(spec.arguments.get("start_index", 0), "start_index")
    if period <= 0 or start < 0 or start + period >= len(values):
        raise ValueError("candidate_period/start_index leave no comparable pair")
    comparisons = len(values) - (start + period)
    if comparisons > spec.max_cases:
        raise ValueError("period check exceeds max_cases")
    for index in range(start + period, len(values)):
        prior_index = index - period
        if values[index] == values[prior_index]:
            continue
        # Independent direct re-read guards against a stale loop state or index bug.
        current = _exact(raw_values[index], "rechecked current value")
        prior = _exact(raw_values[prior_index], "rechecked prior value")
        if current != prior:
            return HandlerEvidence(
                outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
                counterexample={
                    "index": index,
                    "prior_index": prior_index,
                    "value": str(current),
                    "prior_value": str(prior),
                    "candidate_period": period,
                },
                scope={
                    "start_index": start,
                    "end_index": len(values) - 1,
                    "candidate_period": period,
                },
                exact_arithmetic=True,
                cases_checked=index - (start + period) + 1,
                independently_verified=True,
                verification_notes=[
                    "The violating pair was independently re-read with exact rational arithmetic."
                ],
            )
    return HandlerEvidence(
        outcome=ExperimentOutcome.NOT_REFUTED,
        evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
        certificate={
            "candidate_period": period,
            "matching_comparisons": comparisons,
        },
        scope={
            "start_index": start,
            "end_index": len(values) - 1,
            "candidate_period": period,
        },
        exact_arithmetic=True,
        cases_checked=comparisons,
        verification_notes=[
            "The candidate period matched only this finite list and remains not_refuted, not proved."
        ],
    )
