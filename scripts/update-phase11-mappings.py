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
    "io/github/aililuola/mathproofmesh/inspiration"
)


def rewrite(relative: str, transform) -> int:
    path = ROOT / relative
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None:
            raise RuntimeError(f"{relative} has no header")
        fields = reader.fieldnames
        rows = list(reader)
    changed = 0
    for row in rows:
        if transform(row):
            changed += 1
    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(destination, fieldnames=fields, lineterminator="\r\n")
        writer.writeheader()
        writer.writerows(rows)
    return changed


def source_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "11":
        return False
    artifacts = [item.strip() for item in row["target_artifacts"].split(";")]
    paths = []
    for artifact in artifacts:
        if not artifact:
            continue
        if artifact.startswith("no duplicate Java DTOs"):
            paths.append(
                "mathproofmesh-contracts/src/main/java/"
                "io/github/aililuola/mathproofmesh/contract"
            )
        else:
            paths.append(f"{CORE}/{artifact}")
    row.update(
        status="migrated",
        java_path="; ".join(paths),
        verified_by=(
            "70 authority-named JUnit cases across 23 parity classes; "
            "online/offline Maven verify"
        ),
        notes=(
            "Bounded advisory inspiration with scheduler admission, independent "
            "review, novelty/duplicate gates, deterministic learning, and no "
            "Fact or checkpoint authority"
        ),
    )
    return True


def test_transform(row: dict[str, str]) -> bool:
    if row["primary_phase"] != "11":
        return False
    row.update(
        status="ported",
        java_path=(
            "mathproofmesh-core/src/test/java/io/github/aililuola/"
            f"mathproofmesh/inspiration/{row['target_java_test']}"
        ),
        verified_by=(
            "InspirationParityScenarios and dedicated authority-named "
            "parameterized JUnit case; online/offline Maven verify"
        ),
        notes=(
            f"{row['python_test_functions']} declared authority functions retained "
            "as independently reported JUnit cases"
        ),
    )
    return True


def auxiliary_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "11":
        return False
    if row["source_file"].endswith(".jsonl"):
        status = "copied_verified"
        java_path = row["target_path"]
    else:
        status = "translated_verified"
        java_path = (
            "docs/legacy/python-baseline/INSPIRATION_ENGINE.md; "
            "docs/inspiration.md"
        )
    row.update(
        status=status,
        java_path=java_path,
        verified_by=(
            "AuxiliaryFixtureIntegrityTest; Inspiration parity tests; "
            "online/offline Maven verify"
        ),
        notes=(
            "Byte-exact authority retained and active Java semantics documented; "
            "project-local fixture use only"
        ),
    )
    return True


def main() -> None:
    counts = {
        "source": rewrite("migration/source-state.csv", source_transform),
        "test": rewrite("migration/test-state.csv", test_transform),
        "auxiliary": rewrite("migration/auxiliary-state.csv", auxiliary_transform),
    }
    if counts != {"source": 23, "test": 23, "auxiliary": 2}:
        raise SystemExit(f"unexpected phase-11 mapping counts: {counts}")
    print(counts)


if __name__ == "__main__":
    main()
