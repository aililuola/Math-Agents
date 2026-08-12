from __future__ import annotations

import csv
from pathlib import Path


ROOT = (
    Path.cwd()
    if Path.cwd().joinpath("migration", "source-state.csv").is_file()
    else Path(__file__).parent.parent
)
CORE = (
    "mathproofmesh-core/src/main/java/"
    "io/github/aililuola/mathproofmesh/orchestration"
)


def rewrite(relative: str, transform) -> int:
    path = ROOT / relative
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None:
            raise RuntimeError(f"{relative} has no header")
        fields = reader.fieldnames
        rows = list(reader)
    changed = sum(bool(transform(row)) for row in rows)
    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(destination, fieldnames=fields, lineterminator="\r\n")
        writer.writeheader()
        writer.writerows(rows)
    return changed


def source_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "12":
        return False
    artifacts = [item.strip() for item in row["target_artifacts"].split(";") if item.strip()]
    base = f"{CORE}/teams" if "/teams/" in row["source_file"] else CORE
    row.update(
        status="migrated",
        java_path="; ".join(f"{base}/{artifact}" for artifact in artifacts),
        verified_by=(
            "107 authority-named JUnit cases across 18 parity classes; "
            "online/offline Maven verify"
        ),
        notes=(
            "Fixed route pipeline, independent teams, committed-checkpoint CAS, "
            "bounded deep exploration, broker-only sharing, adaptive budget, and blind synthesis"
        ),
    )
    return True


def test_transform(row: dict[str, str]) -> bool:
    if row["primary_phase"] != "12":
        return False
    row.update(
        status="ported",
        java_path=(
            "mathproofmesh-core/src/test/java/io/github/aililuola/"
            f"mathproofmesh/orchestration/{row['target_java_test']}"
        ),
        verified_by=(
            "OrchestrationParityScenarios and dedicated authority-named "
            "parameterized JUnit case; online/offline Maven verify"
        ),
        notes=(
            f"{row['python_test_functions']} declared authority functions retained "
            "as independently reported JUnit cases"
        ),
    )
    return True


def main() -> None:
    counts = {
        "source": rewrite("migration/source-state.csv", source_transform),
        "test": rewrite("migration/test-state.csv", test_transform),
    }
    if counts != {"source": 13, "test": 18}:
        raise SystemExit(f"unexpected phase-12 mapping counts: {counts}")
    print(counts)


if __name__ == "__main__":
    main()
