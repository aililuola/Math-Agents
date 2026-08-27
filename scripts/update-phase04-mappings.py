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
                f"{relative_path}: unknown columns {sorted(unknown_columns)}"
            )
        row.update(replacement)
        matched.add(key)
    if matched != set(updates):
        raise RuntimeError(
            f"{relative_path}: missing rows {sorted(set(updates) - matched)}"
        )

    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(
            destination,
            fieldnames=fieldnames,
            lineterminator="\r\n",
        )
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    production = (
        "mathproofmesh-server/src/main/java/"
        "io/github/aililuola/mathproofmesh/persistence"
    )
    tests = (
        "mathproofmesh-server/src/test/java/"
        "io/github/aililuola/mathproofmesh/persistence"
    )
    update_rows(
        "migration/source-state.csv",
        "source_file",
        {
            "src/mathproofmesh/store.py": {
                "status": "migrated",
                "java_path": (
                    f"{production}/ArtifactStore.java; "
                    f"{production}/CheckpointRepository.java; "
                    f"{production}/PersistenceMetrics.java; "
                    f"{production}/RunRepository.java; "
                    f"{production}/RunLeaseRepository.java; "
                    f"{production}/EventLogRepository.java; "
                    f"{production}/OutboxRepository.java; "
                    f"{production}/InboxRepository.java"
                ),
                "verified_by": (
                    "StoreParityTest; WorkingCheckpointAndMetricsParityTest; "
                    "PersistencePostgresIT; PersistencePolicyTest; Maven verify"
                ),
                "notes": (
                    "Content addressing, atomic replacement retry and durability, "
                    "path/symlink/hash/size/quota rejection, PostgreSQL run isolation, "
                    "working-checkpoint isolation, optimistic locking, fenced leases, "
                    "transactional event/outbox, and inbox deduplication verified"
                ),
            }
        },
    )
    update_rows(
        "migration/test-state.csv",
        "python_test_file",
        {
            "tests/test_store.py": {
                "status": "ported",
                "java_path": f"{tests}/StoreParityTest.java",
                "verified_by": "StoreParityTest; Maven verify",
                "notes": "All 4 Python test functions map to passing JUnit cases",
            },
            "tests/test_working_checkpoint_and_metrics.py": {
                "status": "ported",
                "java_path": (
                    f"{tests}/WorkingCheckpointAndMetricsParityTest.java; "
                    f"{tests}/PersistencePostgresIT.java"
                ),
                "verified_by": (
                    "WorkingCheckpointAndMetricsParityTest; "
                    "PersistencePostgresIT; Maven verify"
                ),
                "notes": (
                    "All 3 Python semantics map to passing metric, fact-inventory, "
                    "and PostgreSQL working-checkpoint isolation tests"
                ),
            },
        },
    )


if __name__ == "__main__":
    main()
