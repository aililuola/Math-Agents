#!/usr/bin/env python3
"""Generate one JUnit wrapper for every phase-13 authority test module."""

from __future__ import annotations

import csv
from pathlib import Path


ROOT = (
    Path.cwd()
    if Path.cwd().joinpath("migration", "test-state.csv").is_file()
    else Path(__file__).parent.parent
)
STATE = ROOT / "migration" / "test-state.csv"
OUTPUT = (
    ROOT
    / "mathproofmesh-server"
    / "src"
    / "test"
    / "java"
    / "io"
    / "github"
    / "aililuola"
    / "mathproofmesh"
    / "workflow"
)


def java_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main() -> None:
    with STATE.open(encoding="utf-8-sig", newline="") as stream:
        rows = [
            row for row in csv.DictReader(stream) if row["primary_phase"] == "13"
        ]
    if len(rows) != 8:
        raise SystemExit(f"expected 8 phase-13 test rows, found {len(rows)}")

    function_count = 0
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for row in rows:
        class_name = row["target_java_test"].removesuffix(".java")
        functions = [
            value.strip()
            for value in row["test_function_names"].split("|")
            if value.strip()
        ]
        expected = int(row["python_test_functions"])
        if len(functions) != expected:
            raise SystemExit(
                f"{row['python_test_file']}: expected {expected}, found {len(functions)}"
            )
        function_count += len(functions)
        values = ",\n        ".join(java_string(value) for value in functions)
        content = f"""package io.github.aililuola.mathproofmesh.workflow;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class {class_name} {{
  static Stream<String> authorityCases() {{
    return Stream.of(
        {values});
  }}

  @ParameterizedTest(name = "{{0}}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {{
    TemporalParityScenarios.verify("{class_name}", authorityFunction);
  }}
}}
"""
        (OUTPUT / f"{class_name}.java").write_text(content, encoding="ascii")

    if function_count != 25:
        raise SystemExit(f"expected 25 phase-13 functions, found {function_count}")
    print(f"generated {len(rows)} classes and {function_count} authority cases")


if __name__ == "__main__":
    main()
