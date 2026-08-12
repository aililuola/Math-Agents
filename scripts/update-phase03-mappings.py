from __future__ import annotations

import csv
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def update_rows(
    relative_path: str,
    key_column: str,
    updates: dict[str, dict[str, str]],
) -> None:
    path = ROOT / relative_path
    with path.open("r", encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None:
            raise RuntimeError(f"{relative_path} has no header")
        fieldnames = reader.fieldnames
        rows = list(reader)

    matched: set[str] = set()
    for row in rows:
        key = row[key_column]
        replacement = updates.get(key)
        if replacement is None:
            continue
        unknown_columns = set(replacement) - set(fieldnames)
        if unknown_columns:
            raise RuntimeError(
                f"{relative_path}: unknown update columns {sorted(unknown_columns)}"
            )
        row.update(replacement)
        matched.add(key)

    if matched != set(updates):
        missing = sorted(set(updates) - matched)
        raise RuntimeError(f"{relative_path}: missing rows {missing}")

    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(
            destination,
            fieldnames=fieldnames,
            lineterminator="\r\n",
        )
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    config_package = (
        "mathproofmesh-server/src/main/java/"
        "io/github/aililuola/mathproofmesh/config"
    )
    config_tests = (
        "ConfigFieldConstraintParityTest; ConfigProfileParityTest; "
        "ConfigValidatorParityTest; StrictYamlConfigLoaderTest; Maven verify"
    )
    update_rows(
        "migration/source-state.csv",
        "source_file",
        {
            "src/mathproofmesh/config.py": {
                "status": "migrated",
                "java_path": config_package,
                "verified_by": config_tests,
                "notes": (
                    "38 typed records, 556 fields, 577 field constraints, "
                    "strict YAML, cross-field invariants, secret redaction, "
                    "and provider endpoint policy verified"
                ),
            },
            "src/mathproofmesh/goal_preflight.py": {
                "status": "migrated",
                "java_path": (
                    f"{config_package}/GoalNormalizationError.java; "
                    f"{config_package}/GoalClarificationRequired.java; "
                    f"{config_package}/GoalPreflightFunctions.java; "
                    f"{config_package}/GoalPreflightService.java"
                ),
                "verified_by": "GoalPreflightParityTest; Maven verify",
                "notes": (
                    "Deterministic zero-API fast path, clarification, "
                    "canonical goal freezing, and bounded normalizer verified"
                ),
            },
        },
    )

    test_package = (
        "mathproofmesh-server/src/test/java/"
        "io/github/aililuola/mathproofmesh/config"
    )
    update_rows(
        "migration/test-state.csv",
        "python_test_file",
        {
            "tests/test_goal_preflight.py": {
                "status": "ported",
                "java_path": f"{test_package}/GoalPreflightParityTest.java",
                "verified_by": "GoalPreflightParityTest; Maven verify",
                "notes": "All 6 Python test semantics mapped to passing JUnit cases",
            },
            "tests/test_hierarchical_config_invariants.py": {
                "status": "ported",
                "java_path": (
                    f"{test_package}/"
                    "HierarchicalConfigInvariantsParityTest.java"
                ),
                "verified_by": (
                    "HierarchicalConfigInvariantsParityTest; Maven verify"
                ),
                "notes": "Active hierarchical topology continuation invariant ported",
            },
        },
    )

    profile_targets = {
        ".env.example": (
            ".env.local.example; migration/baseline/auxiliary/.env.example"
        ),
        "config.deepseek-v4-pro.proof-control-active.yaml": (
            "migration/baseline/auxiliary/"
            "config.deepseek-v4-pro.proof-control-active.yaml; "
            "config/proof-control-active.yaml"
        ),
        "config.deepseek-v4-pro.proof-control-shadow.yaml": (
            "migration/baseline/auxiliary/"
            "config.deepseek-v4-pro.proof-control-shadow.yaml; "
            "config/proof-control-shadow.yaml"
        ),
        "config.deepseek-v4-pro.smoke.yaml": (
            "migration/baseline/auxiliary/config.deepseek-v4-pro.smoke.yaml; "
            "config/deepseek-v4-pro-smoke.yaml"
        ),
        "config.deepseek-v4-pro.topology-active.yaml": (
            "migration/baseline/auxiliary/"
            "config.deepseek-v4-pro.topology-active.yaml; "
            "config/topology-active.yaml"
        ),
        "config.deepseek-v4-pro.yaml": (
            "migration/baseline/auxiliary/config.deepseek-v4-pro.yaml; "
            "config/deepseek-v4-pro.yaml"
        ),
        "config.example.yaml": (
            "migration/baseline/auxiliary/config.example.yaml; "
            "config/application.yaml"
        ),
    }
    update_rows(
        "migration/auxiliary-state.csv",
        "source_file",
        {
            source_file: {
                "status": "translated_verified",
                "java_path": target_path,
                "verified_by": (
                    "AuxiliaryFixtureIntegrityTest; ConfigProfileParityTest; "
                    "SecretValueSecurityTest; Maven verify"
                ),
                "notes": (
                    "Exact baseline fixture retained; strict Java parse and "
                    "redacted semantic parity verified"
                    if source_file != ".env.example"
                    else (
                        "Exact baseline retained; local template contains "
                        "variable names only and no secret values"
                    )
                ),
            }
            for source_file, target_path in profile_targets.items()
        },
    )


if __name__ == "__main__":
    main()
