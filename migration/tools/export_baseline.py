#!/usr/bin/env python3
"""Export the immutable Python compatibility baseline for migration phase 00."""

from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import importlib
import inspect
import json
import os
import pkgutil
import platform
import re
import shutil
import subprocess
import sys
import tomllib
from collections import Counter, defaultdict
from enum import Enum
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel
from pydantic_core import PydanticUndefined


UTF8 = "utf-8"
EXPECTED_MANIFEST_SHA256 = (
    "9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770"
)
EXPECTED_ZIP_SHA256 = (
    "5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2"
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding=UTF8,
        newline="\n",
    )


def write_jsonl(path: Path, values: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding=UTF8, newline="\n") as handle:
        for value in values:
            handle.write(
                json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            )
            handle.write("\n")


def command_output(command: list[str]) -> dict[str, Any]:
    try:
        completed = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            encoding=UTF8,
            errors="replace",
        )
    except FileNotFoundError:
        return {"available": False, "command": command, "exit_code": None, "output": ""}
    output = "\n".join(
        part.strip() for part in (completed.stdout, completed.stderr) if part.strip()
    )
    return {
        "available": True,
        "command": command,
        "exit_code": completed.returncode,
        "output": output,
    }


def read_manifest(path: Path) -> list[dict[str, str]]:
    raw = path.read_bytes()
    manifest_hash = hashlib.sha256(raw).hexdigest()
    if manifest_hash != EXPECTED_MANIFEST_SHA256:
        raise RuntimeError(
            f"source manifest hash mismatch: {manifest_hash} != "
            f"{EXPECTED_MANIFEST_SHA256}"
        )
    if raw.startswith(b"\xef\xbb\xbf") or b"\r\n" in raw or not raw.endswith(b"\n"):
        raise RuntimeError("source manifest is not canonical UTF-8 LF text")
    rows: list[dict[str, str]] = []
    for line in raw.decode(UTF8).splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None:
            raise RuntimeError(f"malformed source manifest line: {line!r}")
        rows.append({"sha256": match.group(1), "path": match.group(2)})
    if len(rows) != 401:
        raise RuntimeError(f"expected 401 manifest rows, got {len(rows)}")
    if [row["path"] for row in rows] != sorted(
        (row["path"] for row in rows), key=lambda value: value.encode(UTF8)
    ):
        raise RuntimeError("source manifest is not sorted by UTF-8 path bytes")
    return rows


def load_csv(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise RuntimeError(f"missing CSV header: {path}")
        return list(reader.fieldnames), list(reader)


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding=UTF8, newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=fieldnames,
            extrasaction="raise",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(rows)


def json_safe(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return value.model_dump(mode="json")
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [json_safe(item) for item in value]
    try:
        json.dumps(value)
    except TypeError:
        return repr(value)
    return value


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def stable_hash(value: Any) -> str:
    raw = value.encode(UTF8) if isinstance(value, str) else canonical_json(value).encode(UTF8)
    return hashlib.sha256(raw).hexdigest()


def import_inventory(source_root: Path) -> tuple[list[Any], list[dict[str, Any]]]:
    package_path = source_root / "src" / "mathproofmesh"
    sys.path.insert(0, str(source_root / "src"))
    names = ["mathproofmesh"]
    names.extend(
        item.name
        for item in pkgutil.walk_packages(
            [str(package_path)],
            prefix="mathproofmesh.",
        )
    )
    modules: list[Any] = []
    inventory: list[dict[str, Any]] = []
    for name in sorted(set(names)):
        try:
            module = importlib.import_module(name)
        except Exception as exc:  # pragma: no cover - phase gate evidence
            inventory.append(
                {
                    "module": name,
                    "status": "failed",
                    "error_type": type(exc).__name__,
                    "error": str(exc),
                }
            )
            continue
        modules.append(module)
        inventory.append({"module": name, "status": "imported"})
    failures = [item for item in inventory if item["status"] != "imported"]
    if failures:
        raise RuntimeError(f"module import failures: {failures}")
    return modules, inventory


def export_schemas(
    modules: list[Any],
    output_root: Path,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    model_types: dict[str, type[BaseModel]] = {}
    enum_types: dict[str, type[Enum]] = {}
    for module in modules:
        for value in vars(module).values():
            if not inspect.isclass(value) or value.__module__ != module.__name__:
                continue
            qualified_name = f"{value.__module__}.{value.__qualname__}"
            if issubclass(value, BaseModel):
                model_types[qualified_name] = value
            elif issubclass(value, Enum):
                enum_types[qualified_name] = value

    schema_dir = output_root / "schemas"
    schema_dir.mkdir(parents=True, exist_ok=True)
    schema_index: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    for index, (qualified_name, model_type) in enumerate(
        sorted(model_types.items()), start=1
    ):
        filename = f"{index:04d}.json"
        try:
            schema = model_type.model_json_schema(mode="validation")
        except Exception as exc:  # pragma: no cover - phase gate evidence
            failures.append(
                {
                    "qualified_name": qualified_name,
                    "error_type": type(exc).__name__,
                    "error": str(exc),
                }
            )
            continue
        write_json(
            schema_dir / filename,
            {"qualified_name": qualified_name, "schema": schema},
        )
        schema_index.append(
            {
                "qualified_name": qualified_name,
                "file": filename,
                "schema_sha256": sha256_file(schema_dir / filename),
            }
        )
    if failures:
        write_json(output_root / "schema-export-failures.json", failures)
        raise RuntimeError(f"Pydantic schema export failures: {failures}")
    write_json(schema_dir / "index.json", schema_index)

    enum_inventory = [
        {
            "qualified_name": qualified_name,
            "members": [
                {"name": member.name, "value": json_safe(member.value)}
                for member in enum_type
            ],
        }
        for qualified_name, enum_type in sorted(enum_types.items())
    ]
    write_json(output_root / "enum-literals.json", enum_inventory)
    return schema_index, enum_inventory


def export_config_defaults(modules: list[Any], output_root: Path) -> dict[str, Any]:
    inventory: dict[str, Any] = {}
    for module in modules:
        for model_type in vars(module).values():
            if (
                not inspect.isclass(model_type)
                or model_type.__module__ != module.__name__
                or not issubclass(model_type, BaseModel)
            ):
                continue
            if model_type.__module__ != "mathproofmesh.config" and model_type.__name__ != (
                "DesktopSettings"
            ):
                continue
            fields: dict[str, Any] = {}
            for name, field in model_type.model_fields.items():
                required = field.is_required()
                default: Any = None
                if field.default is not PydanticUndefined:
                    default = json_safe(field.default)
                elif field.default_factory is not None:
                    default = json_safe(field.default_factory())
                fields[name] = {
                    "annotation": str(field.annotation),
                    "required": required,
                    "default": default,
                    "default_factory": (
                        getattr(field.default_factory, "__qualname__", None)
                        if field.default_factory is not None
                        else None
                    ),
                }
            inventory[f"{model_type.__module__}.{model_type.__qualname__}"] = fields
    write_json(output_root / "config-defaults.json", inventory)
    return inventory


def flatten_values(value: Any, prefix: str = "") -> list[tuple[str, Any]]:
    if isinstance(value, dict):
        flattened: list[tuple[str, Any]] = []
        for key in sorted(value, key=lambda item: (type(item).__name__, str(item))):
            child = f"{prefix}.{key}" if prefix else str(key)
            flattened.extend(flatten_values(value[key], child))
        return flattened
    if isinstance(value, list):
        if not value:
            return [(prefix, [])]
        flattened = []
        for item in value:
            flattened.extend(flatten_values(item, f"{prefix}[]"))
        return flattened
    return [(prefix, value)]


def secret_like(path: str) -> bool:
    lowered = path.lower()
    return any(
        token in lowered for token in ("api_key", "password", "secret", "bearer", "token")
    )


def export_config_fixtures(
    source_root: Path,
    manifest_rows: list[dict[str, str]],
    output_root: Path,
) -> dict[str, Any]:
    from mathproofmesh.config import load_config

    fixture_root = output_root / "config-fixtures"
    source_fixture_root = fixture_root / "source"
    normalized_root = fixture_root / "normalized"
    config_paths = [
        row["path"]
        for row in manifest_rows
        if row["path"].endswith((".yaml", ".yml")) or row["path"] == ".env.example"
    ]
    copied: list[dict[str, Any]] = []
    field_observations: dict[str, dict[str, Any]] = defaultdict(
        lambda: {
            "source_files": set(),
            "observed_types": set(),
            "observed_values": set(),
            "environment_variables": set(),
        }
    )
    normalized_profiles: list[dict[str, Any]] = []

    for relative in config_paths:
        source = source_root / Path(relative)
        destination = source_fixture_root / Path(relative)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        copied.append(
            {
                "source_path": relative,
                "source_sha256": sha256_file(source),
                "fixture_path": destination.relative_to(output_root).as_posix(),
                "fixture_sha256": sha256_file(destination),
            }
        )
        if relative.endswith((".yaml", ".yml")):
            raw = yaml.safe_load(source.read_text(encoding=UTF8))
            for field_path, value in flatten_values(raw):
                item = field_observations[field_path]
                item["source_files"].add(relative)
                item["observed_types"].add(type(value).__name__)
                if secret_like(field_path):
                    item["observed_values"].add("[REDACTED]")
                else:
                    item["observed_values"].add(
                        json.dumps(json_safe(value), ensure_ascii=False, sort_keys=True)
                    )
                if field_path.lower().endswith("_env") and isinstance(value, str):
                    item["environment_variables"].add(value)

        if relative.startswith("config.") and relative.endswith(".yaml"):
            config = load_config(source)
            normalized = config.model_dump(mode="json")
            normalized_path = normalized_root / f"{Path(relative).stem}.json"
            write_json(normalized_path, normalized)
            normalized_profiles.append(
                {
                    "source_path": relative,
                    "normalized_path": normalized_path.relative_to(output_root).as_posix(),
                    "normalized_sha256": sha256_file(normalized_path),
                    "agent_count": len(config.agents),
                }
            )

    env_entries: list[dict[str, Any]] = []
    env_path = source_root / ".env.example"
    for line in env_path.read_text(encoding=UTF8).splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        name, separator, value = stripped.partition("=")
        if not separator:
            raise RuntimeError(f"malformed .env.example line: {line!r}")
        env_entries.append(
            {
                "name": name,
                "secret": True,
                "example_value": "[EMPTY]" if not value else "[REDACTED]",
                "source": ".env.example",
            }
        )
    write_json(fixture_root / "environment-variable-inventory.json", env_entries)

    field_inventory = []
    for path, item in sorted(field_observations.items()):
        field_inventory.append(
            {
                "source_key": path,
                "java_target_key": path,
                "source_files": sorted(item["source_files"]),
                "types": sorted(item["observed_types"]),
                "default_or_observed_values": sorted(item["observed_values"]),
                "secret_source": (
                    "environment-variable-name-reference"
                    if path.lower().endswith("_env")
                    else ("secret-redacted" if secret_like(path) else "not-secret")
                ),
                "environment_variables": sorted(item["environment_variables"]),
                "unknown_field_policy": "forbid",
                "validator": "Pydantic SystemConfig strict field and model validators",
            }
        )
    write_json(fixture_root / "config-field-inventory.json", field_inventory)
    write_json(fixture_root / "fixture-manifest.json", copied)
    write_json(fixture_root / "normalized-profile-manifest.json", normalized_profiles)
    return {
        "source_fixture_count": len(copied),
        "normalized_profile_count": len(normalized_profiles),
        "field_count": len(field_inventory),
        "environment_variable_count": len(env_entries),
    }


def export_hash_vectors(output_root: Path) -> list[dict[str, Any]]:
    from mathproofmesh.schemas import (
        CheckpointStatus,
        ClaimCard,
        ClaimStatus,
        EvidenceType,
        MemoryTier,
        MessageEnvelope,
        MessageType,
        ProblemContract,
        ProblemKind,
        ProofCheckpoint,
        ProofStep,
        QuantifierSpec,
        RouteRole,
        TaskRequirement,
        VariableBinding,
    )

    primitive_values: list[tuple[str, Any]] = [
        ("raw_ascii_string", "MathProofMesh"),
        ("raw_empty_string", ""),
        ("raw_chinese_string", "\u6570\u5b66\u8bc1\u660e\u7f51\u683c"),
        ("raw_supplementary_string", "\U0001f680"),
        ("null", None),
        ("boolean_true", True),
        ("integer", 42),
        ("negative_integer", -17),
        ("float", 1.25),
        ("small_float", 1e-7),
        ("list", [None, True, 3, "\u6570\u5b66"]),
        (
            "nested_mapping",
            {
                "a": {"z": -1, "items": [3, 2, 1]},
                "\U0001f680": "supplementary",
                "\uffff": "bmp",
            },
        ),
    ]
    vectors: list[dict[str, Any]] = []
    for name, value in primitive_values:
        raw_hash_input = value if isinstance(value, str) else canonical_json(value)
        vectors.append(
            {
                "name": name,
                "kind": "primitive",
                "input": value,
                "canonical_json": canonical_json(value),
                "hash_input_mode": "raw_utf8_string" if isinstance(value, str) else "canonical_json",
                "hash_input": raw_hash_input,
                "stable_hash": stable_hash(value),
            }
        )

    quantifier = QuantifierSpec(
        order=0,
        kind="forall",
        variable_id="n",
        display_name="n",
        domain="positive integers",
        restrictions=["n >= 1"],
    )
    binding = VariableBinding(
        variable_id="n",
        display_name="n",
        domain="positive integers",
        owner_scope="problem",
        aliases=["n"],
    )
    message = MessageEnvelope(
        schema_version="1",
        message_id="msg_vector_000001",
        problem_hash="a" * 64,
        source_agent_id="agent-prover",
        source_route_id="route-a",
        source_role=RouteRole.PROVER,
        target_route_ids=["route-b"],
        message_type=MessageType.VERIFIED_LEMMA,
        statement="For every positive integer n, the odd-number sum is n^2.",
        normalized_statement="For every positive integer n, the odd-number sum is n^2.",
        assumptions=["n is a positive integer"],
        conclusion="1+3+...+(2n-1)=n^2",
        quantifiers=[quantifier],
        variable_bindings=[binding],
        dependencies=["claim-base"],
        evidence_type=EvidenceType.NATURAL_PROOF_AUDITED,
        memory_tier=MemoryTier.FACT,
        verification_status=ClaimStatus.VERIFIED,
        verification_confidence=1.0,
        normalization_confidence=1.0,
        round_created=2,
        ttl_rounds=3,
        created_at="2026-07-29T00:00:00+00:00",
    )
    claim = ClaimCard(
        claim_id="claim_vector_000001",
        statement="The first n odd integers sum to n^2.",
        assumptions=["n is a positive integer"],
        conclusion="1+3+...+(2n-1)=n^2",
        dependencies=["claim-base"],
        status=ClaimStatus.VERIFIED,
        source_agent_id="agent-prover",
        self_confidence=1.0,
        verification_confidence=1.0,
    )
    step = ProofStep(
        step_id="step_vector_000001",
        statement="Add the next odd integer 2n+1.",
        justification="n^2+(2n+1)=(n+1)^2",
        dependencies=["claim-base"],
        is_key_step=True,
        confidence=1.0,
    )
    checkpoint = ProofCheckpoint(
        checkpoint_id="checkpoint_vector_000001",
        parent_checkpoint_id="checkpoint_vector_parent",
        problem_hash="a" * 64,
        path_id="path-a",
        strategy_id="strategy-induction",
        source_agent_id="agent-prover",
        source_delta_id="delta-vector",
        segment_index=1,
        verified_steps=[step],
        verified_claim_ids=[claim.claim_id],
        active_assumptions=["n is a positive integer"],
        remaining_subgoals=[],
        current_goal="Close the induction.",
        known_risks=[],
        final_answer="1+3+...+(2n-1)=n^2",
        proof_complete=True,
        status=CheckpointStatus.COMMITTED,
        verification_report_ids=["report-vector"],
        working_notes="excluded from hash",
        proof_sketch="excluded from hash",
        created_at="2026-07-29T00:00:00+00:00",
    )
    problem = ProblemContract(
        problem_id="problem_vector_000001",
        exact_statement="Prove that 1+3+...+(2n-1)=n^2 for every positive integer n.",
        normalized_statement="Prove that 1+3+...+(2n-1)=n^2 for every positive integer n.",
        original_statement="Prove that 1+3+...+(2n-1)=n^2 for every positive integer n.",
        canonical_statement="Prove that 1+3+...+(2n-1)=n^2 for every positive integer n.",
        interpretation_source="original",
        problem_kind=ProblemKind.PROOF,
        task_requirements=[TaskRequirement.PROOF],
        output_language="en",
        created_at="2026-07-29T00:00:00+00:00",
    )

    model_vectors = [
        (
            "message_envelope",
            message,
            {
                "content_hash": message.content_hash,
                "semantic_hash": message.expected_semantic_hash(),
                "immutable_payload": message.immutable_payload(),
            },
        ),
        ("claim_card", claim, {"content_hash": claim.content_hash}),
        ("proof_checkpoint", checkpoint, {"content_hash": checkpoint.content_hash}),
        (
            "problem_contract",
            problem,
            {
                "content_hash": problem.integrity_hash,
                "goal_hash": problem.goal_hash,
            },
        ),
    ]
    for name, model, derived in model_vectors:
        dumped = model.model_dump(mode="json")
        vectors.append(
            {
                "name": name,
                "kind": "pydantic_model",
                "model": f"{type(model).__module__}.{type(model).__qualname__}",
                "input": dumped,
                "canonical_json": canonical_json(dumped),
                "stable_hash": stable_hash(dumped),
                **derived,
            }
        )
    write_jsonl(output_root / "hash-vectors.jsonl", vectors)
    return vectors


def build_test_inventory(
    source_root: Path,
    mapping_rows: list[dict[str, str]],
    manifest: dict[str, str],
    collect_output: Path,
    output_root: Path,
) -> dict[str, Any]:
    collected_lines = collect_output.read_text(encoding=UTF8).splitlines()
    node_ids = [
        line.strip()
        for line in collected_lines
        if line.startswith("tests/") and "::" in line
    ]
    collected_by_file = Counter(node_id.split("::", 1)[0] for node_id in node_ids)
    files: list[dict[str, Any]] = []
    explicit_total = 0
    ast_mismatches: list[dict[str, Any]] = []
    for row in mapping_rows:
        relative = row["python_test_file"]
        path = source_root / Path(relative)
        tree = ast.parse(path.read_text(encoding=UTF8), filename=relative)
        ast_names = [
            node.name
            for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
        ]
        mapped_names = [
            name.strip()
            for name in row["test_function_names"].split("|")
            if name.strip()
        ]
        declared_count = int(row["python_test_functions"])
        explicit_total += declared_count
        if declared_count != len(ast_names) or sorted(mapped_names) != sorted(ast_names):
            ast_mismatches.append(
                {
                    "path": relative,
                    "declared_count": declared_count,
                    "ast_count": len(ast_names),
                    "mapped_names": mapped_names,
                    "ast_names": ast_names,
                }
            )
        files.append(
            {
                **row,
                "source_sha256": manifest[relative],
                "source_size_bytes": path.stat().st_size,
                "ast_test_functions": ast_names,
                "collected_items": collected_by_file.get(relative, 0),
            }
        )
    if ast_mismatches:
        raise RuntimeError(f"test mapping/AST mismatches: {ast_mismatches}")
    if explicit_total != 707 or len(node_ids) != 759:
        raise RuntimeError(
            f"test inventory mismatch: explicit={explicit_total}, collected={len(node_ids)}"
        )
    inventory = {
        "summary": {
            "mapped_files": len(mapping_rows),
            "test_modules": sum(
                1 for row in mapping_rows if row["file_role"] == "test_module"
            ),
            "support_files": sum(
                1 for row in mapping_rows if row["file_role"] != "test_module"
            ),
            "explicit_test_functions": explicit_total,
            "collected_test_items": len(node_ids),
            "parameterized_expansion": len(node_ids) - explicit_total,
        },
        "files": files,
        "collected_node_ids": node_ids,
    }
    write_json(output_root / "test-inventory.json", inventory)
    return inventory


def build_state_files(
    target_root: Path,
    source_root: Path,
    manifest_rows: list[dict[str, str]],
    source_header: list[str],
    source_rows: list[dict[str, str]],
    test_header: list[str],
    test_rows: list[dict[str, str]],
    aux_header: list[str],
    aux_rows: list[dict[str, str]],
    output_root: Path,
) -> dict[str, Any]:
    manifest = {row["path"]: row["sha256"] for row in manifest_rows}
    sizes = {
        row["path"]: (source_root / Path(row["path"])).stat().st_size
        for row in manifest_rows
    }

    source_state = []
    for row in source_rows:
        relative = row["source_file"]
        source_state.append(
            {
                **row,
                "source_sha256": manifest[relative],
                "source_size_bytes": sizes[relative],
                "status": "pending",
                "java_path": "",
                "verified_by": "",
                "notes": "",
            }
        )
    write_csv(
        target_root / "migration" / "source-state.csv",
        source_header
        + [
            "source_sha256",
            "source_size_bytes",
            "status",
            "java_path",
            "verified_by",
            "notes",
        ],
        source_state,
    )

    test_state = []
    for row in test_rows:
        relative = row["python_test_file"]
        test_state.append(
            {
                **row,
                "source_sha256": manifest[relative],
                "source_size_bytes": sizes[relative],
                "status": "pending",
                "java_path": "",
                "verified_by": "",
                "notes": "",
            }
        )
    write_csv(
        target_root / "migration" / "test-state.csv",
        test_header
        + [
            "source_sha256",
            "source_size_bytes",
            "status",
            "java_path",
            "verified_by",
            "notes",
        ],
        test_state,
    )

    aux_state = []
    for row in aux_rows:
        phase_zero = row["phase"] == "00"
        notes = ""
        if phase_zero:
            notes = (
                "baseline_only_verified"
                if row["disposition"] == "baseline-only"
                else "byte_copy_verified"
            )
        aux_state.append(
            {
                **row,
                "status": "verified" if phase_zero else "pending",
                "java_path": row["target_path"] if phase_zero else "",
                "verified_by": "phase-00 source SHA-256 gate" if phase_zero else "",
                "notes": notes,
            }
        )
    write_csv(
        target_root / "migration" / "auxiliary-state.csv",
        aux_header + ["status", "java_path", "verified_by", "notes"],
        aux_state,
    )

    mapping_by_path: dict[str, str] = {}
    for row in source_rows:
        mapping_by_path[row["source_file"]] = "source"
    for row in test_rows:
        mapping_by_path[row["python_test_file"]] = "test"
    for row in aux_rows:
        mapping_by_path[row["source_file"]] = "auxiliary"
    if set(mapping_by_path) != set(manifest) or len(mapping_by_path) != 401:
        raise RuntimeError("three mapping CSVs do not exactly cover the manifest")

    source_manifest_rows = [
        {
            "path": row["path"],
            "size_bytes": sizes[row["path"]],
            "sha256": row["sha256"],
            "mapping_group": mapping_by_path[row["path"]],
        }
        for row in manifest_rows
    ]
    write_csv(
        output_root / "source-manifest.csv",
        ["path", "size_bytes", "sha256", "mapping_group"],
        source_manifest_rows,
    )
    proof = {
        "source_rows": len(source_rows),
        "test_rows": len(test_rows),
        "auxiliary_rows": len(aux_rows),
        "union_rows": len(mapping_by_path),
        "duplicates": 0,
        "missing": [],
        "extra": [],
        "manifest_sha256": EXPECTED_MANIFEST_SHA256,
    }
    write_json(output_root / "mapping-coverage-proof.json", proof)
    return proof


def copy_phase_zero_auxiliary(
    source_root: Path,
    target_root: Path,
    aux_rows: list[dict[str, str]],
) -> list[dict[str, Any]]:
    copies: list[dict[str, Any]] = []
    for row in aux_rows:
        if row["phase"] != "00":
            continue
        relative = row["source_file"]
        source = source_root / Path(relative)
        if relative == "BUILD_INFO.json":
            destination = target_root / "migration" / "baseline" / "BUILD_INFO.json"
        elif relative == "pyproject.toml":
            destination = target_root / "migration" / "baseline" / "pyproject.toml"
        else:
            destination = (
                target_root / "migration" / "baseline" / "auxiliary" / Path(relative)
            )
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        source_hash = sha256_file(source)
        destination_hash = sha256_file(destination)
        if source_hash != destination_hash or source_hash != row["source_sha256"]:
            raise RuntimeError(f"phase-00 auxiliary copy mismatch: {relative}")
        copies.append(
            {
                "source_path": relative,
                "target_path": destination.relative_to(target_root).as_posix(),
                "sha256": source_hash,
                "size_bytes": source.stat().st_size,
            }
        )
    return copies


def build_baseline(
    target_root: Path,
    source_root: Path,
    manifest_rows: list[dict[str, str]],
    test_inventory: dict[str, Any],
    schema_index: list[dict[str, Any]],
    enum_inventory: list[dict[str, Any]],
    config_summary: dict[str, Any],
    phase_zero_copies: list[dict[str, Any]],
) -> dict[str, Any]:
    workspace_root = target_root.parent
    zip_path = (
        target_root
        / "migration"
        / "input"
        / "Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip"
    )
    zip_hash = sha256_file(zip_path)
    if zip_hash != EXPECTED_ZIP_SHA256:
        raise RuntimeError(f"source ZIP hash mismatch: {zip_hash}")
    pyproject = tomllib.loads((source_root / "pyproject.toml").read_text(encoding=UTF8))
    build_info = json.loads((source_root / "BUILD_INFO.json").read_text(encoding=UTF8))
    file_rows = [
        {
            "path": row["path"],
            "size_bytes": (source_root / Path(row["path"])).stat().st_size,
            "sha256": row["sha256"],
        }
        for row in manifest_rows
    ]
    java_home = target_root / ".tools" / "jdk-25"
    baseline_log = target_root / "migration" / "logs" / "python-baseline.log"
    baseline_log_text = baseline_log.read_text(encoding=UTF8, errors="replace")
    summary_match = re.search(r"759 passed(?:, \d+ warnings)? in [0-9.]+s", baseline_log_text)
    if summary_match is None:
        raise RuntimeError("successful 759-test summary is absent from baseline log")
    specifications = {}
    for name in (
        "CODEX_MASTER_INSTRUCTIONS.md",
        "CODEX_START_HERE.md",
        "MIGRATION_PLAN.md",
        "OPS_CONFIG_DOC_MIGRATION_MAP.csv",
        "PHASE_GATES.yaml",
        "PYTHON_SOURCE_MIGRATION_MAP.csv",
        "PYTHON_TEST_MIGRATION_MAP.csv",
        "SHA256SUMS.txt",
        "SOURCE_SNAPSHOT_SHA256SUMS.txt",
    ):
        specifications[name] = sha256_file(target_root / name)
    return {
        "schema_version": "1.0",
        "phase": "00",
        "source_absolute_path": str(workspace_root),
        "baseline_execution_source_path": str(source_root),
        "target_root": str(target_root),
        "source_zip": {
            "path": str(zip_path),
            "sha256": zip_hash,
        },
        "source_manifest": {
            "path": str(target_root / "SOURCE_SNAPSHOT_SHA256SUMS.txt"),
            "sha256": EXPECTED_MANIFEST_SHA256,
            "file_count": len(file_rows),
            "files": file_rows,
        },
        "python_project": {
            "name": pyproject["project"]["name"],
            "version": pyproject["project"]["version"],
            "requires_python": pyproject["project"]["requires-python"],
            "build_info": build_info,
        },
        "planned_python_tests": 759,
        "python_baseline": {
            "result": "PASS",
            "summary": summary_match.group(0),
            "inventory": test_inventory["summary"],
            "log": "migration/logs/python-baseline.log",
            "editable_install": False,
        },
        "exports": {
            "pydantic_schemas": len(schema_index),
            "enums": len(enum_inventory),
            "config": config_summary,
            "phase_zero_auxiliary_copies": phase_zero_copies,
        },
        "environment": {
            "python": {
                "version": sys.version,
                "executable": sys.executable,
            },
            "os": {
                "platform": platform.platform(),
                "system": platform.system(),
                "release": platform.release(),
                "machine": platform.machine(),
            },
            "git": command_output(["git", "--version"]),
            "docker": command_output(["docker", "version"]),
            "docker_compose": command_output(["docker", "compose", "version"]),
            "java": command_output([str(java_home / "bin" / "java.exe"), "-version"]),
            "javac": command_output([str(java_home / "bin" / "javac.exe"), "-version"]),
        },
        "specification_sha256": specifications,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-root", required=True, type=Path)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--collect-output", required=True, type=Path)
    args = parser.parse_args()

    target_root = args.target_root
    source_root = args.source_root
    output_root = target_root / "migration" / "baseline"
    output_root.mkdir(parents=True, exist_ok=True)

    manifest_rows = read_manifest(target_root / "SOURCE_SNAPSHOT_SHA256SUMS.txt")
    manifest = {row["path"]: row["sha256"] for row in manifest_rows}
    for row in manifest_rows:
        source_path = source_root / Path(row["path"])
        if not source_path.is_file() or sha256_file(source_path) != row["sha256"]:
            raise RuntimeError(f"extracted source mismatch: {row['path']}")

    source_header, source_rows = load_csv(
        target_root / "PYTHON_SOURCE_MIGRATION_MAP.csv"
    )
    test_header, test_rows = load_csv(target_root / "PYTHON_TEST_MIGRATION_MAP.csv")
    aux_header, aux_rows = load_csv(target_root / "OPS_CONFIG_DOC_MIGRATION_MAP.csv")
    if (len(source_rows), len(test_rows), len(aux_rows)) != (142, 167, 92):
        raise RuntimeError("mapping CSV row counts are not 142/167/92")

    modules, module_inventory = import_inventory(source_root)
    write_json(output_root / "module-import-inventory.json", module_inventory)
    schema_index, enum_inventory = export_schemas(modules, output_root)
    export_config_defaults(modules, output_root)
    config_summary = export_config_fixtures(
        source_root, manifest_rows, output_root
    )
    hash_vectors = export_hash_vectors(output_root)
    test_inventory = build_test_inventory(
        source_root,
        test_rows,
        manifest,
        args.collect_output,
        output_root,
    )
    coverage = build_state_files(
        target_root,
        source_root,
        manifest_rows,
        source_header,
        source_rows,
        test_header,
        test_rows,
        aux_header,
        aux_rows,
        output_root,
    )
    phase_zero_copies = copy_phase_zero_auxiliary(
        source_root, target_root, aux_rows
    )
    baseline = build_baseline(
        target_root,
        source_root,
        manifest_rows,
        test_inventory,
        schema_index,
        enum_inventory,
        config_summary,
        phase_zero_copies,
    )
    write_json(target_root / "migration" / "BASELINE.json", baseline)
    write_json(
        output_root / "export-summary.json",
        {
            "module_imports": len(module_inventory),
            "pydantic_schemas": len(schema_index),
            "enums": len(enum_inventory),
            "hash_vectors": len(hash_vectors),
            "test_inventory": test_inventory["summary"],
            "config": config_summary,
            "mapping_coverage": coverage,
            "phase_zero_auxiliary_copies": len(phase_zero_copies),
        },
    )
    print(
        json.dumps(
            {
                "status": "PASS",
                "schemas": len(schema_index),
                "enums": len(enum_inventory),
                "hash_vectors": len(hash_vectors),
                "tests": test_inventory["summary"],
                "mapping_coverage": coverage,
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
