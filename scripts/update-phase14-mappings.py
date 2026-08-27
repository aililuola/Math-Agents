from __future__ import annotations

import csv
from pathlib import Path


ROOT = (
    Path.cwd()
    if Path.cwd().joinpath("migration", "source-state.csv").is_file()
    else Path(__file__).parent.parent
)
API = (
    "mathproofmesh-server/src/main/java/"
    "io/github/aililuola/mathproofmesh/api"
)

SOURCE_ARTIFACTS = {
    "src/mathproofmesh/activity.py": [
        "ActivityStatus.java",
        "ActivityImportance.java",
        "ActivityEvent.java",
        "ActivitySanitizer.java",
        "ActivityStream.java",
        "ConsoleActivityView.java",
    ],
    "src/mathproofmesh/cli.py": ["MathProofMeshCommand.java"],
    "src/mathproofmesh/mock_demo.py": ["MockDemoFunctions.java"],
    "src/mathproofmesh/reasoning_trace.py": [
        "ReasoningTraceBinding.java",
        "ReasoningTraceStore.java",
        "ReasoningTraceCall.java",
    ],
    "src/mathproofmesh/report.py": ["ReportFunctions.java"],
    "src/mathproofmesh/server.py": [
        "SolveRequest.java",
        "ResumeRequest.java",
        "HealthController.java",
        "SolveController.java",
        "ResumeController.java",
        "RunQueryController.java",
        "RunApiService.java",
        "SseEncoder.java",
        "BearerTokenFilter.java",
        "RequestSafetyFilter.java",
        "TraceCorrelationFilter.java",
        "ApiExceptionHandler.java",
    ],
}


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
    if row["phase"] != "14":
        return False
    artifacts = SOURCE_ARTIFACTS[row["source_file"]]
    row.update(
        status="migrated",
        java_path="; ".join(f"{API}/{artifact}" for artifact in artifacts),
        verified_by=(
            "12 authority-named JUnit cases, 7 API security/streaming gates, "
            "and online/offline Maven verify"
        ),
        notes=(
            "REST, resumable SSE, bounded authenticated queries, CLI, reports, "
            "metrics, trace correlation, and content redaction retain authority semantics"
        ),
    )
    return True


def test_transform(row: dict[str, str]) -> bool:
    if row["primary_phase"] != "14":
        return False
    row.update(
        status="ported",
        java_path=(
            "mathproofmesh-server/src/test/java/io/github/aililuola/"
            f"mathproofmesh/api/{row['target_java_test']}"
        ),
        verified_by=(
            "ApiParityScenarios and dedicated authority-named parameterized "
            "JUnit case; online/offline Maven verify"
        ),
        notes=(
            f"{row['python_test_functions']} declared authority functions "
            "retained as independently reported JUnit cases"
        ),
    )
    return True


def auxiliary_transform(row: dict[str, str]) -> bool:
    if row["phase"] != "14":
        return False
    if row["source_file"] == "docs/ACTIVITY_TIMELINE.md":
        java_path = (
            "docs/legacy/python-baseline/ACTIVITY_TIMELINE.md; "
            "docs/observability.md"
        )
        notes = (
            "Authority document retained byte-for-byte; active event, "
            "redaction, SSE, metric, trace, and operations semantics consolidated"
        )
    elif row["source_file"] == "examples/problem.txt":
        java_path = "examples/problem.txt"
        notes = (
            "Authority example retained byte-for-byte and exercised through "
            "CLI, REST service, and Mock demo parity gates"
        )
    else:
        raise RuntimeError(f"unexpected phase-14 auxiliary row: {row['source_file']}")
    row.update(
        status="translated_verified",
        java_path=java_path,
        verified_by=(
            "byte-exact SHA-256 verification; phase-14 API/CLI gates; "
            "online/offline Maven verify"
        ),
        notes=notes,
    )
    return True


def main() -> None:
    counts = {
        "source": rewrite("migration/source-state.csv", source_transform),
        "test": rewrite("migration/test-state.csv", test_transform),
        "auxiliary": rewrite("migration/auxiliary-state.csv", auxiliary_transform),
    }
    expected = {"source": 6, "test": 4, "auxiliary": 2}
    if counts != expected:
        raise SystemExit(f"unexpected phase-14 mapping counts: {counts}")
    print(counts)


if __name__ == "__main__":
    main()
