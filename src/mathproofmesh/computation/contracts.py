from __future__ import annotations

from fractions import Fraction
from typing import Any, Iterable, Literal

from ..schemas import ComputationMethod, ComputationPurpose, ExperimentSpec


_BOUNDED_GREEDY_RULES = {
    "avoid_forbidden_differences",
    "avoid_three_term_arithmetic_progression",
    "coprime_to_all",
    "gcd_overlap_all_prior",
}

_BOUNDED_GREEDY_ARGUMENTS = {
    "initial_values",
    "length",
    "candidate_min",
    "candidate_max",
    "strictly_increasing",
    "rule",
    "forbidden_differences",
    "claimed_values",
}

_TOOL_ARGUMENTS: dict[ComputationMethod, set[str]] = {
    ComputationMethod.SYMPY_SIMPLIFY: {"expression"},
    ComputationMethod.SYMPY_EQUIVALENT: {"lhs", "rhs"},
    ComputationMethod.POLYNOMIAL_FACTOR: {"expression"},
    ComputationMethod.NUMERIC_COUNTEREXAMPLE: {
        "lhs",
        "rhs",
        "relation",
        "variables",
        "ranges",
        "samples",
        "tolerance",
    },
    ComputationMethod.MODULAR_EXHAUSTIVE: {
        "lhs",
        "rhs",
        "modulus",
        "relation",
        "variables",
        "finite_reduction",
        "reduction_justification",
    },
    ComputationMethod.BOUNDED_INTEGER_SEARCH: {"target", "constraints"},
    ComputationMethod.GRAPH_CERTIFICATE: {"graph", "property", "certificate"},
    ComputationMethod.RECURRENCE_CHECK: {
        "initial_values",
        "coefficients",
        "start_n",
        "end_n",
        "inhomogeneous",
        "claimed_expression",
    },
    ComputationMethod.BOUNDED_GREEDY_SEQUENCE: _BOUNDED_GREEDY_ARGUMENTS,
    ComputationMethod.CANDIDATE_PERIOD_CHECK: {
        "values",
        "candidate_period",
        "start_index",
    },
    ComputationMethod.EXACT_GEOMETRY: {"points", "assertion"},
    ComputationMethod.SANDBOXED_PYTHON: {"input"},
    ComputationMethod.LEAN_CHECK: {"source"},
}

_NO_DOMAIN_METHODS = {
    ComputationMethod.SYMPY_SIMPLIFY,
    ComputationMethod.SYMPY_EQUIVALENT,
    ComputationMethod.POLYNOMIAL_FACTOR,
    ComputationMethod.NUMERIC_COUNTEREXAMPLE,
    ComputationMethod.GRAPH_CERTIFICATE,
    ComputationMethod.RECURRENCE_CHECK,
    ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
    ComputationMethod.CANDIDATE_PERIOD_CHECK,
    ComputationMethod.EXACT_GEOMETRY,
    ComputationMethod.SANDBOXED_PYTHON,
    ComputationMethod.LEAN_CHECK,
}

_RELATIONS = {"eq", "ne", "le", "lt", "ge", "gt"}


def computation_contract_mode(
    spec: ExperimentSpec,
) -> Literal["discovery", "assertion"]:
    """Separate data generation from checking a declared mathematical assertion."""

    if spec.purpose == ComputationPurpose.DISCOVER_PATTERN:
        return "discovery"
    return "assertion"


def normalize_exploratory_contract(spec: ExperimentSpec) -> bool:
    """Fail safely toward discovery when a generative request asserts no value."""

    if (
        spec.method != ComputationMethod.BOUNDED_GREEDY_SEQUENCE
        or "claimed_values" in spec.arguments
        or spec.purpose == ComputationPurpose.DISCOVER_PATTERN
    ):
        return False
    object.__setattr__(spec, "purpose", ComputationPurpose.DISCOVER_PATTERN)
    object.__setattr__(spec, "broad_search", True)
    object.__setattr__(spec, "runtime_fingerprint", {})
    object.__setattr__(spec, "execution_hash", "")
    object.__setattr__(spec, "request_hash", "")
    spec.validate_and_hash()
    return True


def experiment_tool_catalog(
    allowed_tools: Iterable[str] | None = None,
) -> list[dict[str, Any]]:
    """Return compact model-facing contracts for executable computation methods."""

    allowed = set(allowed_tools or [])
    catalog: list[dict[str, Any]] = [
        {
            "method": "sympy_simplify",
            "required_arguments": ["expression"],
            "optional_arguments": [],
            "domains": "unused",
        },
        {
            "method": "sympy_equivalent",
            "required_arguments": ["lhs", "rhs"],
            "optional_arguments": [],
            "domains": "unused",
        },
        {
            "method": "polynomial_factor",
            "required_arguments": ["expression"],
            "optional_arguments": [],
            "domains": "unused",
        },
        {
            "method": "numeric_counterexample",
            "required_arguments": ["lhs", "rhs"],
            "optional_arguments": [
                "relation",
                "variables",
                "ranges",
                "samples",
                "tolerance",
            ],
            "constraints": ["ExperimentSpec.exact_arithmetic must be false."],
        },
        {
            "method": "modular_exhaustive",
            "required_arguments": ["lhs", "modulus"],
            "optional_arguments": [
                "rhs",
                "relation",
                "variables",
                "finite_reduction",
                "reduction_justification",
            ],
            "domains": "Map each declared variable to residue values or min/max.",
        },
        {
            "method": "bounded_integer_search",
            "required_arguments": ["target"],
            "optional_arguments": ["constraints"],
            "argument_shapes": {
                "target": {
                    "lhs": "expression",
                    "rhs": "expression (optional, default 0)",
                    "relation": sorted(_RELATIONS),
                },
                "constraints": "list of target-shaped mappings",
            },
            "domains": "Required mapping of every variable to integer min/max bounds.",
        },
        {
            "method": "graph_certificate",
            "required_arguments": ["graph", "property", "certificate"],
            "constraints": [
                "property is proper_coloring, path, cycle, matching, or connected."
            ],
        },
        {
            "method": "recurrence_check",
            "required_arguments": ["initial_values", "coefficients", "end_n"],
            "optional_arguments": [
                "start_n",
                "inhomogeneous",
                "claimed_expression",
            ],
            "domains": "unused",
        },
        {
            "method": "bounded_greedy_sequence",
            "scope": "Generate one deterministic finite sequence; domain sweeps are unsupported.",
            "contract_modes": {
                "discovery": (
                    "Use purpose=discover_pattern and omit claimed_values; the output "
                    "may inspire only a scoped candidate conjecture."
                ),
                "assertion": (
                    "For any non-discovery purpose, claimed_values is required and "
                    "is checked exactly."
                ),
            },
            "required_arguments": ["initial_values", "length", "rule"],
            "optional_arguments": [
                "candidate_min",
                "candidate_max",
                "strictly_increasing",
                "forbidden_differences",
                "claimed_values",
            ],
            "rules": sorted(_BOUNDED_GREEDY_RULES),
            "constraints": [
                "initial_values must be a non-empty list.",
                "Use gcd_overlap_all_prior for the least larger integer having gcd > 1 with every prior value.",
                "forbidden_differences is required only by avoid_forbidden_differences.",
                "max_cases counts candidate trials, not requested sequence terms.",
                "Unknown aliases such as a1, max_n, max_terms, report_dn, and check_early_dn_one are rejected.",
            ],
            "domains": "Must be empty.",
        },
        {
            "method": "candidate_period_check",
            "required_arguments": ["values", "candidate_period"],
            "optional_arguments": ["start_index"],
            "domains": "unused",
        },
        {
            "method": "exact_geometry",
            "required_arguments": ["points", "assertion"],
            "constraints": [
                "assertion.kind is collinear, orientation, equal_distance, or point_on_segment."
            ],
        },
        {
            "method": "sandboxed_python",
            "scope": "One bounded custom computation in an isolated Docker sandbox.",
            "required_arguments": ["input"],
            "optional_arguments": [],
            "constraints": [
                "Last resort only; typed_tool_gap must explain why no typed contract applies.",
                "arguments.input must be the complete JSON object supplied to run(data).",
                "Do not place sandbox inputs beside input; such fields are ignored and rejected.",
            ],
            "domains": "Must be empty; put all bounded input under arguments.input.",
        },
        {
            "method": "lean_check",
            "required_arguments": ["source"],
            "optional_arguments": [],
            "domains": "unused",
        },
    ]
    if not allowed:
        return catalog
    return [item for item in catalog if item["method"] in allowed]


def validate_experiment_contract(spec: ExperimentSpec) -> list[str]:
    """Reject malformed requests before policy admission or tool execution."""

    args = spec.arguments
    issues: list[str] = []
    allowed_arguments = _TOOL_ARGUMENTS.get(spec.method)
    if allowed_arguments is None:
        return [f"no registered contract exists for method {spec.method.value}"]
    unknown = sorted(set(args) - allowed_arguments)
    if unknown:
        issues.append(f"unsupported arguments: {', '.join(unknown)}")
    if spec.method in _NO_DOMAIN_METHODS and spec.domains:
        issues.append(f"{spec.method.value} does not accept domains")

    if spec.method == ComputationMethod.SYMPY_SIMPLIFY:
        _require_nonempty_string(args, "expression", issues)
    elif spec.method == ComputationMethod.SYMPY_EQUIVALENT:
        _require_nonempty_string(args, "lhs", issues)
        _require_nonempty_string(args, "rhs", issues)
    elif spec.method == ComputationMethod.POLYNOMIAL_FACTOR:
        _require_nonempty_string(args, "expression", issues)
    elif spec.method == ComputationMethod.NUMERIC_COUNTEREXAMPLE:
        _validate_numeric_counterexample(args, issues)
    elif spec.method == ComputationMethod.MODULAR_EXHAUSTIVE:
        _validate_modular(spec, issues)
    elif spec.method == ComputationMethod.BOUNDED_INTEGER_SEARCH:
        _validate_integer_search(spec, issues)
    elif spec.method == ComputationMethod.GRAPH_CERTIFICATE:
        _validate_graph(args, issues)
    elif spec.method == ComputationMethod.RECURRENCE_CHECK:
        _validate_recurrence(args, issues)
    elif spec.method == ComputationMethod.BOUNDED_GREEDY_SEQUENCE:
        _validate_bounded_greedy(spec, issues)
    elif spec.method == ComputationMethod.CANDIDATE_PERIOD_CHECK:
        _validate_candidate_period(args, issues)
    elif spec.method == ComputationMethod.EXACT_GEOMETRY:
        _validate_geometry(args, issues)
    elif spec.method == ComputationMethod.SANDBOXED_PYTHON:
        if not isinstance(args.get("input"), dict):
            issues.append("input must be a JSON object")
    elif spec.method == ComputationMethod.LEAN_CHECK:
        _require_nonempty_string(args, "source", issues)
    return issues


def _validate_bounded_greedy(
    spec: ExperimentSpec,
    issues: list[str],
) -> None:
    args = spec.arguments
    initial_values = args.get("initial_values")
    if not isinstance(initial_values, list) or not initial_values:
        issues.append("initial_values must be a non-empty list")
    elif not all(_is_exact_integer(value) for value in initial_values):
        issues.append("initial_values must contain only exact integers")
    if "length" not in args:
        issues.append("length is required")
    elif not _is_exact_integer(args["length"]):
        issues.append("length must be an integer")
    elif isinstance(initial_values, list) and int(Fraction(str(args["length"]))) < len(
        initial_values
    ):
        issues.append("length must be at least the number of initial_values")

    rule = args.get("rule")
    if rule not in _BOUNDED_GREEDY_RULES:
        issues.append(
            "rule must be one of: " + ", ".join(sorted(_BOUNDED_GREEDY_RULES))
        )
    if rule == "avoid_forbidden_differences":
        forbidden = args.get("forbidden_differences")
        if not isinstance(forbidden, list) or not forbidden:
            issues.append(
                "forbidden_differences must be a non-empty list for this rule"
            )

    claimed_values = args.get("claimed_values")
    if claimed_values is not None and not isinstance(claimed_values, list):
        issues.append("claimed_values must be a list when supplied")
    mode = computation_contract_mode(spec)
    if mode == "assertion" and claimed_values is None:
        issues.append(
            "assertion-mode bounded_greedy_sequence requires claimed_values; "
            "use purpose=discover_pattern for exploratory prefix generation"
        )
    for name in ("candidate_min", "candidate_max"):
        if name in args and not _is_exact_integer(args[name]):
            issues.append(f"{name} must be an integer")
    if (
        _is_exact_integer(args.get("candidate_min", 0))
        and _is_exact_integer(args.get("candidate_max", 1_000_000))
        and int(Fraction(str(args.get("candidate_max", 1_000_000))))
        < int(Fraction(str(args.get("candidate_min", 0))))
    ):
        issues.append("candidate_max must not be below candidate_min")
    if "strictly_increasing" in args and not isinstance(
        args["strictly_increasing"], bool
    ):
        issues.append("strictly_increasing must be a boolean")


def _validate_numeric_counterexample(
    args: dict[str, Any],
    issues: list[str],
) -> None:
    _require_nonempty_string(args, "lhs", issues)
    _require_nonempty_string(args, "rhs", issues)
    relation = args.get("relation", "eq")
    if relation not in _RELATIONS:
        issues.append("relation must be one of: " + ", ".join(sorted(_RELATIONS)))
    variables = args.get("variables")
    if variables is not None:
        if not isinstance(variables, list) or not all(
            isinstance(value, str) and value.strip() for value in variables
        ):
            issues.append("variables must be a list of non-empty names")
        elif len(variables) != len(set(variables)):
            issues.append("variables must be unique")
    ranges = args.get("ranges")
    if ranges is not None:
        if not isinstance(ranges, dict):
            issues.append("ranges must map variable names to [lower, upper]")
        else:
            for name, bounds in ranges.items():
                if not isinstance(bounds, (list, tuple)) or len(bounds) != 2:
                    issues.append(f"range for {name!r} must be [lower, upper]")
    if "samples" in args and (
        not _is_exact_integer(args["samples"])
        or int(Fraction(str(args["samples"]))) < 1
    ):
        issues.append("samples must be a positive integer")
    if "tolerance" in args:
        try:
            if isinstance(args["tolerance"], bool) or float(args["tolerance"]) < 0:
                raise ValueError
        except (TypeError, ValueError):
            issues.append("tolerance must be a nonnegative number")


def _validate_modular(spec: ExperimentSpec, issues: list[str]) -> None:
    args = spec.arguments
    _require_nonempty_string(args, "lhs", issues)
    if not _is_exact_integer(args.get("modulus")):
        issues.append("modulus must be an integer")
    elif int(Fraction(str(args["modulus"]))) < 2:
        issues.append("modulus must be at least 2")
    if args.get("relation", "eq") not in {"eq", "ne"}:
        issues.append("relation must be eq or ne")
    variables = args.get("variables")
    if variables is not None:
        if not isinstance(variables, list) or not all(
            isinstance(value, str) and value.strip() for value in variables
        ):
            issues.append("variables must be a list of non-empty names")
        elif len(variables) != len(set(variables)):
            issues.append("variables must be unique")
    for name, domain in spec.domains.items():
        if isinstance(domain, list):
            if not domain:
                issues.append(f"domain for {name!r} cannot be empty")
            continue
        if not isinstance(domain, dict):
            issues.append(f"domain for {name!r} must be a mapping or list")
            continue
        if "values" in domain:
            if not isinstance(domain["values"], list) or not domain["values"]:
                issues.append(f"domain values for {name!r} must be a non-empty list")
        elif not all(
            key not in domain or _is_exact_integer(domain[key])
            for key in ("min", "max")
        ):
            issues.append(f"domain min/max for {name!r} must be integers")


def _validate_integer_search(spec: ExperimentSpec, issues: list[str]) -> None:
    if not spec.domains:
        issues.append("bounded_integer_search requires finite variable domains")
    for name, domain in spec.domains.items():
        if not isinstance(domain, dict) or "min" not in domain or "max" not in domain:
            issues.append(f"domain for {name!r} requires integer min and max")
            continue
        if not _is_exact_integer(domain["min"]) or not _is_exact_integer(domain["max"]):
            issues.append(f"domain min/max for {name!r} must be integers")
            continue
        if int(Fraction(str(domain["max"]))) < int(Fraction(str(domain["min"]))):
            issues.append(f"domain for {name!r} has max < min")
    _validate_relation_mapping(spec.arguments.get("target"), "target", issues)
    constraints = spec.arguments.get("constraints", [])
    if not isinstance(constraints, list):
        issues.append("constraints must be a list of typed relation mappings")
    else:
        for index, constraint in enumerate(constraints):
            _validate_relation_mapping(constraint, f"constraints[{index}]", issues)


def _validate_relation_mapping(
    value: Any,
    label: str,
    issues: list[str],
) -> None:
    if not isinstance(value, dict):
        issues.append(f"{label} must be a typed relation mapping")
        return
    _require_nonempty_string(value, "lhs", issues, prefix=f"{label}.")
    if value.get("relation", "eq") not in _RELATIONS:
        issues.append(
            f"{label}.relation must be one of: " + ", ".join(sorted(_RELATIONS))
        )


def _validate_graph(args: dict[str, Any], issues: list[str]) -> None:
    graph = args.get("graph")
    certificate = args.get("certificate")
    if not isinstance(graph, dict):
        issues.append("graph must be a typed mapping")
    else:
        nodes = graph.get("nodes")
        edges = graph.get("edges")
        if not isinstance(nodes, list):
            issues.append("graph.nodes must be a list")
        elif len([str(node) for node in nodes]) != len(
            set(str(node) for node in nodes)
        ):
            issues.append("graph node identifiers must be unique")
        if not isinstance(edges, list) or not all(
            isinstance(edge, (list, tuple)) and len(edge) == 2 for edge in edges
        ):
            issues.append("graph.edges must contain two-endpoint pairs")
    if args.get("property") not in {
        "proper_coloring",
        "path",
        "cycle",
        "matching",
        "connected",
    }:
        issues.append(
            "property must be proper_coloring, path, cycle, matching, or connected"
        )
    if not isinstance(certificate, dict):
        issues.append("certificate must be a typed mapping")


def _validate_recurrence(args: dict[str, Any], issues: list[str]) -> None:
    initial = args.get("initial_values")
    coefficients = args.get("coefficients")
    if not isinstance(initial, list) or not initial:
        issues.append("initial_values must be a non-empty list")
    if not isinstance(coefficients, list) or not coefficients:
        issues.append("coefficients must be a non-empty list")
    if (
        isinstance(initial, list)
        and isinstance(coefficients, list)
        and coefficients
        and len(initial) < len(coefficients)
    ):
        issues.append("initial_values must contain at least recurrence order values")
    if not _is_exact_integer(args.get("end_n")):
        issues.append("end_n must be an integer")
    if "start_n" in args and not _is_exact_integer(args["start_n"]):
        issues.append("start_n must be an integer")
    if _is_exact_integer(args.get("end_n")) and _is_exact_integer(
        args.get("start_n", 0)
    ):
        if int(Fraction(str(args["end_n"]))) < int(
            Fraction(str(args.get("start_n", 0)))
        ):
            issues.append("end_n must not be below start_n")


def _validate_candidate_period(args: dict[str, Any], issues: list[str]) -> None:
    values = args.get("values")
    if not isinstance(values, list) or not values:
        issues.append("values must be a non-empty list")
    if not _is_exact_integer(args.get("candidate_period")):
        issues.append("candidate_period must be an integer")
    elif int(Fraction(str(args["candidate_period"]))) <= 0:
        issues.append("candidate_period must be positive")
    if "start_index" in args and (
        not _is_exact_integer(args["start_index"])
        or int(Fraction(str(args["start_index"]))) < 0
    ):
        issues.append("start_index must be a nonnegative integer")
    if (
        isinstance(values, list)
        and values
        and _is_exact_integer(args.get("candidate_period"))
        and _is_exact_integer(args.get("start_index", 0))
        and int(Fraction(str(args.get("start_index", 0))))
        + int(Fraction(str(args["candidate_period"])))
        >= len(values)
    ):
        issues.append("candidate_period/start_index leave no comparable pair")


def _validate_geometry(args: dict[str, Any], issues: list[str]) -> None:
    points = args.get("points")
    assertion = args.get("assertion")
    if not isinstance(points, dict) or not points:
        issues.append("points must map names to exact coordinate pairs")
    elif not all(
        isinstance(point, (list, tuple)) and len(point) == 2
        for point in points.values()
    ):
        issues.append("each point must contain exactly two coordinates")
    if not isinstance(assertion, dict):
        issues.append("assertion must be a typed mapping")
        return
    kind = assertion.get("kind")
    expected_counts = {
        "collinear": 3,
        "orientation": 3,
        "equal_distance": 4,
        "point_on_segment": 3,
    }
    if kind not in expected_counts:
        issues.append(
            "assertion.kind must be collinear, orientation, equal_distance, or point_on_segment"
        )
        return
    names = assertion.get("points")
    if not isinstance(names, list) or len(names) != expected_counts[kind]:
        issues.append(f"{kind} requires exactly {expected_counts[kind]} point names")
    elif isinstance(points, dict) and any(str(name) not in points for name in names):
        issues.append("geometry assertion references an undeclared point")


def _require_nonempty_string(
    mapping: dict[str, Any],
    name: str,
    issues: list[str],
    *,
    prefix: str = "",
) -> None:
    value = mapping.get(name)
    if not isinstance(value, str) or not value.strip():
        issues.append(f"{prefix}{name} must be a non-empty string")


def _is_exact_integer(value: Any) -> bool:
    if value is None or isinstance(value, bool):
        return False
    try:
        return Fraction(str(value)).denominator == 1
    except (TypeError, ValueError, ZeroDivisionError):
        return False
