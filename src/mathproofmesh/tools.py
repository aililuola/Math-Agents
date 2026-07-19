from __future__ import annotations

import ast
import math
import random
import shlex
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

import sympy as sp

from .config import SystemConfig
from .schemas import ToolRequest, ToolResult
from .store import ArtifactStore


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
            if not isinstance(node.func, ast.Name) or node.func.id not in _ALLOWED_FUNCTIONS:
                raise UnsafeExpressionError("only whitelisted mathematical functions are allowed")
            if node.keywords:
                raise UnsafeExpressionError("keyword arguments are forbidden")
        if isinstance(node, ast.Constant) and not isinstance(node.value, (int, float, complex)):
            raise UnsafeExpressionError("only numeric constants are allowed")
    return names


def parse_expression(expression: str) -> sp.Expr:
    names = _validate_expression_ast(expression)
    locals_dict: dict[str, Any] = {**_ALLOWED_FUNCTIONS, **_ALLOWED_CONSTANTS}
    for name in names:
        if name not in locals_dict:
            locals_dict[name] = sp.Symbol(name, real=True)
    return sp.sympify(expression.replace("^", "**"), locals=locals_dict, evaluate=True)


class ToolBroker:
    """Executes narrowly-scoped deterministic checks. Arbitrary Python execution is intentionally absent."""

    def __init__(self, config: SystemConfig, store: ArtifactStore) -> None:
        self.config = config
        self.store = store
        self.random = random.Random(config.runtime.random_seed)

    def execute_many(self, requests: list[ToolRequest]) -> list[ToolResult]:
        return [self.execute(request) for request in requests]

    def execute(self, request: ToolRequest) -> ToolResult:
        try:
            if request.kind == "sympy_simplify":
                result = self._sympy_simplify(request.arguments)
            elif request.kind == "sympy_equivalent":
                result = self._sympy_equivalent(request.arguments)
            elif request.kind == "numeric_counterexample":
                result = self._numeric_counterexample(request.arguments)
            elif request.kind == "polynomial_factor":
                result = self._polynomial_factor(request.arguments)
            elif request.kind == "lean_check":
                result = self._lean_check(request.arguments)
            else:
                raise ValueError(f"unsupported tool kind: {request.kind}")
            evidence = self.store.write_content_addressed(
                "tools",
                {
                    "request": request.model_dump(mode="json"),
                    "ok": True,
                    "result": result,
                },
                summary=f"Tool result for {request.kind}",
            )
            tool_result = ToolResult(
                request_id=request.request_id,
                kind=request.kind,
                ok=True,
                result=result,
                evidence_ref=evidence,
            )
        except Exception as exc:
            evidence = self.store.write_content_addressed(
                "tools",
                {
                    "request": request.model_dump(mode="json"),
                    "ok": False,
                    "error": str(exc),
                },
                summary=f"Failed tool request for {request.kind}",
            )
            tool_result = ToolResult(
                request_id=request.request_id,
                kind=request.kind,
                ok=False,
                error=str(exc),
                evidence_ref=evidence,
            )
        self.store.append_event("tool_result", tool_result)
        return tool_result

    def _sympy_simplify(self, args: dict[str, Any]) -> dict[str, Any]:
        if not self.config.verification.enable_sympy_tools:
            raise RuntimeError("SymPy tools are disabled")
        expression = str(args["expression"])
        parsed = parse_expression(expression)
        simplified = sp.simplify(parsed)
        return {
            "input": expression,
            "parsed": str(parsed),
            "simplified": str(simplified),
            "is_zero": bool(simplified == 0),
        }

    def _sympy_equivalent(self, args: dict[str, Any]) -> dict[str, Any]:
        if not self.config.verification.enable_sympy_tools:
            raise RuntimeError("SymPy tools are disabled")
        lhs = parse_expression(str(args["lhs"]))
        rhs = parse_expression(str(args["rhs"]))
        difference = sp.simplify(lhs - rhs)
        return {
            "lhs": str(lhs),
            "rhs": str(rhs),
            "difference": str(difference),
            "equivalent": bool(difference == 0),
        }

    def _polynomial_factor(self, args: dict[str, Any]) -> dict[str, Any]:
        if not self.config.verification.enable_sympy_tools:
            raise RuntimeError("SymPy tools are disabled")
        expression = parse_expression(str(args["expression"]))
        return {
            "expanded": str(sp.expand(expression)),
            "factored": str(sp.factor(expression)),
        }

    def _numeric_counterexample(self, args: dict[str, Any]) -> dict[str, Any]:
        if not self.config.verification.enable_numeric_counterexamples:
            raise RuntimeError("numeric counterexample search is disabled")
        lhs = parse_expression(str(args["lhs"]))
        rhs = parse_expression(str(args["rhs"]))
        relation = str(args.get("relation", "eq"))
        variables = args.get("variables") or sorted(str(s) for s in lhs.free_symbols | rhs.free_symbols)
        ranges = args.get("ranges") or {}
        samples = min(10000, max(1, int(args.get("samples", 200))))
        tolerance = float(args.get("tolerance", 1e-8))
        symbols = {str(s): s for s in lhs.free_symbols | rhs.free_symbols}
        for name in variables:
            symbols.setdefault(str(name), sp.Symbol(str(name), real=True))

        def relation_holds(lval: complex, rval: complex) -> bool:
            if relation == "eq":
                return abs(lval - rval) <= tolerance * max(1.0, abs(lval), abs(rval))
            if abs(lval.imag) > tolerance or abs(rval.imag) > tolerance:
                return False
            if relation == "le":
                return lval.real <= rval.real + tolerance
            if relation == "lt":
                return lval.real < rval.real - tolerance
            if relation == "ge":
                return lval.real + tolerance >= rval.real
            if relation == "gt":
                return lval.real - tolerance > rval.real
            raise ValueError(f"unsupported relation: {relation}")

        checked = 0
        skipped = 0
        for _ in range(samples):
            substitution: dict[sp.Symbol, sp.Rational] = {}
            readable: dict[str, str] = {}
            for name in variables:
                lo, hi = ranges.get(name, [-5, 5])
                lo_i = int(math.floor(float(lo) * 10))
                hi_i = int(math.ceil(float(hi) * 10))
                numerator = self.random.randint(lo_i, hi_i)
                denominator = self.random.choice([1, 1, 1, 2, 3, 5])
                value = sp.Rational(numerator, 10 * denominator)
                substitution[symbols[name]] = value
                readable[name] = str(value)
            try:
                lval = complex(sp.N(lhs.subs(substitution), 30))
                rval = complex(sp.N(rhs.subs(substitution), 30))
                if not all(math.isfinite(x) for x in [lval.real, lval.imag, rval.real, rval.imag]):
                    skipped += 1
                    continue
            except Exception:
                skipped += 1
                continue
            checked += 1
            if not relation_holds(lval, rval):
                return {
                    "counterexample_found": True,
                    "assignment": readable,
                    "lhs_value": repr(lval),
                    "rhs_value": repr(rval),
                    "checked": checked,
                    "skipped": skipped,
                    "warning": "A sampled counterexample refutes a universal claim; absence of one is not a proof.",
                }
        return {
            "counterexample_found": False,
            "checked": checked,
            "skipped": skipped,
            "warning": "Random testing did not find a counterexample; this is not a proof.",
        }

    def _lean_check(self, args: dict[str, Any]) -> dict[str, Any]:
        if not self.config.verification.enable_lean:
            raise RuntimeError("Lean execution is disabled")
        command = shlex.split(self.config.verification.lean_command)
        executable = shutil.which(command[0])
        if executable is None:
            raise RuntimeError(f"Lean executable not found: {command[0]}")
        source = str(args["source"])
        # Lean files can execute metaprograms. Enable this only inside a trusted, isolated container.
        with tempfile.TemporaryDirectory(prefix="mathproofmesh_lean_") as tmpdir:
            path = Path(tmpdir) / "Main.lean"
            path.write_text(source, encoding="utf-8")
            completed = subprocess.run(
                [*command, str(path)],
                cwd=tmpdir,
                capture_output=True,
                text=True,
                timeout=self.config.verification.external_tool_timeout_seconds,
                check=False,
            )
            return {
                "returncode": completed.returncode,
                "stdout": completed.stdout[-20000:],
                "stderr": completed.stderr[-20000:],
                "accepted": completed.returncode == 0,
            }
