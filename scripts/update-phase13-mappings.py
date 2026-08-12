from __future__ import annotations

import csv
from pathlib import Path


ROOT = (
    Path.cwd()
    if Path.cwd().joinpath("migration", "source-state.csv").is_file()
    else Path(__file__).parent.parent
)
SERVER = (
    "mathproofmesh-server/src/main/java/"
    "io/github/aililuola/mathproofmesh/workflow"
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
    if row["phase"] != "13":
        return False
    artifacts = [
        f"{SERVER}/VerificationBundle.java",
        f"{SERVER}/SolveState.java",
        f"{SERVER}/RunStageMachine.java",
        f"{CORE}/InProcessRunCoordinator.java",
        f"{SERVER}/MathProofMeshSolveWorkflow.java",
        f"{SERVER}/RouteExplorationWorkflow.java",
        f"{SERVER}/WorkflowActivities.java",
        f"{SERVER}/IdempotentWorkflowActivities.java",
    ]
    row.update(
        status="migrated",
        java_path="; ".join(artifacts),
        verified_by=(
            "25 authority-named JUnit cases across 8 parity classes; "
            "Temporal TestWorkflowEnvironment, history replay, container "
            "persistence, and online/offline Maven verify"
        ),
        notes=(
            "Two deterministic workflows, idempotent Activities, durable "
            "signals/updates/queries, retry recovery, Continue-As-New, and "
            "legacy checkpoint migration"
        ),
    )
    return True


def test_transform(row: dict[str, str]) -> bool:
    if row["primary_phase"] != "13":
        return False
    row.update(
        status="ported",
        java_path=(
            "mathproofmesh-server/src/test/java/io/github/aililuola/"
            f"mathproofmesh/workflow/{row['target_java_test']}"
        ),
        verified_by=(
            "TemporalParityScenarios and dedicated authority-named "
            "parameterized JUnit case; online/offline Maven verify"
        ),
        notes=(
            f"{row['python_test_functions']} declared authority functions "
            "retained as independently reported JUnit cases"
        ),
    )
    return True


def auxiliary_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "13":
        return False
    row.update(
        status="translated_verified",
        java_path=(
            "docs/legacy/python-baseline/CHECKPOINT_RESUME.md; "
            "docs/compatibility.md; docs/temporal.md"
        ),
        verified_by=(
            "byte-exact SHA-256 verification; Temporal replay and resume "
            "tests; online/offline Maven verify"
        ),
        notes=(
            "Authority document retained byte-for-byte; current workflow, "
            "checkpoint, recovery, and operational semantics consolidated"
        ),
    )
    return True


def main() -> None:
    counts = {
        "source": rewrite("migration/source-state.csv", source_transform),
        "test": rewrite("migration/test-state.csv", test_transform),
        "auxiliary": rewrite("migration/auxiliary-state.csv", auxiliary_transform),
    }
    expected = {"source": 1, "test": 8, "auxiliary": 1}
    if counts != expected:
        raise SystemExit(f"unexpected phase-13 mapping counts: {counts}")
    print(counts)


if __name__ == "__main__":
    main()
