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


def _concyclic_determinant(a: Point, b: Point, c: Point, d: Point) -> Fraction:
    """Exact 3x3 reduction of the 4x4 concyclicity determinant.

    After translating by ``a``, the rows are (x, y, x^2 + y^2); the
    determinant vanishes exactly when the four points lie on one common
    circle or line.
    """

    rows = []
    for point in (b, c, d):
        dx = point[0] - a[0]
        dy = point[1] - a[1]
        rows.append((dx, dy, dx * dx + dy * dy))
    (r0, r1, r2) = rows
    return (
        r0[0] * (r1[1] * r2[2] - r1[2] * r2[1])
        - r0[1] * (r1[0] * r2[2] - r1[2] * r2[0])
        + r0[2] * (r1[0] * r2[1] - r1[1] * r2[0])
    )


def _vector(tail: Point, head: Point) -> Point:
    return head[0] - tail[0], head[1] - tail[1]


def _cross(u: Point, v: Point) -> Fraction:
    return u[0] * v[1] - u[1] * v[0]


def _dot(u: Point, v: Point) -> Fraction:
    return u[0] * v[0] + u[1] * v[1]


def _sign(value: Fraction) -> int:
    return (value > 0) - (value < 0)


def _expected_flag(assertion: dict[str, Any], kind: str) -> bool:
    expected = assertion.get("expected", True)
    if not isinstance(expected, bool):
        raise ValueError(f"{kind} expected must be a boolean")
    return expected


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
    elif kind == "concyclic":
        if len(names) != 4:
            raise ValueError("concyclic requires points [a,b,c,d]")
        expected = _expected_flag(assertion, kind)
        a, b, c, d = (points[name] for name in names)
        determinant = _concyclic_determinant(a, b, c, d)
        pairwise_distinct = len({a, b, c, d}) == 4
        all_collinear = _orientation(a, b, c) == 0 and _orientation(a, b, d) == 0
        genuinely_concyclic = (
            determinant == 0 and pairwise_distinct and not all_collinear
        )
        holds = genuinely_concyclic == expected
        details = {
            "determinant": str(determinant),
            "pairwise_distinct": pairwise_distinct,
            "all_collinear": all_collinear,
        }
    elif kind in {"parallel", "perpendicular"}:
        if len(names) != 4:
            raise ValueError(f"{kind} requires points [a,b,c,d]")
        expected = _expected_flag(assertion, kind)
        first = _vector(points[names[0]], points[names[1]])
        second = _vector(points[names[2]], points[names[3]])
        if first == (0, 0) or second == (0, 0):
            raise ValueError(f"{kind} requires two nondegenerate segments")
        if kind == "parallel":
            value = _cross(first, second)
            details = {"cross_product": str(value)}
        else:
            value = _dot(first, second)
            details = {"dot_product": str(value)}
        holds = (value == 0) == expected
    elif kind == "equal_angle":
        if len(names) != 6:
            raise ValueError(
                "equal_angle requires points [a,b,c,d,e,f] comparing the "
                "angle at b in a-b-c with the angle at e in d-e-f"
            )
        expected = _expected_flag(assertion, kind)
        first_left = _vector(points[names[1]], points[names[0]])
        first_right = _vector(points[names[1]], points[names[2]])
        second_left = _vector(points[names[4]], points[names[3]])
        second_right = _vector(points[names[4]], points[names[5]])
        if (0, 0) in {first_left, first_right, second_left, second_right}:
            raise ValueError("equal_angle requires nondegenerate rays")
        first_cross = abs(_cross(first_left, first_right))
        first_dot = _dot(first_left, first_right)
        second_cross = abs(_cross(second_left, second_right))
        second_dot = _dot(second_left, second_right)
        # Unsigned angles in [0, pi] agree exactly when their cosine signs
        # match and |cross1| * dot2 == |cross2| * dot1 (equal tangents).
        angles_equal = (
            _sign(first_dot) == _sign(second_dot)
            and first_cross * second_dot == second_cross * first_dot
        )
        holds = angles_equal == expected
        details = {
            "first_abs_cross": str(first_cross),
            "first_dot": str(first_dot),
            "second_abs_cross": str(second_cross),
            "second_dot": str(second_dot),
        }
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
