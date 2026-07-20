from __future__ import annotations

from fractions import Fraction
from typing import Any

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence


Point = tuple[Fraction, Fraction]


def _point(value: Any) -> Point:
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        raise ValueError("each point must contain exactly two coordinates")
    return Fraction(str(value[0])), Fraction(str(value[1]))


def _orientation(a: Point, b: Point, c: Point) -> Fraction:
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def _distance_squared(a: Point, b: Point) -> Fraction:
    return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2


def run_exact_geometry(spec: ExperimentSpec) -> HandlerEvidence:
    args = spec.arguments
    raw_points = args.get("points")
    if not isinstance(raw_points, dict):
        raise ValueError("arguments.points must map names to exact coordinates")
    points = {str(name): _point(value) for name, value in raw_points.items()}
    assertion = args.get("assertion")
    if not isinstance(assertion, dict):
        raise ValueError("arguments.assertion must be a typed mapping")
    kind = str(assertion.get("kind", ""))
    names = [str(name) for name in assertion.get("points", [])]
    if any(name not in points for name in names):
        raise ValueError("geometry assertion references an undeclared point")

    details: dict[str, Any]
    holds: bool
    if kind in {"collinear", "orientation"}:
        if len(names) != 3:
            raise ValueError(f"{kind} requires three points")
        determinant = _orientation(*(points[name] for name in names))
        if kind == "collinear":
            expected = assertion.get("expected", True)
            if not isinstance(expected, bool):
                raise ValueError("collinear expected must be a boolean")
            holds = (determinant == 0) == expected
        else:
            expected_sign = assertion["expected_sign"]
            if isinstance(expected_sign, bool) or not isinstance(expected_sign, int):
                raise ValueError("orientation expected_sign must be -1, 0, or 1")
            if expected_sign not in {-1, 0, 1}:
                raise ValueError("orientation expected_sign must be -1, 0, or 1")
            actual_sign = (determinant > 0) - (determinant < 0)
            holds = actual_sign == expected_sign
        details = {"determinant": str(determinant)}
    elif kind == "equal_distance":
        if len(names) != 4:
            raise ValueError("equal_distance requires points [a,b,c,d]")
        first = _distance_squared(points[names[0]], points[names[1]])
        second = _distance_squared(points[names[2]], points[names[3]])
        holds = first == second
        details = {"first_squared": str(first), "second_squared": str(second)}
    elif kind == "point_on_segment":
        if len(names) != 3:
            raise ValueError("point_on_segment requires points [p,a,b]")
        p, a, b = (points[name] for name in names)
        determinant = _orientation(a, b, p)
        within = min(a[0], b[0]) <= p[0] <= max(a[0], b[0]) and min(a[1], b[1]) <= p[
            1
        ] <= max(a[1], b[1])
        holds = determinant == 0 and within
        details = {"determinant": str(determinant), "within_bounding_box": within}
    else:
        raise ValueError(f"unsupported exact geometry assertion: {kind}")

    if not holds:
        # The exact determinant/distance calculation itself is a concrete refutation.
        return HandlerEvidence(
            outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
            evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
            counterexample={"points": raw_points, "assertion": assertion, **details},
            scope={"coordinate_type": "Fraction"},
            exact_arithmetic=True,
            cases_checked=1,
            independently_verified=True,
            verification_notes=[
                "The assertion was rechecked with exact rational determinants or squared distances."
            ],
        )
    return HandlerEvidence(
        outcome=ExperimentOutcome.CERTIFIED,
        evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
        certificate={"points": raw_points, "assertion": assertion, **details},
        scope={"coordinate_type": "Fraction"},
        exact_arithmetic=True,
        cases_checked=1,
        independently_verified=True,
        verification_notes=[
            "The declared coordinate assertion was checked exactly; this certifies only that assertion."
        ],
    )
