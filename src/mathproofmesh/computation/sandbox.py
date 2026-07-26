from __future__ import annotations

import ast
import json
import os
import shlex
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from ..config import ComputationConfig, VerificationConfig
from ..schemas import (
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentProgram,
    ExperimentSpec,
)
from .handlers.base import HandlerEvidence


_IS_WINDOWS = os.name == "nt"
_ALLOWED_IMPORTS = {
    "collections",
    "decimal",
    "fractions",
    "functools",
    "itertools",
    "math",
}
_FORBIDDEN_CALLS = {
    "breakpoint",
    "compile",
    "eval",
    "exec",
    "getattr",
    "globals",
    "input",
    "locals",
    "open",
    "print",
    "setattr",
    "vars",
    "__import__",
}
_FORBIDDEN_MODULES = {
    "asyncio",
    "ctypes",
    "http",
    "importlib",
    "multiprocessing",
    "os",
    "pathlib",
    "requests",
    "shutil",
    "signal",
    "socket",
    "subprocess",
    "sys",
    "urllib",
}


class UnsafeProgramError(ValueError):
    pass


class SandboxExecutionError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        exit_code: int | None,
        stdout: str,
        stderr: str,
    ) -> None:
        super().__init__(message)
        self.exit_code = exit_code
        self.stdout = stdout
        self.stderr = stderr


def _find_docker_executable() -> str | None:
    discovered = shutil.which("docker")
    if discovered:
        return discovered
    if not _IS_WINDOWS:
        return None

    candidates: list[Path] = []
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidates.append(
            Path(local_app_data)
            / "Programs"
            / "DockerDesktop"
            / "resources"
            / "bin"
            / "docker.exe"
        )
    program_files = os.environ.get("ProgramFiles")
    if program_files:
        candidates.append(
            Path(program_files)
            / "Docker"
            / "Docker"
            / "resources"
            / "bin"
            / "docker.exe"
        )
    for candidate in candidates:
        try:
            if candidate.is_file():
                return str(candidate)
        except OSError:
            continue
    return None


def validate_program_source(source: str) -> set[str]:
    tree = ast.parse(source, mode="exec")
    run_definitions = 0
    imported_modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names = {alias.name.split(".", 1)[0] for alias in node.names}
            imported_modules.update(names)
            if any(name not in _ALLOWED_IMPORTS for name in names):
                raise UnsafeProgramError("program imports a non-whitelisted module")
        if isinstance(node, ast.ImportFrom):
            if node.level or not node.module:
                raise UnsafeProgramError("relative imports are forbidden")
            name = node.module.split(".", 1)[0]
            imported_modules.add(name)
            if name in _FORBIDDEN_MODULES or name not in _ALLOWED_IMPORTS:
                raise UnsafeProgramError("program imports a non-whitelisted module")
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name) and node.func.id in _FORBIDDEN_CALLS:
                raise UnsafeProgramError(f"forbidden call: {node.func.id}")
            if (
                isinstance(node.func, ast.Attribute)
                and node.func.attr in _FORBIDDEN_CALLS
            ):
                raise UnsafeProgramError(f"forbidden call: {node.func.attr}")
        if isinstance(node, ast.Attribute) and (
            node.attr.startswith("_") or "__" in node.attr
        ):
            raise UnsafeProgramError("private/dunder attribute access is forbidden")
        if isinstance(node, ast.Name) and (node.id.startswith("_") or "__" in node.id):
            raise UnsafeProgramError("private/dunder names are forbidden")
        if isinstance(node, ast.FunctionDef) and node.name == "run":
            run_definitions += 1
            positional = [*node.args.posonlyargs, *node.args.args]
            if (
                len(positional) != 1
                or node.args.vararg is not None
                or node.args.kwarg is not None
                or node.args.kwonlyargs
            ):
                raise UnsafeProgramError(
                    "run must have exactly one positional argument"
                )
    if run_definitions != 1:
        raise UnsafeProgramError(
            "program must define run(data) and return a JSON object"
        )
    return imported_modules


def build_docker_command(config: ComputationConfig, workdir: Path) -> list[str]:
    if not config.sandbox_image:
        raise RuntimeError("sandbox_image is not configured")
    return [
        "docker",
        "run",
        "--rm",
        "--interactive",
        "--network",
        "none",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges",
        "--pids-limit",
        str(config.sandbox_pids_limit),
        "--memory",
        f"{config.sandbox_memory_mb}m",
        "--cpus",
        str(config.sandbox_cpus),
        "--user",
        "65532:65532",
        "--tmpfs",
        "/tmp:rw,noexec,nosuid,size=64m",
        "--mount",
        f"type=bind,src={workdir},dst=/work,readonly",
        "--workdir",
        "/tmp",
        "--entrypoint",
        "python",
        config.sandbox_image,
        "-I",
        "-B",
        "-c",
        (
            "import json,runpy,sys;"
            "ns=runpy.run_path('/work/program.py',run_name='experiment');"
            "data=json.loads(sys.stdin.read());"
            "result=ns['run'](data);"
            "out=json.dumps(result,sort_keys=True,separators=(',',':'));"
            f"assert len(out)<={config.max_output_chars},'output limit exceeded';"
            "sys.stdout.write(out)"
        ),
    ]


def build_lean_docker_command(
    config: VerificationConfig,
    workdir: Path,
    *,
    docker_executable: str = "docker",
) -> list[str]:
    """Build the networkless, read-only Lean verification command."""

    if not config.lean_sandbox_image:
        raise RuntimeError("lean_sandbox_image is not configured")
    lean_command = shlex.split(config.lean_command)
    if not lean_command:
        raise RuntimeError("lean_command is empty")
    return [
        docker_executable,
        "run",
        "--rm",
        "--network",
        "none",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges",
        "--pids-limit",
        str(config.lean_sandbox_pids_limit),
        "--memory",
        f"{config.lean_sandbox_memory_mb}m",
        "--cpus",
        str(config.lean_sandbox_cpus),
        "--user",
        "65532:65532",
        "--tmpfs",
        "/tmp:rw,noexec,nosuid,size=128m",
        "--mount",
        f"type=bind,src={workdir},dst=/work,readonly",
        "--workdir",
        "/work",
        config.lean_sandbox_image,
        *lean_command,
        "/work/Main.lean",
    ]


def _validate_json_value(value: Any, schema: dict[str, Any], path: str) -> None:
    declared_type = schema.get("type")
    type_valid = {
        "array": lambda item: isinstance(item, list),
        "boolean": lambda item: isinstance(item, bool),
        "integer": lambda item: isinstance(item, int) and not isinstance(item, bool),
        "null": lambda item: item is None,
        "number": lambda item: (
            isinstance(item, (int, float)) and not isinstance(item, bool)
        ),
        "object": lambda item: isinstance(item, dict),
        "string": lambda item: isinstance(item, str),
    }
    if declared_type is not None:
        checker = type_valid.get(str(declared_type))
        if checker is None:
            raise ValueError(f"unsupported JSON Schema type at {path}: {declared_type}")
        if not checker(value):
            raise ValueError(f"JSON value at {path} has the wrong type")
    if "const" in schema and value != schema["const"]:
        raise ValueError(f"JSON value at {path} does not match const")
    if "enum" in schema and value not in schema["enum"]:
        raise ValueError(f"JSON value at {path} is outside enum")

    if isinstance(value, dict):
        properties = schema.get("properties", {})
        if not isinstance(properties, dict):
            raise ValueError(f"JSON Schema properties at {path} must be an object")
        required = schema.get("required", [])
        if not isinstance(required, list):
            raise ValueError(f"JSON Schema required at {path} must be an array")
        for name in required:
            if name not in value:
                raise ValueError(f"JSON object at {path} is missing {name!r}")
        if schema.get("additionalProperties") is False:
            unexpected = set(value) - set(properties)
            if unexpected:
                raise ValueError(
                    f"JSON object at {path} has unexpected fields: {sorted(unexpected)}"
                )
        for name, item in value.items():
            declaration = properties.get(name)
            if isinstance(declaration, dict):
                _validate_json_value(item, declaration, f"{path}.{name}")
    elif isinstance(value, list) and isinstance(schema.get("items"), dict):
        for index, item in enumerate(value):
            _validate_json_value(item, schema["items"], f"{path}[{index}]")


def _validate_json_object(
    payload: Any, schema: dict[str, Any], *, label: str
) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError(f"sandbox {label} must be a JSON object")
    if schema.get("type", "object") != "object":
        raise ValueError(f"sandbox {label} schema must have type=object")
    _validate_json_value(payload, {**schema, "type": "object"}, "$")
    return payload


def _validate_program_schemas(program: ExperimentProgram) -> None:
    input_properties = program.input_schema.get("properties")
    input_required = program.input_schema.get("required")
    if (
        not isinstance(input_properties, dict)
        or not isinstance(input_properties.get("seed"), dict)
        or input_properties["seed"].get("type") != "integer"
        or not isinstance(input_required, list)
        or "seed" not in input_required
    ):
        raise ValueError("sandbox input_schema must require the injected integer seed")

    output_properties = program.output_schema.get("properties")
    output_required = program.output_schema.get("required")
    required_output = {"outcome", "cases_checked", "scope", "exact_arithmetic"}
    expected_types = {
        "outcome": "string",
        "cases_checked": "integer",
        "scope": "object",
        "exact_arithmetic": "boolean",
    }
    if not isinstance(output_properties, dict) or not isinstance(output_required, list):
        raise ValueError(
            "sandbox output_schema must declare properties and required fields"
        )
    if not required_output.issubset(output_required):
        raise ValueError(
            "sandbox output_schema must require outcome, cases_checked, scope, and exact_arithmetic"
        )
    for name, expected_type in expected_types.items():
        declaration = output_properties.get(name)
        if (
            not isinstance(declaration, dict)
            or declaration.get("type") != expected_type
        ):
            raise ValueError(
                f"sandbox output_schema must declare {name} as {expected_type}"
            )


def run_sandboxed_python(
    spec: ExperimentSpec,
    program: ExperimentProgram,
    config: ComputationConfig,
) -> HandlerEvidence:
    if not config.sandboxed_python_enabled:
        raise RuntimeError("sandboxed Python is disabled")
    declared_dependencies = {
        dependency.split(".", 1)[0] for dependency in program.dependencies
    }
    invalid_dependencies = {
        dependency.split(".", 1)[0]
        for dependency in program.dependencies
        if dependency.split(".", 1)[0] not in _ALLOWED_IMPORTS
    }
    if invalid_dependencies:
        raise UnsafeProgramError(
            f"program declares non-whitelisted dependencies: {sorted(invalid_dependencies)}"
        )
    imported_modules = validate_program_source(program.source)
    if imported_modules != declared_dependencies:
        raise UnsafeProgramError(
            "program dependencies must exactly match its imported modules"
        )
    _validate_program_schemas(program)
    docker_path = _find_docker_executable()
    if docker_path is None:
        raise RuntimeError("Docker executable was not found")
    input_payload = spec.arguments.get("input", {})
    if not isinstance(input_payload, dict):
        raise ValueError("sandbox input must be a JSON object")
    input_payload = {**input_payload, "seed": spec.seed}
    _validate_json_object(input_payload, program.input_schema, label="input")
    with tempfile.TemporaryDirectory(prefix="mathproofmesh_experiment_") as tmpdir:
        workdir = Path(tmpdir).resolve()
        workdir.chmod(0o755)
        program_path = workdir / "program.py"
        program_path.write_text(program.source, encoding="utf-8")
        program_path.chmod(0o644)
        command = build_docker_command(config, workdir)
        command[0] = docker_path
        try:
            completed = subprocess.run(
                command,
                input=json.dumps(input_payload, ensure_ascii=True),
                capture_output=True,
                text=True,
                timeout=config.sandbox_timeout_seconds,
                check=False,
                env={},
            )
        except subprocess.TimeoutExpired as exc:
            stdout = _bounded_process_text(exc.stdout, config.max_output_chars)
            stderr = _bounded_process_text(exc.stderr, config.max_output_chars)
            raise SandboxExecutionError(
                f"sandbox timed out after {config.sandbox_timeout_seconds} seconds",
                exit_code=None,
                stdout=stdout,
                stderr=stderr,
            ) from exc
    stdout = completed.stdout[: config.max_output_chars]
    stderr = completed.stderr[: config.max_output_chars]
    if completed.returncode != 0:
        raise SandboxExecutionError(
            f"sandbox exited with code {completed.returncode}: {stderr or stdout}",
            exit_code=completed.returncode,
            stdout=stdout,
            stderr=stderr,
        )
    try:
        payload = _validate_json_object(
            json.loads(stdout), program.output_schema, label="output"
        )
    except ValueError as exc:
        raise SandboxExecutionError(
            f"sandbox output was rejected: {exc}",
            exit_code=completed.returncode,
            stdout=stdout,
            stderr=stderr,
        ) from exc
    raw_outcome = str(payload.get("outcome", "inconclusive"))
    if raw_outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND.value:
        counterexample = payload.get("counterexample")
        if not isinstance(counterexample, dict):
            raise ValueError(
                "counterexample_found output requires counterexample object"
            )
        evidence = HandlerEvidence(
            outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
            evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
            counterexample=counterexample,
            scope=dict(payload.get("scope", {})),
            exact_arithmetic=bool(payload.get("exact_arithmetic", False)),
            cases_checked=max(0, int(payload.get("cases_checked", 0))),
            independently_verified=False,
            verification_notes=[
                "This is only a sandbox-generated counterexample candidate until a typed checker revalidates it."
            ],
            raw_output=payload,
        )
    elif raw_outcome in {
        ExperimentOutcome.NOT_REFUTED.value,
        ExperimentOutcome.CERTIFIED.value,
    }:
        evidence = HandlerEvidence(
            outcome=ExperimentOutcome.NOT_REFUTED,
            evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
            certificate=payload.get("certificate"),
            scope=dict(payload.get("scope", {})),
            exact_arithmetic=bool(payload.get("exact_arithmetic", False)),
            cases_checked=max(0, int(payload.get("cases_checked", 0))),
            verification_notes=[
                "Positive model-generated Python output is bounded evidence only and cannot establish the claim."
            ],
            raw_output=payload,
        )
    else:
        evidence = HandlerEvidence(
            outcome=ExperimentOutcome.INCONCLUSIVE,
            evidence_strength=EvidenceStrength.HEURISTIC,
            scope=dict(payload.get("scope", {})),
            verification_notes=[
                str(payload.get("note", "Sandbox result was inconclusive."))
            ],
            raw_output=payload,
        )
    evidence.process_exit_code = completed.returncode
    evidence.process_stdout = stdout
    evidence.process_stderr = stderr
    return evidence


def _bounded_process_text(value: str | bytes | None, limit: int) -> str:
    if isinstance(value, bytes):
        text = value.decode("utf-8", errors="replace")
    else:
        text = value or ""
    return text[:limit]
