#!/usr/bin/env python3
"""Finalize only the phase-02 source and test inventory rows."""

from __future__ import annotations

import csv
import io
from pathlib import Path


ROOT = Path(__file__).absolute().parents[2]
PACKAGE_PATH = (
    "mathproofmesh-contracts/src/main/java/"
    "io/github/aililuola/mathproofmesh/contract"
)
TEST_PATH = (
    "mathproofmesh-contracts/src/test/java/"
    "io/github/aililuola/mathproofmesh/contract"
)

SOURCE_UPDATES = {
    "src/mathproofmesh/schemas.py": (
        "migrated",
        PACKAGE_PATH,
        (
            "ContractInventoryParityTest; CanonicalJsonParityTest; "
            "DerivedContractParityTest; PythonDifferentialParityTest; "
            "SchemasParityTest; Maven verify"
        ),
        (
            "102 records and 40 enums migrated with strict validation, "
            "defensive immutability, canonical JSON, and 16/16 hash vectors"
        ),
    ),
    "src/mathproofmesh/task_contracts.py": (
        "migrated",
        f"{PACKAGE_PATH}/TaskContractsFunctions.java",
        "TaskContractsParityTest; Maven verify",
        "All five task-contract functions migrated with Python parity cases",
    ),
}

TEST_UPDATES = {
    "tests/test_schemas.py": (
        "ported",
        f"{TEST_PATH}/SchemasParityTest.java",
        (
            "SchemasParityTest; DerivedContractParityTest; "
            "PythonDifferentialParityTest; Maven verify"
        ),
        "All 3 Python test functions ported; strict and differential cases pass",
    ),
    "tests/test_structured_payload_normalization.py": (
        "ported",
        f"{TEST_PATH}/StructuredPayloadNormalizationParityTest.java",
        "StructuredPayloadNormalizationParityTest; Maven verify",
        "All 6 Python test functions ported with deterministic normalization",
    ),
    "tests/test_task_contracts.py": (
        "ported",
        f"{TEST_PATH}/TaskContractsParityTest.java",
        "TaskContractsParityTest; Maven verify",
        "All 3 Python test functions ported with equivalent assertions",
    ),
}


def update_rows(path: Path, key_column: str, updates: dict[str, tuple[str, ...]]) -> None:
    raw = path.read_text(encoding="utf-8")
    lines = raw.splitlines(keepends=True)
    header = next(csv.reader([lines[0].rstrip("\r\n")]))
    expected_tail = ["status", "java_path", "verified_by", "notes"]
    if header[-4:] != expected_tail or header[0] != key_column:
        raise RuntimeError(f"Unexpected inventory schema: {path}")

    found: set[str] = set()
    output: list[str] = [lines[0]]
    for physical_line in lines[1:]:
        newline = "\r\n" if physical_line.endswith("\r\n") else "\n"
        body = physical_line.rstrip("\r\n")
        row = next(csv.reader([body]))
        key = row[0]
        if key in updates:
            if row[-4] not in {"pending", updates[key][0]}:
                raise RuntimeError(f"Unexpected status for {key}: {row[-4]}")
            row[-4:] = list(updates[key])
            buffer = io.StringIO()
            csv.writer(buffer, lineterminator="").writerow(row)
            physical_line = buffer.getvalue() + newline
            found.add(key)
        output.append(physical_line)

    missing = set(updates) - found
    if missing:
        raise RuntimeError(f"Missing inventory rows in {path}: {sorted(missing)}")
    path.write_text("".join(output), encoding="utf-8", newline="")


def main() -> None:
    update_rows(
        ROOT / "migration/source-state.csv",
        "source_file",
        SOURCE_UPDATES,
    )
    update_rows(
        ROOT / "migration/test-state.csv",
        "python_test_file",
        TEST_UPDATES,
    )
    print("PHASE-02 STATE ROWS: PASS source=2 test=3")


if __name__ == "__main__":
    main()
