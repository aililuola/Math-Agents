"""Fail-closed AST validator for the optional containerized custom-program path."""

from __future__ import annotations

import ast
import json
import sys


ALLOWED_IMPORTS = {
    "collections",
    "decimal",
    "fractions",
    "functools",
    "itertools",
    "math",
}
FORBIDDEN_CALLS = {
    "__import__",
    "breakpoint",
    "compile",
    "eval",
    "exec",
    "getattr",
    "globals",
    "input",
    "locals",
    "open",
    "setattr",
    "vars",
}
ALLOWED_NODES = {
    ast.Module,
    ast.Import,
    ast.ImportFrom,
    ast.FunctionDef,
    ast.arguments,
    ast.arg,
    ast.alias,
    ast.Return,
    ast.Assign,
    ast.AnnAssign,
    ast.AugAssign,
    ast.Expr,
    ast.If,
    ast.For,
    ast.While,
    ast.Break,
    ast.Continue,
    ast.Pass,
    ast.Name,
    ast.Load,
    ast.Store,
    ast.Constant,
    ast.List,
    ast.Tuple,
    ast.Set,
    ast.Dict,
    ast.Subscript,
    ast.Slice,
    ast.BinOp,
    ast.UnaryOp,
    ast.BoolOp,
    ast.Compare,
    ast.Call,
    ast.keyword,
    ast.ListComp,
    ast.SetComp,
    ast.DictComp,
    ast.GeneratorExp,
    ast.comprehension,
    ast.Add,
    ast.Sub,
    ast.Mult,
    ast.Div,
    ast.FloorDiv,
    ast.Mod,
    ast.Pow,
    ast.UAdd,
    ast.USub,
    ast.Not,
    ast.And,
    ast.Or,
    ast.Eq,
    ast.NotEq,
    ast.Lt,
    ast.LtE,
    ast.Gt,
    ast.GtE,
    ast.In,
    ast.NotIn,
    ast.Is,
    ast.IsNot,
}


def validate(source: str) -> set[str]:
    if not isinstance(source, str) or not source.strip():
        raise ValueError("program source must be non-empty")
    tree = ast.parse(source, mode="exec")
    run_functions = [
        node
        for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == "run"
    ]
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
    if len(run_functions) != 1 or len(functions) != 1:
        raise ValueError("program must define exactly one function named run")
    run = run_functions[0]
    if (
        len(run.args.args) != 1
        or run.args.args[0].arg != "data"
        or run.args.vararg is not None
        or run.args.kwarg is not None
        or run.args.kwonlyargs
    ):
        raise ValueError("run must have the exact signature run(data)")
    imports: set[str] = set()
    for node in ast.walk(tree):
        if type(node) not in ALLOWED_NODES:
            raise ValueError(f"disallowed syntax: {type(node).__name__}")
        if isinstance(node, ast.Attribute):
            raise ValueError("attribute access is forbidden")
        if isinstance(node, ast.Name):
            if node.id.startswith("_") or "__" in node.id:
                raise ValueError("private/dunder names are forbidden")
        if isinstance(node, ast.Call):
            if not isinstance(node.func, ast.Name):
                raise ValueError("only direct calls to public names are allowed")
            if node.func.id in FORBIDDEN_CALLS:
                raise ValueError(f"forbidden call: {node.func.id}")
        if isinstance(node, ast.Import):
            for alias in node.names:
                root = alias.name.split(".", 1)[0]
                if root not in ALLOWED_IMPORTS:
                    raise ValueError(f"forbidden import: {root}")
                imports.add(root)
        if isinstance(node, ast.ImportFrom):
            if node.level != 0 or node.module is None:
                raise ValueError("relative imports are forbidden")
            root = node.module.split(".", 1)[0]
            if root not in ALLOWED_IMPORTS:
                raise ValueError(f"forbidden import: {root}")
            imports.add(root)
    return imports


def main() -> int:
    try:
        imports = sorted(validate(sys.stdin.read()))
        print(json.dumps({"valid": True, "imports": imports}, sort_keys=True))
        return 0
    except (SyntaxError, ValueError) as exc:
        print(
            json.dumps(
                {"valid": False, "error": str(exc)[:1000]},
                sort_keys=True,
            )
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
