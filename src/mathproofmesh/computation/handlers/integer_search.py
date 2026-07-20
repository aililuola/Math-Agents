from __future__ import annotations

import ast
import re
from typing import Any

from ...schemas import EvidenceStrength, ExperimentOutcome, ExperimentSpec
from .base import HandlerEvidence


_ARITHMETIC_NODES = (
    ast.Expression,
    ast.BinOp,
    ast.UnaryOp,
    ast.Add,
    ast.Sub,
    ast.Mult,
    ast.FloorDiv,
    ast.Mod,
    ast.Pow,
    ast.UAdd,
    ast.USub,
    ast.Name,
    ast.Load,
    ast.Constant,
)


def _exact_int(value: Any, label: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{label} must be an integer")
    if isinstance(value, int):
        return value
    if isinstance(value, str) and re.fullmatch(r"[+-]?\d+", value.strip()):
        return int(value)
    raise ValueError(f"{label} must be an integer")


def _parse_arithmetic(expression: str) -> ast.Expression:
    tree = ast.parse(expression.replace("^", "**"), mode="eval")
    for node in ast.walk(tree):
        if not isinstance(node, _ARITHMETIC_NODES):
            raise ValueError(
                f"unsupported integer expression syntax: {type(node).__name__}"
            )
        if isinstance(node, ast.Name) and (node.id.startswith("_") or "__" in node.id):
            raise ValueError("private/dunder names are forbidden")
        if isinstance(node, ast.Constant) and (
            not isinstance(node.value, int) or isinstance(node.value, bool)
        ):
            raise ValueError("bounded integer search accepts integer constants only")
        if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.FloorDiv, ast.Mod)):
            if (
                not isinstance(node.right, ast.Constant)
                or not isinstance(node.right.value, int)
                or isinstance(node.right.value, bool)
                or node.right.value <= 0
            ):
                raise ValueError(
                    "integer division and modulo require a positive constant divisor"
                )
    return tree


def _to_z3(node: ast.AST, variables: dict[str, Any], z3: Any) -> Any:
    if isinstance(node, ast.Expression):
        return _to_z3(node.body, variables, z3)
    if isinstance(node, ast.Constant):
        return z3.IntVal(node.value)
    if isinstance(node, ast.Name):
        if node.id not in variables:
            raise ValueError(f"undeclared integer variable: {node.id}")
        return variables[node.id]
    if isinstance(node, ast.UnaryOp):
        value = _to_z3(node.operand, variables, z3)
        return value if isinstance(node.op, ast.UAdd) else -value
    if isinstance(node, ast.BinOp):
        left = _to_z3(node.left, variables, z3)
        right = _to_z3(node.right, variables, z3)
        if isinstance(node.op, ast.Add):
            return left + right
        if isinstance(node.op, ast.Sub):
            return left - right
        if isinstance(node.op, ast.Mult):
            return left * right
        if isinstance(node.op, ast.FloorDiv):
            return left / right
        if isinstance(node.op, ast.Mod):
            return left % right
        if isinstance(node.op, ast.Pow):
            if (
                not isinstance(node.right, ast.Constant)
                or not 0 <= node.right.value <= 12
            ):
                raise ValueError(
                    "integer powers require a constant exponent from 0 to 12"
                )
            result = z3.IntVal(1)
            for _ in range(node.right.value):
                result *= left
            return result
    raise ValueError(f"unsupported integer expression node: {type(node).__name__}")


def _eval_integer(node: ast.AST, values: dict[str, int]) -> int:
    if isinstance(node, ast.Expression):
        return _eval_integer(node.body, values)
    if isinstance(node, ast.Constant):
        return int(node.value)
    if isinstance(node, ast.Name):
        return values[node.id]
    if isinstance(node, ast.UnaryOp):
        value = _eval_integer(node.operand, values)
        return value if isinstance(node.op, ast.UAdd) else -value
    if isinstance(node, ast.BinOp):
        left = _eval_integer(node.left, values)
        right = _eval_integer(node.right, values)
        if isinstance(node.op, ast.Add):
            return left + right
        if isinstance(node.op, ast.Sub):
            return left - right
        if isinstance(node.op, ast.Mult):
            return left * right
        if isinstance(node.op, ast.FloorDiv):
            return left // right
        if isinstance(node.op, ast.Mod):
            return left % right
        if isinstance(node.op, ast.Pow):
            return left ** int(node.right.value)  # type: ignore[union-attr]
    raise ValueError(f"unsupported integer expression node: {type(node).__name__}")


def _relation(left: Any, right: Any, op: str) -> Any:
    if op == "eq":
        return left == right
    if op == "ne":
        return left != right
    if op == "le":
        return left <= right
    if op == "lt":
        return left < right
    if op == "ge":
        return left >= right
    if op == "gt":
        return left > right
    raise ValueError(f"unsupported relation: {op}")


def run_bounded_integer_search(spec: ExperimentSpec) -> HandlerEvidence:
    try:
        import z3  # type: ignore[import-not-found]
    except ImportError as exc:
        raise RuntimeError(
            "bounded_integer_search requires the optional z3-solver dependency"
        ) from exc

    if not spec.domains:
        raise ValueError("bounded_integer_search requires finite variable domains")
    bounds: dict[str, tuple[int, int]] = {}
    total_cases = 1
    for name, raw in spec.domains.items():
        if not isinstance(raw, dict) or "min" not in raw or "max" not in raw:
            raise ValueError(f"domain for {name!r} requires integer min and max")
        lower = _exact_int(raw["min"], f"domain minimum for {name!r}")
        upper = _exact_int(raw["max"], f"domain maximum for {name!r}")
        if upper < lower:
            raise ValueError(f"domain for {name!r} has max < min")
        bounds[name] = (lower, upper)
        total_cases *= upper - lower + 1
    if total_cases > spec.max_cases:
        raise ValueError(
            f"bounded domain has {total_cases} cases, exceeding max_cases={spec.max_cases}"
        )

    target = spec.arguments.get("target")
    if not isinstance(target, dict):
        raise ValueError("arguments.target must be a typed relation mapping")
    target_lhs = _parse_arithmetic(str(target["lhs"]))
    target_rhs = _parse_arithmetic(str(target.get("rhs", "0")))
    target_op = str(target.get("relation", "eq"))
    variables = {name: z3.Int(name) for name in bounds}
    solver = z3.Solver()
    solver.set("random_seed", spec.seed)
    for name, (lower, upper) in bounds.items():
        solver.add(variables[name] >= lower, variables[name] <= upper)

    parsed_constraints: list[tuple[ast.Expression, ast.Expression, str]] = []
    for constraint in spec.arguments.get("constraints", []):
        if not isinstance(constraint, dict):
            raise ValueError("each constraint must be a typed relation mapping")
        left_ast = _parse_arithmetic(str(constraint["lhs"]))
        right_ast = _parse_arithmetic(str(constraint.get("rhs", "0")))
        relation = str(constraint.get("relation", "eq"))
        parsed_constraints.append((left_ast, right_ast, relation))
        solver.add(
            _relation(
                _to_z3(left_ast, variables, z3),
                _to_z3(right_ast, variables, z3),
                relation,
            )
        )

    z3_target = _relation(
        _to_z3(target_lhs, variables, z3),
        _to_z3(target_rhs, variables, z3),
        target_op,
    )
    solver.add(z3.Not(z3_target))
    status = solver.check()
    if status == z3.sat:
        # Pin the lexicographically least violating tuple. This makes the
        # certificate stable across replay instead of relying on an arbitrary model.
        for name in sorted(variables):
            lower, upper = bounds[name]
            while lower < upper:
                midpoint = (lower + upper) // 2
                solver.push()
                solver.add(variables[name] <= midpoint)
                bounded_status = solver.check()
                solver.pop()
                if bounded_status == z3.sat:
                    upper = midpoint
                elif bounded_status == z3.unsat:
                    lower = midpoint + 1
                else:
                    raise RuntimeError(
                        "Z3 returned unknown while canonicalizing a counterexample"
                    )
            solver.add(variables[name] == lower)
        if solver.check() != z3.sat:
            raise RuntimeError("Z3 lost a model while canonicalizing its certificate")
        model = solver.model()
        assignment = {
            name: model.eval(variable, model_completion=True).as_long()
            for name, variable in variables.items()
        }
        left = _eval_integer(target_lhs, assignment)
        right = _eval_integer(target_rhs, assignment)
        for constraint_lhs, constraint_rhs, constraint_op in parsed_constraints:
            constraint_left = _eval_integer(constraint_lhs, assignment)
            constraint_right = _eval_integer(constraint_rhs, assignment)
            if not _relation(constraint_left, constraint_right, constraint_op):
                raise RuntimeError(
                    "Z3 candidate failed independent constraint re-evaluation"
                )
        if _relation(left, right, target_op):
            raise RuntimeError("Z3 candidate failed independent integer re-evaluation")
        return HandlerEvidence(
            outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
            evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
            counterexample={
                "assignment": assignment,
                "lhs_value": left,
                "rhs_value": right,
                "relation": target_op,
            },
            scope={
                "domains": spec.domains,
                "constraints": spec.arguments.get("constraints", []),
            },
            exact_arithmetic=True,
            cases_checked=1,
            independently_verified=True,
            verification_notes=[
                "The Z3 model was re-evaluated by a separate exact integer evaluator."
            ],
        )
    if status == z3.unsat:
        return HandlerEvidence(
            outcome=ExperimentOutcome.NOT_REFUTED,
            evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
            certificate={"solver_status": "unsat", "domains": spec.domains},
            scope={"domains": spec.domains, "total_domain_cases": total_cases},
            exact_arithmetic=True,
            cases_checked=total_cases,
            verification_notes=[
                "Z3 found no violating assignment in the declared finite domain; this does not prove claims outside that domain."
            ],
        )
    return HandlerEvidence(
        outcome=ExperimentOutcome.INCONCLUSIVE,
        evidence_strength=EvidenceStrength.HEURISTIC,
        scope={"domains": spec.domains},
        verification_notes=["Z3 returned unknown."],
    )
