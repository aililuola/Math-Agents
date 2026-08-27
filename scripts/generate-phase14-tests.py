#!/usr/bin/env python3
"""Generate one JUnit wrapper for every phase-14 authority test module."""

from __future__ import annotations

import csv
import sys
from pathlib import Path


ROOT = (
    Path.cwd()
    if Path.cwd().joinpath("migration", "test-state.csv").is_file()
    else Path(__file__).parent.parent
)
STATE = ROOT / "migration" / "test-state.csv"
OUTPUT_PATH = (
    ROOT
    / "mathproofmesh-server"
    / "src"
    / "test"
    / "java"
    / "io"
    / "github"
    / "aililuola"
    / "mathproofmesh"
    / "api"
)
OUTPUT = (
    Path("\\\\?\\" + str(OUTPUT_PATH.resolve()))
    if sys.platform == "win32"
    else OUTPUT_PATH
)


def java_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main() -> None:
    with STATE.open(encoding="utf-8-sig", newline="") as stream:
        rows = [
            row for row in csv.DictReader(stream) if row["primary_phase"] == "14"
        ]
    if len(rows) != 4:
        raise SystemExit(f"expected 4 phase-14 test rows, found {len(rows)}")

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
        content = f"""package io.github.aililuola.mathproofmesh.api;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class {class_name} {{
  @TempDir Path temporaryDirectory;

  static Stream<String> authorityCases() {{
    return Stream.of(
        {values});
  }}

  @ParameterizedTest(name = "{{0}}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {{
    ApiParityScenarios.verify("{class_name}", authorityFunction, temporaryDirectory);
  }}
}}
"""
        (OUTPUT / f"{class_name}.java").write_text(content, encoding="ascii")

    if function_count != 12:
        raise SystemExit(f"expected 12 phase-14 functions, found {function_count}")
    print(f"generated {len(rows)} classes and {function_count} authority cases")


if __name__ == "__main__":
    main()
